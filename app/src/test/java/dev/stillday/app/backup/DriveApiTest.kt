package dev.stillday.app.backup

import dev.stillday.app.data.plannerJson
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import java.io.IOException

class DriveApiTest {
    private lateinit var server: MockWebServer
    private var invalidations = 0
    private val tokens = object : DriveTokens {
        override suspend fun token(account: String): String { assertEquals("person@example.com", account); return "test-token" }
        override suspend fun invalidate(token: String) { invalidations++ }
    }
    private val content = BackupContent.parse(plannerJson.encodeToString(Backup(exportedAt = "2026-11-11T12:00:00", preferences = Preferences(), data = Snapshot())))
    private lateinit var api: DriveApi
    @Before fun setup() { server = MockWebServer(); server.start(); api = DriveApi(tokens, baseUrl = server.url("/").toString()) }
    @After fun close() { server.shutdown() }
    private fun metadata(etag: String = "\"v1\"") = MockResponse().setHeader("ETag", etag).setBody("""{"id":"file","name":"Stillday backup.json","trashed":false}""")
    @Test fun conditionalUpdateKeepsFileIdAndNeverUsesAnUnconditionalOverwrite() = runBlocking {
        server.enqueue(metadata("\"v2\""))
        api.write("person@example.com", "file", "\"v1\"", content)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method); assertEquals("\"v1\"", request.getHeader("If-Match"))
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertTrue(request.path!!.startsWith("/upload/drive/v3/files/file?"))
        assertTrue(request.body.readUtf8().contains(content.text))
    }
    @Test fun revisionMismatchBecomesAConflictInsteadOfRetryingBlindly() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(412))
        try { api.write("person@example.com", "file", "\"v1\"", content); fail("Expected conflict") }
        catch (_: DriveConflictException) { }
        assertEquals(1, server.requestCount)
    }
    @Test fun readChecksRevisionAroundDownload() = runBlocking {
        server.enqueue(metadata()); server.enqueue(MockResponse().setBody(content.text)); server.enqueue(metadata())
        assertEquals(content.hash, api.read("person@example.com", "file").content.hash)
        assertEquals(3, server.requestCount)
    }
    @Test fun unchangedBackupCheckFetchesOnlyMetadata() = runBlocking {
        server.enqueue(metadata())
        assertTrue(api.unchanged("person@example.com", "file", "\"v1\""))
        val request = server.takeRequest()
        assertFalse(request.path!!.contains("alt=media")); assertEquals(1, server.requestCount)
    }
    @Test fun missingRevisionTagPreventsAnyUploadOrImport() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"file","name":"Backup"}"""))
        try { api.read("person@example.com", "file"); fail("No revision tag accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(1, server.requestCount)
        try { api.write("person@example.com", "file", "", content); fail("No revision tag accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(1, server.requestCount)
    }
    @Test fun authorizationExpiryInvalidatesTokenAndRequiresReconnect() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try { api.list("person@example.com"); fail("Expired authorization accepted") } catch (_: DriveAuthException) { }
        assertEquals(1, invalidations)
    }
    @Test fun listingUsesOnlyPrivateAppStorageAndFollowsPagination() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"nextPageToken":"next","files":[{"id":"a","name":"First"}]}"""))
        server.enqueue(MockResponse().setBody("""{"files":[{"id":"b","name":"Second"}]}"""))
        assertEquals(listOf("a", "b"), api.list("person@example.com").map { it.id })
        assertTrue(server.takeRequest().path!!.contains("spaces=appDataFolder"))
        assertTrue(server.takeRequest().path!!.contains("pageToken=next"))
    }
    @Test fun creationSetsPrivateParentAndDoesNotTouchOtherBackups() = runBlocking {
        server.enqueue(metadata())
        assertEquals("file", api.create("person@example.com", "My backup", content).id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = request.body.readUtf8()
        assertTrue(body.contains("appDataFolder")); assertTrue(body.contains("My backup")); assertTrue(body.contains("stilldayBackup"))
    }
    @Test fun futureAppBackupIsNotSilentlyDowngraded() = runBlocking {
        server.enqueue(MockResponse().setHeader("ETag", "v1").setBody("""{"id":"file","name":"Backup","appProperties":{"stilldayVersion":"99999"}}"""))
        try { api.read("person@example.com", "file"); fail("Newer writer accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(1, server.requestCount)
    }
    @Test fun missingFileAndOversizedBackupRemainFailures() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        try { api.read("person@example.com", "file"); fail("Missing file accepted") } catch (_: IOException) { }
        server.enqueue(metadata()); server.enqueue(MockResponse().setHeader("Content-Length", BackupContent.MAX_BYTES + 1))
        try { api.read("person@example.com", "file"); fail("Oversized file accepted") } catch (_: IllegalArgumentException) { }
    }
}
