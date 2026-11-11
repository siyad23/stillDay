package dev.stillday.app.backup

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import java.io.IOException
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val DRIVE_BACKUP_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
fun driveAuthorizationRequest(email: String) = AuthorizationRequest.builder()
    .setAccount(Account(email, "com.google")).setRequestedScopes(listOf(Scope(DRIVE_BACKUP_SCOPE))).build()

suspend fun <T> Task<T>.awaitDrive(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

class GoogleDriveTokens(private val context: Context) : DriveTokens {
    override suspend fun token(account: String): String {
        val result = try { Identity.getAuthorizationClient(context).authorize(driveAuthorizationRequest(account)).awaitDrive() }
        catch (error: ApiException) { throw when (error.statusCode) {
            4, 16 -> DriveAuthException()
            10 -> DriveAuthException("Google Drive authorization needs setup. Verify the Android OAuth package and signing SHA-1 in your Google Cloud project.")
            else -> IOException("Google authorization is unavailable. Your local changes are saved and backup will retry.", error)
        } }
        if (result.hasResolution()) throw DriveAuthException()
        return result.accessToken?.takeIf { it.isNotBlank() } ?: throw DriveAuthException()
    }
    override suspend fun invalidate(token: String) {
        Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build()).awaitDrive()
    }
}
