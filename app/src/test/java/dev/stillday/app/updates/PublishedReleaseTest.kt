package dev.stillday.app.updates

import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in live check: ordinary tests remain independent of GitHub availability and release changes. */
class PublishedReleaseTest {
    @Test fun publicReleaseWorksForCurrentAndOlderInstallations() = runBlocking {
        assumeTrue(System.getenv("STILLDAY_LIVE_UPDATES") == "true")
        val code = requireNotNull(System.getenv("STILLDAY_LIVE_VERSION_CODE")).toLong()
        val name = requireNotNull(System.getenv("STILLDAY_LIVE_VERSION_NAME"))
        val source = GitHubUpdateSource(GitHubTransport())
        val installed = InstalledApp("dev.stillday.app", code, 36, setOf("verified-separately"))
        assertEquals(ReleaseResult.UpToDate, source.check(installed))
        val available = source.check(installed.copy(versionCode = code - 1)) as ReleaseResult.Available
        assertEquals(code, available.update.manifest.versionCode)
        assertEquals(name, available.update.manifest.versionName)
        assertTrue(available.update.downloadUrl.endsWith("/" + available.update.manifest.apkName))
        assertEquals("v$name", available.update.releaseTag)
    }
}
