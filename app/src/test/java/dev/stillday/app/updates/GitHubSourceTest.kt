package dev.stillday.app.updates

import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GitHubSourceTest {
    private val app = InstalledApp("dev.stillday.app", 3, 36, setOf("key"))
    private val hash = "a".repeat(64)
    private val m = UpdateManifest(packageName = app.packageName, versionCode = 4, versionName = "1.0.2", minSdk = 26, apkName = "Stillday.apk", sizeBytes = 100, sha256 = hash)
    private val base = "https://github.com/${UpdatePolicy.REPOSITORY}/releases/download/v1.0.2/"
    private fun release(apkSize: Long = 100, digest: String = "sha256:$hash", draft: Boolean = false, prerelease: Boolean = false): String = """
        {"tag_name":"v0.1","body":"Release notes","draft":$draft,"prerelease":$prerelease,"assets":[
        {"name":"update.json","browser_download_url":"${base}update.json"},
        {"name":"Stillday.apk","size":$apkSize,"digest":"$digest","browser_download_url":"${base}Stillday.apk"}]}
    """.trimIndent()
    private class Transport(val responses: Map<String, String>) : UpdateTransport {
        val requested = mutableListOf<String>()
        override suspend fun text(url: String, maxBytes: Int): String { requested += url; return responses[url] ?: error("Unexpected request") }
        override suspend fun download(url: String, destination: File, expectedBytes: Long, progress: (Long) -> Unit) = error("Metadata checks must not download APKs")
    }
    private fun source(body: String = release(), manifest: UpdateManifest = m) = GitHubUpdateSource(Transport(mapOf(UpdatePolicy.API_URL to body, base + "update.json" to UpdatePolicy.json.encodeToString(manifest))))
    @Test fun discoversUpdateByActualVersionCode() = runBlocking {
        val result = source().check(app) as ReleaseResult.Available
        assertEquals(4L, result.update.manifest.versionCode)
        assertEquals("v0.1", result.update.releaseTag)
        assertEquals("Release notes", result.update.notes)
    }
    @Test fun ignoresOlderAndEqualVersions() = runBlocking {
        assertEquals(ReleaseResult.UpToDate, source(manifest = m.copy(versionCode = 3)).check(app))
        assertEquals(ReleaseResult.UpToDate, source(manifest = m.copy(versionCode = 2)).check(app))
    }
    @Test fun legacyReleaseDoesNotDownloadItsApkToGuessVersion() = runBlocking {
        val transport = Transport(mapOf(UpdatePolicy.API_URL to """{"assets":[{"name":"Stillday-debug.apk"}]}"""))
        assertEquals(ReleaseResult.NotConfigured, GitHubUpdateSource(transport).check(app))
        assertEquals(listOf(UpdatePolicy.API_URL), transport.requested)
    }
    @Test fun ignoresDraftsAndPrereleases() = runBlocking {
        assertEquals(ReleaseResult.NoRelease, source(release(draft = true)).check(app))
        assertEquals(ReleaseResult.NoRelease, source(release(prerelease = true)).check(app))
    }
    @Test fun handlesMissingRelease() = runBlocking {
        val transport = object : UpdateTransport {
            override suspend fun text(url: String, maxBytes: Int): String = throw UpdateHttpException(404, "missing")
            override suspend fun download(url: String, destination: File, expectedBytes: Long, progress: (Long) -> Unit) = Unit
        }
        assertEquals(ReleaseResult.NoRelease, GitHubUpdateSource(transport).check(app))
    }
    @Test fun rejectsIncorrectAssetSize() = runBlocking {
        try { source(release(apkSize = 101)).check(app); fail("Incorrect size accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun rejectsIncorrectGitHubDigest() = runBlocking {
        try { source(release(digest = "sha256:${"b".repeat(64)}")).check(app); fail("Incorrect digest accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun rejectsForeignAssetLinks() = runBlocking {
        try { source(release().replace(base, "https://github.com/other/repo/releases/download/v1/")).check(app); fail("Foreign link accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun malformedJsonDoesNotBecomeAnUpdate() = runBlocking {
        try { source("bad json").check(app); fail("Malformed response accepted") } catch (_: Exception) { }
    }
}
