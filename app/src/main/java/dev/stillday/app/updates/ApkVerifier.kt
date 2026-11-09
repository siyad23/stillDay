package dev.stillday.app.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dev.stillday.domain.*
import java.io.File
import java.security.MessageDigest

interface UpdateVerifier {
    fun installed(): InstalledApp
    fun verify(file: File, update: AppUpdate)
}

class ApkVerifier(private val context: Context) : UpdateVerifier {
    @Suppress("DEPRECATION")
    private val flags: Int get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            .orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()

    @Suppress("DEPRECATION")
    override fun installed(): InstalledApp {
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        return InstalledApp(context.packageName, PackageInfoCompat.getLongVersionCode(info), Build.VERSION.SDK_INT, certificates(info))
    }

    @Suppress("DEPRECATION")
    override fun verify(file: File, update: AppUpdate) {
        require(file.isFile && file.length() == update.manifest.sizeBytes) { "The APK download is incomplete. Download it again." }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        require(digest.digest().hex().equals(update.manifest.sha256, true)) { "The APK checksum did not match. Download it again." }
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("The downloaded file is not a valid signed APK.")
        val identity = ApkIdentity(archive.packageName, PackageInfoCompat.getLongVersionCode(archive), archive.applicationInfo?.minSdkVersion ?: 0, certificates(archive))
        UpdatePolicy.validateApk(update, installed(), identity, file.length(), update.manifest.sha256)
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
