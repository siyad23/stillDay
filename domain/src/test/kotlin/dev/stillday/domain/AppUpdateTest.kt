package dev.stillday.domain

import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    private val hash = "a".repeat(64)
    private val app = InstalledApp("dev.stillday.app", 3, 36, setOf("trusted"))
    private val manifest = UpdateManifest(packageName = app.packageName, versionCode = 4, versionName = "1.0.2",
        minSdk = 26, apkName = "Stillday.apk", sizeBytes = 100, sha256 = hash)
    private val update = AppUpdate(manifest, "https://github.com/siyad23/stillDay/releases/download/v1.0.2/Stillday.apk", UpdatePolicy.RELEASES_URL)
    private val apk = ApkIdentity(app.packageName, 4, 26, setOf("trusted"))
    private fun rejected(block: () -> Unit) { try { block(); fail("Unsafe update accepted") } catch (_: IllegalArgumentException) { } }

    @Test fun acceptsNewerCodeRegardlessOfVersionNameOrder() {
        assertTrue(UpdatePolicy.validateManifest(manifest.copy(versionName = "0.1"), app))
        assertFalse(UpdatePolicy.validateManifest(manifest.copy(versionCode = 3, versionName = "999.0"), app))
        assertFalse(UpdatePolicy.validateManifest(manifest.copy(versionCode = 2), app))
    }
    @Test fun verifiesExactPackageVersionSizeDigestAndCertificate() { UpdatePolicy.validateApk(update, app, apk, 100, hash.uppercase()) }
    @Test fun rejectsWrongPackage() { rejected { UpdatePolicy.validateApk(update, app, apk.copy(packageName = "other.app"), 100, hash) } }
    @Test fun rejectsWrongVersion() { rejected { UpdatePolicy.validateApk(update, app, apk.copy(versionCode = 5), 100, hash) } }
    @Test fun rejectsWrongSigningKeyAndMissingSignatures() {
        rejected { UpdatePolicy.validateApk(update, app, apk.copy(signingCertificates = setOf("different")), 100, hash) }
        rejected { UpdatePolicy.validateApk(update, app.copy(signingCertificates = emptySet()), apk, 100, hash) }
    }
    @Test fun rejectsTruncatedOversizedAndCorruptDownloads() {
        rejected { UpdatePolicy.validateApk(update, app, apk, 99, hash) }
        rejected { UpdatePolicy.validateApk(update, app, apk, 101, hash) }
        rejected { UpdatePolicy.validateApk(update, app, apk, 100, "b".repeat(64)) }
    }
    @Test fun rejectsIncompatibleAndroidVersionAndMismatchedSdk() {
        rejected { UpdatePolicy.validateManifest(manifest.copy(minSdk = 37), app) }
        rejected { UpdatePolicy.validateApk(update, app, apk.copy(minSdk = 28), 100, hash) }
    }
    @Test fun rejectsMalformedMetadata() {
        listOf(manifest.copy(schemaVersion = 2), manifest.copy(versionCode = 0), manifest.copy(versionName = ""),
            manifest.copy(apkName = "../Stillday.apk"), manifest.copy(apkName = "other.zip"), manifest.copy(sizeBytes = 0),
            manifest.copy(sizeBytes = UpdatePolicy.MAX_APK_BYTES + 1), manifest.copy(sha256 = "bad"), manifest.copy(packageName = "other"))
            .forEach { m -> rejected { UpdatePolicy.validateManifest(m, app) } }
    }
    @Test fun rejectsInsecureAndUntrustedUrls() {
        listOf("http://github.com/siyad23/stillDay/releases/download/v1/Stillday.apk", "https://evil.example/a.apk",
            "https://github.com.evil.example/a.apk", "https://user@github.com/a.apk", "https://github.com:8443/a.apk",
            "file:///data/app/update.apk").forEach { url -> rejected { UpdatePolicy.requireHttps(url) } }
    }
    @Test fun rejectsOtherRepositoriesAndDirectCdnAssetOrigins() {
        rejected { UpdatePolicy.requireReleaseAsset("https://github.com/someone/other/releases/download/v1/app.apk") }
        rejected { UpdatePolicy.requireReleaseAsset("https://release-assets.githubusercontent.com/file.apk") }
        UpdatePolicy.requireReleaseAsset(update.downloadUrl)
    }
    @Test fun permitsOnlyKnownHttpsRedirectHosts() {
        UpdatePolicy.requireHttps("https://release-assets.githubusercontent.com/file.apk?signature=value")
        rejected { UpdatePolicy.requireHttps("https://github.com/file.apk#fragment") }
    }
}
