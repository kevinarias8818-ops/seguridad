package com.example.util

import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class TimeSlotFilter(val label: String) {
    ALL("Todas las horas"),
    MORNING("Mañana (05–12h)"),
    AFTERNOON("Tarde (12–18h)"),
    NIGHT("Noche (18–05h)")
}

object DateTimeUtils {
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayDateFormat = SimpleDateFormat("dd MMM yyyy", Locale.forLanguageTag("es-ES"))
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val fullDateTimeFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    fun todayIso(): String = isoDateFormat.format(Date())

    fun formatIsoToDisplay(isoDate: String): String {
        return try {
            val parsed = isoDateFormat.parse(isoDate)
            if (parsed != null) displayDateFormat.format(parsed) else isoDate
        } catch (_: Exception) {
            isoDate
        }
    }

    fun formatTime(timestamp: Timestamp?): String {
        if (timestamp == null) return "--:--"
        return timeFormat.format(timestamp.toDate())
    }

    fun formatDateTime(timestamp: Timestamp?): String {
        if (timestamp == null) return "Pendiente"
        return fullDateTimeFormat.format(timestamp.toDate())
    }

    fun matchesTimeSlot(
        checkInAt: Timestamp?,
        createdAt: Timestamp?,
        expectedTime: String,
        filter: TimeSlotFilter
    ): Boolean {
        if (filter == TimeSlotFilter.ALL) return true
        val hour = extractHour(checkInAt, createdAt, expectedTime) ?: return false
        return when (filter) {
            TimeSlotFilter.ALL -> true
            TimeSlotFilter.MORNING -> hour in 5..11
            TimeSlotFilter.AFTERNOON -> hour in 12..17
            TimeSlotFilter.NIGHT -> hour >= 18 || hour < 5
        }
    }

    private fun extractHour(
        checkInAt: Timestamp?,
        createdAt: Timestamp?,
        expectedTime: String
    ): Int? {
        if (checkInAt != null) {
            val cal = Calendar.getInstance().apply { time = checkInAt.toDate() }
            return cal.get(Calendar.HOUR_OF_DAY)
        }
        val expectedHour = Regex("""^(\d{1,2})[:h]\d{0,2}""").find(expectedTime.trim())
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..23 }
        if (expectedHour != null) {
            return expectedHour
        }
        if (createdAt != null) {
            val cal = Calendar.getInstance().apply { time = createdAt.toDate() }
            return cal.get(Calendar.HOUR_OF_DAY)
        }
        return null
    }
}
