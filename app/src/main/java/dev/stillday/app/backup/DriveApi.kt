package dev.stillday.app.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import dev.stillday.app.BuildConfig
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

interface DriveTokens {
    suspend fun token(account: String): String
    suspend fun invalidate(token: String)
}

open class DriveApi(private val tokens: DriveTokens, private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).writeTimeout(45, TimeUnit.SECONDS)
    .followRedirects(false).build(), private val baseUrl: String = "https://www.googleapis.com/") : BackupRemoteStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val type = "application/json; charset=utf-8".toMediaType()
    private val fields = "id,name,modifiedTime,md5Checksum,trashed,appProperties"
    private data class ResponseData(val text: String, val etag: String)
    private data class Metadata(val file: DriveFile, val etag: String, val md5: String?)

    suspend fun list(account: String): List<DriveFile> {
        val files = mutableListOf<DriveFile>()
        var page: String? = null
        do {
            val url = (baseUrl + "drive/v3/files").toHttpUrlBuilder()
                .addQueryParameter("spaces", "appDataFolder")
                .addQueryParameter("q", "trashed = false and appProperties has { key='stilldayBackup' and value='1' }")
                .addQueryParameter("fields", "nextPageToken,files(id,name,modifiedTime)")
                .addQueryParameter("pageSize", "100").addQueryParameter("orderBy", "modifiedTime desc")
            page?.let { url.addQueryParameter("pageToken", it) }
            val body = json.parseToJsonElement(request(account, Request.Builder().url(url.build()).get().build(), 1_000_000).text).jsonObject
            files += body["files"]?.jsonArray.orEmpty().map { file(it.jsonObject) }
            require(files.size <= 1000) { "Too many Drive backups. Remove unused backups before reconnecting." }
            page = body["nextPageToken"]?.jsonPrimitive?.contentOrNull
        } while (page != null)
        return files
    }

    suspend fun create(account: String, name: String, content: BackupContent): DriveFile {
        val metadata = buildJsonObject {
            put("name", name); put("mimeType", "application/json")
            putJsonArray("parents") { add("appDataFolder") }
            putJsonObject("appProperties") { put("stilldayBackup", "1"); put("stilldayVersion", BuildConfig.VERSION_CODE.toString()) }
        }
        val url = (baseUrl + "upload/drive/v3/files").toHttpUrlBuilder().addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", fields).build()
        val body = multipart(metadata.toString(), content.text)
        val response = request(account, Request.Builder().url(url).post(body).build(), 32_768)
        return file(json.parseToJsonElement(response.text).jsonObject)
    }

    override suspend fun read(account: String, fileId: String): RemoteBackup {
        repeat(3) {
            val before = metadata(account, fileId)
            val url = (baseUrl + "drive/v3/files/" + fileId).toHttpUrlBuilder().addQueryParameter("alt", "media").build()
            val response = request(account, Request.Builder().url(url).get().build(), BackupContent.MAX_BYTES)
            val after = metadata(account, fileId)
            if (before.etag == after.etag) {
                before.md5?.let { expected ->
                    val actual = MessageDigest.getInstance("MD5").digest(response.text.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    require(actual.equals(expected, true)) { "Drive backup changed during download. Try syncing again." }
                }
                return RemoteBackup(BackupContent.parse(response.text), after.etag)
            }
        }
        throw DriveConflictException()
    }

    override suspend fun unchanged(account: String, fileId: String, etag: String): Boolean = metadata(account, fileId).etag == etag

    override suspend fun write(account: String, fileId: String, expectedEtag: String, content: BackupContent): RemoteBackup {
        require(expectedEtag.isNotBlank()) { "Drive did not supply a revision tag. Backup was not overwritten." }
        val url = (baseUrl + "upload/drive/v3/files/" + fileId).toHttpUrlBuilder().addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", fields).build()
        val metadata = buildJsonObject { putJsonObject("appProperties") { put("stilldayBackup", "1"); put("stilldayVersion", BuildConfig.VERSION_CODE.toString()) } }
        val response = request(account, Request.Builder().url(url).header("If-Match", expectedEtag)
            .patch(multipart(metadata.toString(), content.text)).build(), 32_768)
        return RemoteBackup(content, response.etag)
    }

    private suspend fun metadata(account: String, id: String): Metadata {
        require(Regex("[A-Za-z0-9_-]+").matches(id)) { "Invalid Drive backup ID." }
        val url = (baseUrl + "drive/v3/files/" + id).toHttpUrlBuilder().addQueryParameter("fields", fields).build()
        val response = request(account, Request.Builder().url(url).get().build(), 32_768)
        val value = json.parseToJsonElement(response.text).jsonObject
        val writerVersion = value["appProperties"]?.jsonObject?.get("stilldayVersion")?.jsonPrimitive?.intOrNull
        require(writerVersion == null || writerVersion <= BuildConfig.VERSION_CODE) { "This backup was saved by a newer Stillday version. Update the app before syncing." }
        require(value["trashed"]?.jsonPrimitive?.booleanOrNull != true) { "The Drive backup is in the trash. Restore it or create a new backup." }
        require(response.etag.isNotBlank()) { "Drive did not supply a revision tag. Sync paused to protect your data." }
        return Metadata(file(value), response.etag, value["md5Checksum"]?.jsonPrimitive?.contentOrNull)
    }

    private fun file(value: JsonObject) = DriveFile(value.getValue("id").jsonPrimitive.content,
        value.getValue("name").jsonPrimitive.content, value["modifiedTime"]?.jsonPrimitive?.contentOrNull.orEmpty())

    private fun multipart(metadata: String, content: String): RequestBody = MultipartBody.Builder().setType("multipart/related".toMediaType())
        .addPart(metadata.toRequestBody(type)).addPart(content.toRequestBody(type)).build()

    private fun String.toHttpUrlBuilder(): HttpUrl.Builder = toHttpUrl().newBuilder()

    private suspend fun request(account: String, request: Request, limit: Int): ResponseData {
        val token = tokens.token(account)
        return withContext(Dispatchers.IO) {
            client.newCall(request.newBuilder().header("Authorization", "Bearer $token").header("Accept", "application/json").build()).execute().use { response ->
                if (response.code == 401) { tokens.invalidate(token); throw DriveAuthException() }
                if (response.code == 412) throw DriveConflictException()
                if (!response.isSuccessful) throw IOException(when (response.code) {
                    403 -> "Drive access was denied or its quota was reached. Check your Google Cloud Drive API and OAuth setup."
                    404 -> "Drive backup was not found. Reconnect the original account or create a new backup."
                    429 -> "Google Drive is busy. Automatic backup will retry."
                    else -> "Google Drive returned HTTP ${response.code}. Automatic backup will retry."
                })
                val body = response.body ?: throw IOException("Drive returned an empty response.")
                require(body.contentLength() <= limit) { "Drive response exceeds the backup size limit." }
                val output = java.io.ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        require(output.size() + count <= limit) { "Drive response exceeds the backup size limit." }
                        output.write(buffer, 0, count)
                    }
                }
                ResponseData(output.toString("UTF-8"), response.header("ETag").orEmpty())
            }
        }
    }
}
