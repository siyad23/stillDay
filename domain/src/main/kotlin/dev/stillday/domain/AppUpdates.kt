package dev.stillday.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

@Serializable
data class UpdateManifest(
    val schemaVersion: Int = 1,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkName: String,
    val sizeBytes: Long,
    val sha256: String
)

@Serializable
data class AppUpdate(val manifest: UpdateManifest, val downloadUrl: String, val releaseUrl: String,
    val notes: String = "", val releaseTag: String = "")

data class InstalledApp(val packageName: String, val versionCode: Long, val sdk: Int, val signingCertificates: Set<String>)
data class ApkIdentity(val packageName: String, val versionCode: Long, val minSdk: Int, val signingCertificates: Set<String>)

object UpdatePolicy {
    const val REPOSITORY = "siyad23/stillDay"
    const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val RELEASES_URL = "https://github.com/$REPOSITORY/releases"
    const val MANIFEST_NAME = "update.json"
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    private val redirects = setOf("github.com", "api.github.com", "release-assets.githubusercontent.com",
        "objects.githubusercontent.com", "github-releases.githubusercontent.com")
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun requireHttps(url: String) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host?.lowercase() in redirects && uri.userInfo == null &&
            uri.port in setOf(-1, 443) && uri.fragment == null) { "The update link is not a trusted GitHub HTTPS address." }
    }

    fun requireReleaseAsset(url: String) {
        requireHttps(url)
        val uri = URI(url)
        require(uri.host.equals("github.com", true) && uri.path.startsWith("/$REPOSITORY/releases/download/", true)) {
            "The update must come from Stillday's release repository."
        }
    }

    fun validateManifest(manifest: UpdateManifest, app: InstalledApp): Boolean {
        require(manifest.schemaVersion == 1) { "This update format is not supported." }
        require(manifest.packageName == app.packageName) { "The update belongs to another app." }
        require(manifest.versionCode > 0 && manifest.versionName.isNotBlank() && manifest.versionName.length <= 100)
        require(manifest.minSdk in 26..100 && manifest.minSdk <= app.sdk) { "This update needs a newer Android version." }
        require(manifest.sizeBytes in 1..MAX_APK_BYTES) { "The update size is invalid." }
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk", RegexOption.IGNORE_CASE).matches(manifest.apkName)) { "The APK filename is invalid." }
        require(Regex("[a-fA-F0-9]{64}").matches(manifest.sha256)) { "The update checksum is missing or invalid." }
        return manifest.versionCode > app.versionCode
    }

    fun validateApk(update: AppUpdate, app: InstalledApp, apk: ApkIdentity, bytes: Long, sha256: String) {
        require(validateManifest(update.manifest, app)) { "This update is not newer than the installed app." }
        require(bytes == update.manifest.sizeBytes) { "The APK download is incomplete." }
        require(sha256.equals(update.manifest.sha256, true)) { "The APK checksum did not match. Download the update again." }
        require(apk.packageName == app.packageName) { "The downloaded APK belongs to another app." }
        require(apk.versionCode == update.manifest.versionCode) { "The APK version does not match the release details." }
        require(apk.minSdk == update.manifest.minSdk && apk.minSdk <= app.sdk) { "The APK requires a different Android version." }
        require(app.signingCertificates.isNotEmpty() && apk.signingCertificates == app.signingCertificates) {
            "This release uses a different signing key. Ask for an update signed with the same key as your installed app. Your data has not been changed."
        }
    }
}
