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
        val hour = extractHour(checkInAt ?: createdAt, expectedTime) ?: return false
        return when (filter) {
            TimeSlotFilter.ALL -> true
            TimeSlotFilter.MORNING -> hour in 5..11
            TimeSlotFilter.AFTERNOON -> hour in 12..17
            TimeSlotFilter.NIGHT -> hour >= 18 || hour < 5
        }
    }

    private fun extractHour(timestamp: Timestamp?, expectedTime: String): Int? {
        val expectedHour = Regex("""^(\d{1,2}):\d{2}""").find(expectedTime.trim())
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (timestamp != null) {
            val cal = Calendar.getInstance().apply { time = timestamp.toDate() }
            return cal.get(Calendar.HOUR_OF_DAY)
        }
        return expectedHour?.takeIf { it in 0..23 }
    }
}
