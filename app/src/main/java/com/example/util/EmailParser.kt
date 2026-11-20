package com.example.util

import com.example.model.EntryDraft
import com.example.model.EntrySource
import com.example.model.VisitorType

object EmailParser {

    val SAMPLE_INSTITUTIONAL_EMAIL = """
        Asunto: Autorización de ingresos - Turno del día

        Grado: Tte.
        Nombre: Carlos Alberto Mendoza
        Cédula: 1020458912
        Placa: KLM-482
        Dependencia: Comandancia - Bloque B
        Motivo: Reunión de coordinación operativa
        Tipo: Personal
        Hora programada: 08:30
        Observaciones: Ingresa por garita norte con equipo portátil
        ---
        Grado: Ing.
        Nombre: Laura Sofía Gómez
        Cédula: 52987412
        Placa: Peatonal
        Dependencia: Sala de Sistemas - Piso 3
        Motivo: Mantenimiento preventivo de red
        Tipo: Contratista
        Hora programada: 09:00
        Observaciones: Requiere acompañamiento de seguridad
        ---
        Sr. | Javier Ramírez | 79654321 | WXY-905 | Logística y Bodega 4 | Entrega de suministros | Proveedor | 10:15 | Descargue autorizado
    """.trimIndent()

    fun parseInstitutionalEmail(rawText: String, targetDateIso: String): List<EntryDraft> {
        val cleaned = rawText.replace("\r\n", "\n").trim()
        if (cleaned.isEmpty()) return emptyList()

        val results = mutableListOf<EntryDraft>()

        val blocks = cleaned
            .split(Regex("""\n\s*-{3,}\s*\n|\n{2,}"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val hasKeyValue = lines.any { line ->
                line.contains(":") && isRecognizedKey(line.substringBefore(":"))
            }

            if (hasKeyValue) {
                parseKeyValueBlock(lines, targetDateIso)?.let { results.add(it) }
            } else {
                for (line in lines) {
                    if (line.startsWith("Asunto:", ignoreCase = true) ||
                        line.startsWith("De:", ignoreCase = true) ||
                        line.startsWith("Para:", ignoreCase = true) ||
                        line.startsWith("Fecha:", ignoreCase = true)
                    ) continue
                    parseDelimitedLine(line, targetDateIso)?.let { results.add(it) }
                }
            }
        }
        return results
    }

    private fun isRecognizedKey(rawKey: String): Boolean {
        val key = rawKey.trim().lowercase()
        return key in setOf(
            "grado", "rango", "cargo", "tratamiento", "grado/nombre", "grado / nombre",
            "nombre", "nombres", "visitante", "personal", "persona", "empleado", "name",
            "cedula", "cédula", "cc", "documento", "doc", "dni", "id", "identificacion", "identificación",
            "placa", "vehiculo", "vehículo", "matricula", "matrícula",
            "dependencia", "unidad", "destino", "area", "área", "torre", "apartamento", "apto", "oficina", "casa",
            "motivo", "motivo/dependencia", "razon", "razón", "actividad",
            "tipo", "categoria", "categoría",
            "hora", "horario", "hora esperada", "hora prevista", "hora programada",
            "nota", "notas", "observacion", "observación", "observaciones"
        )
    }

    private fun parseKeyValueBlock(lines: List<String>, targetDateIso: String): EntryDraft? {
        var rank = ""
        var fullName = ""
        var document = ""
        var plate = ""
        var destination = ""
        var reason = ""
        var visitorType = VisitorType.VISITANTE
        var expectedTime = ""
        var notes = ""

        for (line in lines) {
            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0) continue
            val key = line.substring(0, colonIdx).trim().lowercase()
            val value = line.substring(colonIdx + 1).trim()
            if (value.isEmpty()) continue

            when (key) {
                "grado", "rango", "cargo", "tratamiento" ->
                    rank = value.take(40)
                "grado/nombre", "grado / nombre" -> {
                    val tokens = value.split(Regex("""\s+"""), limit = 2)
                    if (tokens.size == 2 && tokens[0].length <= 15) {
                        rank = tokens[0].take(40)
                        fullName = tokens[1].take(120)
                    } else {
                        fullName = value.take(120)
                    }
                }
                "nombre", "nombres", "visitante", "personal", "persona", "empleado", "name" ->
                    fullName = value.take(120)
                "cedula", "cédula", "cc", "documento", "doc", "dni", "id", "identificacion", "identificación" ->
                    document = value.take(40)
                "placa", "vehiculo", "vehículo", "matricula", "matrícula" ->
                    plate = normalizePlate(value)
                "dependencia", "unidad", "destino", "area", "área", "torre", "apartamento", "apto", "oficina", "casa" ->
                    destination = value.take(80)
                "motivo", "razon", "razón", "actividad" ->
                    reason = value.take(160)
                "motivo/dependencia" -> {
                    if (value.contains("/")) {
                        reason = value.substringBefore("/").trim().take(160)
                        destination = value.substringAfter("/").trim().take(80)
                    } else {
                        destination = value.take(80)
                    }
                }
                "tipo", "categoria", "categoría" ->
                    visitorType = VisitorType.fromFlexibleText(value)
                "hora", "horario", "hora esperada", "hora prevista", "hora programada" ->
                    expectedTime = value.take(20)
                "nota", "notas", "observacion", "observación", "observaciones" ->
                    notes = value.take(300)
            }
        }

        if (fullName.length < 2) return null
        return EntryDraft(
            entryDate = targetDateIso,
            rank = rank,
            fullName = fullName,
            documentNumber = document.ifBlank { "S/D" }.take(40),
            plate = plate,
            destinationUnit = destination.ifBlank { "Dependencia Principal" }.take(80),
            reason = reason,
            visitorType = visitorType,
            expectedTime = expectedTime,
            source = EntrySource.CORREO_INSTITUCIONAL,
            notes = notes
        )
    }

    private fun parseDelimitedLine(line: String, targetDateIso: String): EntryDraft? {
        val delimiter = when {
            line.contains('|') -> '|'
            line.contains(';') -> ';'
            line.contains('\t') -> '\t'
            line.count { it == ',' } >= 2 -> ','
            else -> return null
        }
        val parts = line.split(delimiter).map { it.trim() }
        if (parts.size < 2) return null
        if (parts[0].equals("nombre", ignoreCase = true) || parts[0].equals("grado", ignoreCase = true)) {
            return null
        }

        // Supports either:
        // 8+ cols: Grado | Nombre | Cédula | Placa | Dependencia | Motivo | Tipo | Hora | Observaciones
        // 6-7 cols: Nombre | Cédula | Placa | Dependencia | Tipo | Hora | Observaciones
        val hasRankColumn = parts.size >= 8
        val rank = if (hasRankColumn) parts[0].take(40) else ""
        val offset = if (hasRankColumn) 1 else 0

        val fullName = parts.getOrNull(offset)?.take(120).orEmpty()
        if (fullName.length < 2) return null

        val document = parts.getOrNull(offset + 1)?.take(40).orEmpty().ifBlank { "S/D" }
        val plate = normalizePlate(parts.getOrNull(offset + 2).orEmpty())
        val destination = parts.getOrNull(offset + 3)?.take(80).orEmpty().ifBlank { "Dependencia Principal" }

        val reason: String
        val typeIdx: Int
        if (hasRankColumn) {
            reason = parts.getOrNull(offset + 4)?.take(160).orEmpty()
            typeIdx = offset + 5
        } else {
            reason = ""
            typeIdx = offset + 4
        }

        val type = parts.getOrNull(typeIdx)?.let { VisitorType.fromFlexibleText(it) } ?: VisitorType.VISITANTE
        val expectedTime = parts.getOrNull(typeIdx + 1)?.take(20).orEmpty()
        val notes = parts.getOrNull(typeIdx + 2)?.take(300).orEmpty()

        return EntryDraft(
            entryDate = targetDateIso,
            rank = rank,
            fullName = fullName,
            documentNumber = document,
            plate = plate,
            destinationUnit = destination,
            reason = reason,
            visitorType = type,
            expectedTime = expectedTime,
            source = EntrySource.CORREO_INSTITUCIONAL,
            notes = notes
        )
    }

    private fun normalizePlate(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.equals("peatonal", ignoreCase = true) ||
            trimmed.equals("n/a", ignoreCase = true) ||
            trimmed.equals("ninguna", ignoreCase = true) ||
            trimmed == "-"
        ) {
            ""
        } else {
            trimmed.uppercase().take(20)
        }
    }
}
