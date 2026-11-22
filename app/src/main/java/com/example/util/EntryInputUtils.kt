package com.example.util

import com.example.model.AccessEntry
import com.example.model.EntryStatus

data class OnSiteDuplicateMatch(
    val entry: AccessEntry,
    val matchedByDocument: Boolean,
    val matchedByPlate: Boolean
)

object EntryInputUtils {

    private val validTimeRegex = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")

    /**
     * Cédula / Documento: permite únicamente dígitos numéricos (0-9).
     */
    fun formatDocumentNumberInput(raw: String): String =
        raw.filter(Char::isDigit).take(20)

    /**
     * Placa: fuerza mayúsculas automáticas y conserva solo caracteres alfanuméricos (ej. ABC123).
     */
    fun formatPlateInput(raw: String): String =
        raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(10)

    /**
     * Hora de ingreso (24h HH:MM): acepta solo dígitos y formatea automáticamente con ':' (ej. 0830 -> 08:30),
     * permitiendo borrar con retroceso sin quedar atrapado en ':'.
     */
    fun formatEntryTimeDigits(previous: String, rawNext: String): String {
        val digits = rawNext.filter(Char::isDigit).take(4)
        val isDeleting = rawNext.length < previous.length
        return when {
            digits.isEmpty() -> ""
            digits.length == 1 -> digits
            digits.length == 2 -> {
                if (isDeleting && previous.endsWith(":")) {
                    digits.take(1)
                } else {
                    "$digits:"
                }
            }
            else -> "${digits.take(2)}:${digits.drop(2)}"
        }
    }

    /**
     * Normaliza un valor previo (por ejemplo al editar un registro importado como "8:30" o "08h30").
     */
    fun normalizeExistingTime(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val digits = trimmed.filter(Char::isDigit)
        return when (digits.length) {
            3 -> "0${digits.take(1)}:${digits.drop(1)}"
            4 -> "${digits.take(2)}:${digits.drop(2)}"
            else -> trimmed.take(5)
        }
    }

    fun isValidEntryTime(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return true
        return validTimeRegex.matches(trimmed)
    }

    fun normalizeDocumentForMatch(raw: String): String {
        val digits = raw.filter(Char::isDigit)
        return if (digits.isNotEmpty()) digits else raw.trim().uppercase()
    }

    fun normalizePlateForMatch(raw: String): String =
        raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun findActiveOnSiteDuplicates(
        activeEntries: List<AccessEntry>,
        documentNumber: String,
        plate: String,
        excludeEntryId: String? = null
    ): List<OnSiteDuplicateMatch> {
        val cleanDoc = normalizeDocumentForMatch(documentNumber)
        val cleanPlate = normalizePlateForMatch(plate)
        if (cleanDoc.isEmpty() && cleanPlate.isEmpty()) return emptyList()

        return activeEntries.mapNotNull { entry ->
            if (entry.parsedStatus != EntryStatus.INGRESO) return@mapNotNull null
            if (!excludeEntryId.isNullOrBlank() && entry.id == excludeEntryId) return@mapNotNull null

            val entryDoc = normalizeDocumentForMatch(entry.documentNumber)
            val entryPlate = normalizePlateForMatch(entry.plate)

            val docMatches = cleanDoc.isNotEmpty() && entryDoc == cleanDoc
            val plateMatches = cleanPlate.isNotEmpty() && entryPlate == cleanPlate

            if (docMatches || plateMatches) {
                OnSiteDuplicateMatch(
                    entry = entry,
                    matchedByDocument = docMatches,
                    matchedByPlate = plateMatches
                )
            } else {
                null
            }
        }
    }
}
