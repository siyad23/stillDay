package dev.stillday.domain

import java.time.Instant
import java.time.ZoneId

object WakeSchedule {
    fun next(now: Instant, preferences: Preferences): Instant? {
        if (!preferences.wakeAlarmEnabled || preferences.prayerTimes.isEmpty()) return null
        val zone = ZoneId.of(preferences.timezone)
        val today = now.atZone(zone).toLocalDate()
        val candidate = DayPlanner.wake(today, preferences).atZone(zone).toInstant()
        return if (candidate > now) candidate else DayPlanner.wake(today.plusDays(1), preferences).atZone(zone).toInstant()
    }
}
