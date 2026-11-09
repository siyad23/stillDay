package dev.stillday.app.updates

import dev.stillday.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class UpdateHttpException(val status: Int, message: String) : Exception(message)

interface UpdateTransport {
    suspend fun text(url: String, maxBytes: Int): String
    suspend fun download(url: String, destination: File, expectedBytes: Long, progress: (Long) -> Unit)
}

class GitHubTransport : UpdateTransport {
    override suspend fun text(url: String, maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        transfer(url, output, maxBytes.toLong(), null) { }
        return output.toString("UTF-8")
    }

    override suspend fun download(url: String, destination: File, expectedBytes: Long, progress: (Long) -> Unit) {
        destination.outputStream().use { transfer(url, it, expectedBytes, expectedBytes, progress) }
    }

    private suspend fun transfer(url: String, output: OutputStream, maxBytes: Long, expected: Long?, progress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val active = AtomicReference<HttpURLConnection?>()
                continuation.invokeOnCancellation { active.get()?.disconnect() }
                try {
                    var current = url
                    var connection: HttpURLConnection? = null
                    for (redirect in 0..5) {
                        UpdatePolicy.requireHttps(current)
                        if (!continuation.isActive) return@suspendCancellableCoroutine
                        val candidate = URI(current).toURL().openConnection() as HttpURLConnection
                        connection = candidate
                        active.set(candidate)
                        candidate.instanceFollowRedirects = false
                        candidate.connectTimeout = 15_000
                        candidate.readTimeout = 20_000
                        candidate.setRequestProperty("User-Agent", "Stillday-UpdateChecker")
                        candidate.setRequestProperty("Accept", if (URI(current).host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
                        val code = candidate.responseCode
                        if (code in listOf(301, 302, 303, 307, 308)) {
                            val location = candidate.getHeaderField("Location") ?: error("The update server returned an empty redirect.")
                            current = URI(current).resolve(location).toString()
                            candidate.disconnect()
                            connection = null
                            continue
                        }
                        if (code !in 200..299) throw UpdateHttpException(code, when (code) {
                            404 -> "No public release is available yet."
                            403, 429 -> "GitHub has temporarily limited update checks. Try again later."
                            else -> "The update server returned HTTP $code. Try again later."
                        })
                        val length = candidate.contentLengthLong
                        require(length <= maxBytes && (expected == null || length < 0 || length == expected)) { "The update download size does not match the release." }
                        var count = 0L
                        var lastProgress = 0L
                        candidate.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (continuation.isActive) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                count += read
                                require(count <= maxBytes) { "The update response is too large." }
                                output.write(buffer, 0, read)
                                if (count - lastProgress >= 128 * 1024) { progress(count); lastProgress = count }
                            }
                        }
                        if (!continuation.isActive) return@suspendCancellableCoroutine
                        require(expected == null || count == expected) { "The APK download is incomplete. Try again." }
                        progress(count)
                        continuation.resume(Unit)
                        break
                    }
                    if (connection == null && continuation.isActive) error("The update server redirected too many times.")
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                } finally { active.get()?.disconnect() }
            }
        }
}

sealed interface ReleaseResult {
    data class Available(val update: AppUpdate) : ReleaseResult
    data object UpToDate : ReleaseResult
    data object NoRelease : ReleaseResult
    data object NotConfigured : ReleaseResult
}

class GitHubUpdateSource(private val transport: UpdateTransport) {
    suspend fun check(app: InstalledApp): ReleaseResult {
        val body = try { transport.text(UpdatePolicy.API_URL, 1_000_000) }
        catch (e: UpdateHttpException) { if (e.status == 404) return ReleaseResult.NoRelease else throw e }
        val release = UpdatePolicy.json.parseToJsonElement(body).jsonObject
        if (release["draft"]?.jsonPrimitive?.booleanOrNull == true || release["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return ReleaseResult.NoRelease
        val assets = release["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
        val metadata = assets.singleOrNull { it["name"]?.jsonPrimitive?.content == UpdatePolicy.MANIFEST_NAME } ?: return ReleaseResult.NotConfigured
        val metadataUrl = metadata.getValue("browser_download_url").jsonPrimitive.content
        UpdatePolicy.requireReleaseAsset(metadataUrl)
        val manifest = UpdatePolicy.json.decodeFromString<UpdateManifest>(transport.text(metadataUrl, 32_768))
        val newer = UpdatePolicy.validateManifest(manifest, app)
        if (!newer) return ReleaseResult.UpToDate
        val apk = assets.singleOrNull { it["name"]?.jsonPrimitive?.content == manifest.apkName } ?: error("The published APK is missing. Try again when the release is complete.")
        val apkUrl = apk.getValue("browser_download_url").jsonPrimitive.content
        UpdatePolicy.requireReleaseAsset(apkUrl)
        require(apk["size"]?.jsonPrimitive?.longOrNull == manifest.sizeBytes) { "The release APK size does not match its update details." }
        apk["digest"]?.jsonPrimitive?.contentOrNull?.let { digest ->
            require(digest.equals("sha256:${manifest.sha256}", true)) { "The release APK checksum does not match GitHub's checksum." }
        }
        return ReleaseResult.Available(AppUpdate(manifest, apkUrl, UpdatePolicy.RELEASES_URL,
            release["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(6000), release["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()))
    }
}
