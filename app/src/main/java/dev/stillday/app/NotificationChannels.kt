package dev.stillday.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import dev.stillday.domain.NotificationRule
import dev.stillday.domain.ReminderCategory
import java.security.MessageDigest

object NotificationChannels {
    fun id(category: ReminderCategory, sound: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(sound.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        return "reminder_${category.name.lowercase()}_$hash"
    }

    fun soundUri(context: Context, sound: String): Uri? = when (sound) {
        "silent" -> null
        "default" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        else -> {
            val uri = Uri.parse(sound)
            // Device-specific ringtone URIs in a restored backup may no longer exist.
            val readable = uri.scheme == "content" && runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            if (readable) uri else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }
    }

    fun ensure(context: Context, category: ReminderCategory, rule: NotificationRule): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        val uri = soundUri(context, rule.sound)
        // Auditory properties are immutable after channel creation. Each chosen sound
        // has a stable channel; existing Android overrides remain in the user's control.
        val id = id(category, uri?.toString() ?: "silent")
        manager.createNotificationChannel(NotificationChannel(id, category.label,
            if (rule.sound == "silent") NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Stillday ${category.label.lowercase()}"
            setSound(uri, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(false)
        })
        return id
    }
}
