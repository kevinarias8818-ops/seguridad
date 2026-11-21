package com.example.util

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import com.example.model.AccessEntry
import com.example.model.EntryStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ReportPeriod(
    val title: String,
    val subtitle: String,
    val hoursWindow: Int,
    val daysWindow: Int,
    val fileSlug: String
) {
    LAST_24_HOURS(
        title = "Últimas 24 horas",
        subtitle = "Corte diario (ingresos, salidas e imágenes de las últimas 24h)",
        hoursWindow = 24,
        daysWindow = 1,
        fileSlug = "24h"
    ),
    LAST_7_DAYS(
        title = "Últimos 7 días (Semanal)",
        subtitle = "Consolidado semanal completo de los últimos 7 días",
        hoursWindow = 24 * 7,
        daysWindow = 7,
        fileSlug = "7dias"
    )
}

data class ExcelReportResult(
    val file: File,
    val shareUri: Uri,
    val fileName: String,
    val period: ReportPeriod,
    val entryCount: Int,
    val imageCount: Int
)

private data class EmbeddedPlateMedia(
    val mediaIndex: Int,
    val rowZeroBased: Int,
    val extension: String,
    val bytes: ByteArray
)

object ExcelReportGenerator {
    const val XLSX_MIME_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    fun filterEntriesForPeriod(
        entries: List<AccessEntry>,
        period: ReportPeriod,
        nowMillis: Long = System.currentTimeMillis()
    ): List<AccessEntry> {
        val cutoffMillis = nowMillis - period.hoursWindow * 3_600_000L
        val startDateIso = DateTimeUtils.isoDateDaysAgo(period.daysWindow - 1, nowMillis)
        val todayIso = DateTimeUtils.todayIso()

        return entries.filter { entry ->
            val checkInMs = entry.checkInAt?.toDate()?.time
            val checkOutMs = entry.checkOutAt?.toDate()?.time
            val createdMs = entry.createdAt?.toDate()?.time
            val updatedMs = entry.updatedAt?.toDate()?.time

            val hasActivityInWindow = listOfNotNull(checkOutMs, checkInMs, updatedMs, createdMs)
                .any { it >= cutoffMillis }

            val inDateScope = if (period == ReportPeriod.LAST_24_HOURS) {
                entry.entryDate == todayIso
            } else {
                entry.entryDate in startDateIso..todayIso
            }

            hasActivityInWindow || inDateScope
        }.sortedWith(
            compareByDescending<AccessEntry> { it.entryDate }
                .thenByDescending { it.checkOutAt?.seconds ?: it.checkInAt?.seconds ?: it.createdAt?.seconds ?: 0L }
        )
    }

    fun generateReportFile(
        context: Context,
        entries: List<AccessEntry>,
        period: ReportPeriod,
        generatedBy: String,
        unitId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): ExcelReportResult {
        val filtered = filterEntriesForPeriod(entries, period, nowMillis)
        val dir = File(context.cacheDir, "excel_reports").apply { mkdirs() }
        val fileName = "ControlAcceso_${period.fileSlug}_${DateTimeUtils.formatFileStamp(nowMillis)}.xlsx"
        val outFile = File(dir, fileName)

        val (xlsxBytes, embeddedCount) = generateWorkbookBytesWithStats(
            entries = filtered,
            period = period,
            generatedBy = generatedBy,
            unitId = unitId,
            nowMillis = nowMillis
        )

        FileOutputStream(outFile).use { fos ->
            fos.write(xlsxBytes)
            fos.flush()
        }

        val shareUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )

        return ExcelReportResult(
            file = outFile,
            shareUri = shareUri,
            fileName = fileName,
            period = period,
            entryCount = filtered.size,
            imageCount = embeddedCount
        )
    }

    fun generateWorkbookBytes(
        entries: List<AccessEntry>,
        period: ReportPeriod,
        generatedBy: String,
        unitId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): ByteArray = generateWorkbookBytesWithStats(
        entries = entries,
        period = period,
        generatedBy = generatedBy,
        unitId = unitId,
        nowMillis = nowMillis
    ).first

    private fun generateWorkbookBytesWithStats(
        entries: List<AccessEntry>,
        period: ReportPeriod,
        generatedBy: String,
        unitId: String,
        nowMillis: Long
    ): Pair<ByteArray, Int> {
        val mediaList = mutableListOf<EmbeddedPlateMedia>()
        var nextMediaIdx = 1

        val sheet1Xml = buildEntriesSheetXml(entries) { entry, rowZeroBased ->
            val decoded = decodePlateImageBytes(entry.plateImageData)
            if (decoded != null) {
                mediaList += EmbeddedPlateMedia(
                    mediaIndex = nextMediaIdx++,
                    rowZeroBased = rowZeroBased,
                    extension = decoded.first,
                    bytes = decoded.second
                )
                true
            } else {
                false
            }
        }

        val sheet2Xml = buildSummarySheetXml(
            entries = entries,
            period = period,
            generatedBy = generatedBy,
            unitId = unitId,
            imageCount = mediaList.size,
            nowMillis = nowMillis
        )

        val hasDrawings = mediaList.isNotEmpty()
        val extensions = mediaList.map { it.extension }.toSet()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            fun putText(path: String, xml: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(xml.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }

            putText("[Content_Types].xml", buildContentTypesXml(hasDrawings, extensions))
            putText("_rels/.rels", ROOT_RELS_XML)
            putText("xl/workbook.xml", WORKBOOK_XML)
            putText("xl/_rels/workbook.xml.rels", WORKBOOK_RELS_XML)
            putText("xl/styles.xml", STYLES_XML)
            putText("xl/worksheets/sheet1.xml", sheet1Xml)
            putText("xl/worksheets/sheet2.xml", sheet2Xml)

            if (hasDrawings) {
                putText("xl/worksheets/_rels/sheet1.xml.rels", SHEET1_RELS_XML)
                putText("xl/drawings/drawing1.xml", buildDrawingXml(mediaList))
                putText("xl/drawings/_rels/drawing1.xml.rels", buildDrawingRelsXml(mediaList))
                for (media in mediaList) {
                    zip.putNextEntry(ZipEntry("xl/media/plate_${media.mediaIndex}.${media.extension}"))
                    zip.write(media.bytes)
                    zip.closeEntry()
                }
            }
        }
        return baos.toByteArray() to mediaList.size
    }

    private fun decodePlateImageBytes(dataUrl: String?): Pair<String, ByteArray>? {
        if (dataUrl.isNullOrBlank()) return null
        val trimmed = dataUrl.trim()
        val ext = when {
            trimmed.startsWith("data:image/jpeg;base64,") -> "jpg"
            trimmed.startsWith("data:image/png;base64,") -> "png"
            trimmed.startsWith("data:image/webp;base64,") -> "webp"
            else -> return null
        }
        val commaIdx = trimmed.indexOf(',')
        if (commaIdx <= 0) return null
        val base64Part = trimmed.substring(commaIdx + 1)
        return runCatching {
            val raw = Base64.decode(base64Part, Base64.DEFAULT)
            if (raw.isEmpty()) null else ext to raw
        }.getOrNull()
    }

    private fun buildEntriesSheetXml(
        entries: List<AccessEntry>,
        registerMedia: (AccessEntry, Int) -> Boolean
    ): String {
        val headers = listOf(
            "#",
            "Estado",
            "Fecha Autorizada",
            "Hora Programada",
            "Grado / Cargo",
            "Nombre Completo",
            "Cédula / Documento",
            "Tipo de Ingreso",
            "Placa",
            "Imagen de Placa",
            "Archivo de Imagen",
            "Dependencia / Unidad Destino",
            "Motivo",
            "Observaciones",
            "Origen",
            "Fecha y Hora de Ingreso",
            "Ingreso Registrado Por",
            "Fecha y Hora de Salida",
            "Salida Registrada Por",
            "Creado Por",
            "Fecha Creación",
            "Última Actualización"
        )
        val colWidths = listOf(
            6, 16, 16, 15, 16, 26, 18, 16, 14, 24, 24, 26, 28, 30, 20, 20, 22, 20, 22, 22, 18, 18
        )

        val sb = StringBuilder(entries.size * 512 + 2048)
        sb.append(
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
  <cols>
"""
        )
        colWidths.forEachIndexed { idx, w ->
            val col1 = idx + 1
            sb.append("""    <col min="$col1" max="$col1" width="$w" customWidth="1"/>""").append('\n')
        }
        sb.append("  </cols>\n  <sheetData>\n")

        // Header Row (row 1)
        sb.append("""    <row r="1" ht="28" customHeight="1">""").append('\n')
        headers.forEachIndexed { colIdx, title ->
            sb.append(inlineCell(colRef(colIdx, 1), title, styleIdx = 1))
        }
        sb.append("    </row>\n")

        var anyMedia = false
        if (entries.isEmpty()) {
            sb.append("""    <row r="2" ht="26" customHeight="1">""").append('\n')
            sb.append(inlineCell("A2", "—", styleIdx = 0))
            sb.append(inlineCell("B2", "Sin registros en el período seleccionado", styleIdx = 0))
            sb.append("    </row>\n")
        } else {
            entries.forEachIndexed { index, entry ->
                val rowNum1 = index + 2
                val rowIdx0 = rowNum1 - 1
                val hasImage = registerMedia(entry, rowIdx0)
                if (hasImage) anyMedia = true
                val rowHeight = if (hasImage) 96 else 28

                val parsedStatus = entry.parsedStatus
                val statusStyle = when (parsedStatus) {
                    EntryStatus.PENDIENTE -> 3
                    EntryStatus.INGRESO -> 4
                    EntryStatus.SALIDA -> 5
                }
                val checkInText = DateTimeUtils.formatDateTimeOrDefault(entry.checkInAt, "Pendiente")
                val checkOutText = when (parsedStatus) {
                    EntryStatus.SALIDA -> DateTimeUtils.formatDateTimeOrDefault(entry.checkOutAt, "Registrada")
                    EntryStatus.INGRESO -> "En sitio"
                    EntryStatus.PENDIENTE -> "Pendiente"
                }

                val values = listOf(
                    (index + 1).toString(),
                    parsedStatus.label,
                    entry.entryDate,
                    entry.expectedTime.ifBlank { "Sin hora" },
                    entry.rank,
                    entry.fullName,
                    entry.documentNumber,
                    entry.parsedVisitorType.label,
                    entry.plate.ifBlank { "Peatonal" },
                    if (hasImage) "" else "Sin imagen adjunta",
                    if (hasImage) entry.plateImageName.ifBlank { "placa.jpg" } else "",
                    entry.destinationUnit,
                    entry.reason,
                    entry.notes,
                    entry.parsedSource.label,
                    checkInText,
                    entry.checkInByName.orEmpty(),
                    checkOutText,
                    entry.checkOutByName.orEmpty(),
                    entry.createdByName,
                    DateTimeUtils.formatDateTimeOrDefault(entry.createdAt, ""),
                    DateTimeUtils.formatDateTimeOrDefault(entry.updatedAt, "")
                )

                sb.append("""    <row r="$rowNum1" ht="$rowHeight" customHeight="1">""").append('\n')
                values.forEachIndexed { colIdx, text ->
                    val style = if (colIdx == 1) statusStyle else 0
                    sb.append(inlineCell(colRef(colIdx, rowNum1), text, styleIdx = style))
                }
                sb.append("    </row>\n")
            }
        }

        sb.append("  </sheetData>\n")
        if (anyMedia) {
            sb.append("""  <drawing r:id="rId1"/>""").append('\n')
        }
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun buildSummarySheetXml(
        entries: List<AccessEntry>,
        period: ReportPeriod,
        generatedBy: String,
        unitId: String,
        imageCount: Int,
        nowMillis: Long
    ): String {
        val pendingCount = entries.count { it.parsedStatus == EntryStatus.PENDIENTE }
        val inCount = entries.count { it.parsedStatus == EntryStatus.INGRESO }
        val outCount = entries.count { it.parsedStatus == EntryStatus.SALIDA }
        val startDate = DateTimeUtils.isoDateDaysAgo(period.daysWindow - 1, nowMillis)
        val endDate = DateTimeUtils.todayIso()
        val generatedAt = DateTimeUtils.formatDateTimeOrDefault(
            com.google.firebase.Timestamp(nowMillis / 1000L, 0),
            endDate
        )

        val rows = listOf(
            Triple("Control Acceso CENOP — Reporte de Ingresos y Salidas", "", 2),
            Triple("Período seleccionado", period.title, 6),
            Triple("Cobertura de fechas", "$startDate al $endDate", 0),
            Triple("Unidad", unitId, 0),
            Triple("Generado por (Administrador)", generatedBy, 0),
            Triple("Fecha de generación", generatedAt, 0),
            Triple("", "", 0),
            Triple("Indicador", "Cantidad", 1),
            Triple("Total de registros en el período", entries.size.toString(), 6),
            Triple("Pendientes por ingresar", pendingCount.toString(), 3),
            Triple("En sitio (Ya ingresó)", inCount.toString(), 4),
            Triple("Salidas completadas (Ya salió)", outCount.toString(), 5),
            Triple("Registros con imagen de placa adjunta", imageCount.toString(), 0)
        )

        val sb = StringBuilder(2048)
        sb.append(
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <cols>
    <col min="1" max="1" width="38" customWidth="1"/>
    <col min="2" max="2" width="36" customWidth="1"/>
  </cols>
  <sheetData>
"""
        )
        rows.forEachIndexed { idx, (label, value, style) ->
            val r = idx + 1
            val ht = if (r == 1) 30 else 24
            sb.append("""    <row r="$r" ht="$ht" customHeight="1">""").append('\n')
            sb.append(inlineCell("A$r", label, styleIdx = style))
            if (value.isNotEmpty() || r == 8) {
                sb.append(inlineCell("B$r", value, styleIdx = style))
            }
            sb.append("    </row>\n")
        }
        sb.append("  </sheetData>\n</worksheet>")
        return sb.toString()
    }

    private fun buildContentTypesXml(hasDrawings: Boolean, extensions: Set<String>): String {
        val extDefaults = buildString {
            if ("jpg" in extensions) {
                append("""  <Default Extension="jpg" ContentType="image/jpeg"/>""").append('\n')
            }
            if ("png" in extensions) {
                append("""  <Default Extension="png" ContentType="image/png"/>""").append('\n')
            }
            if ("webp" in extensions) {
                append("""  <Default Extension="webp" ContentType="image/webp"/>""").append('\n')
            }
        }
        val drawingOverride = if (hasDrawings) {
            """  <Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>"""
        } else {
            ""
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
$extDefaults  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
$drawingOverride
</Types>"""
    }

    private fun buildDrawingXml(mediaList: List<EmbeddedPlateMedia>): String {
        val anchors = mediaList.joinToString("\n") { media ->
            val r = media.rowZeroBased
            val id = media.mediaIndex + 1
            """  <xdr:twoCellAnchor editAs="oneCell">
    <xdr:from>
      <xdr:col>9</xdr:col>
      <xdr:colOff>38100</xdr:colOff>
      <xdr:row>$r</xdr:row>
      <xdr:rowOff>38100</xdr:rowOff>
    </xdr:from>
    <xdr:to>
      <xdr:col>9</xdr:col>
      <xdr:colOff>1480000</xdr:colOff>
      <xdr:row>$r</xdr:row>
      <xdr:rowOff>1140000</xdr:rowOff>
    </xdr:to>
    <xdr:pic>
      <xdr:nvPicPr>
        <xdr:cNvPr id="$id" name="Placa_${media.mediaIndex}"/>
        <xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr>
      </xdr:nvPicPr>
      <xdr:blipFill>
        <a:blip r:embed="rId${media.mediaIndex}"/>
        <a:stretch><a:fillRect/></a:stretch>
      </xdr:blipFill>
      <xdr:spPr>
        <a:xfrm><a:off x="0" y="0"/><a:ext cx="1441900" cy="1101900"/></a:xfrm>
        <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
      </xdr:spPr>
    </xdr:pic>
    <xdr:clientData/>
  </xdr:twoCellAnchor>"""
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing"
          xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
$anchors
</xdr:wsDr>"""
    }

    private fun buildDrawingRelsXml(mediaList: List<EmbeddedPlateMedia>): String {
        val rels = mediaList.joinToString("\n") { media ->
            """  <Relationship Id="rId${media.mediaIndex}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/plate_${media.mediaIndex}.${media.extension}"/>"""
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
$rels
</Relationships>"""
    }

    private fun inlineCell(cellRef: String, value: String, styleIdx: Int): String {
        val escaped = escapeXml(value)
        return """      <c r="$cellRef" t="inlineStr" s="$styleIdx"><is><t xml:space="preserve">$escaped</t></is></c>""" + "\n"
    }

    private fun colRef(colZeroBased: Int, rowOneBased: Int): String {
        var n = colZeroBased
        val colName = StringBuilder()
        while (n >= 0) {
            colName.append(('A' + (n % 26)))
            n = (n / 26) - 1
        }
        return "${colName.reverse()}$rowOneBased"
    }

    private fun escapeXml(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val code = ch.code
            val isValidXmlChar = code == 0x9 || code == 0xA || code == 0xD || (code >= 0x20 && code != 0xFFFE && code != 0xFFFF)
            if (!isValidXmlChar) continue
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private const val ROOT_RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private const val WORKBOOK_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Ingresos y Salidas" sheetId="1" r:id="rId1"/>
    <sheet name="Resumen" sheetId="2" r:id="rId2"/>
  </sheets>
</workbook>"""

    private const val WORKBOOK_RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private const val SHEET1_RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing1.xml"/>
</Relationships>"""

    private const val STYLES_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="6">
    <font><sz val="11"/><color rgb="FF0F172A"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
    <font><b/><sz val="14"/><color rgb="FF0F172A"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><color rgb="FF92400E"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><color rgb="FF065F46"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><color rgb="FF075985"/><name val="Calibri"/></font>
  </fonts>
  <fills count="8">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FF0F172A"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFFEF3C7"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFD1FAE5"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFE0F2FE"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFF1F5F9"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFFFFFFF"/></patternFill></fill>
  </fills>
  <borders count="2">
    <border><left/><right/><top/><bottom/><diagonal/></border>
    <border>
      <left style="thin"><color rgb="FFCBD5E1"/></left>
      <right style="thin"><color rgb="FFCBD5E1"/></right>
      <top style="thin"><color rgb="FFCBD5E1"/></top>
      <bottom style="thin"><color rgb="FFCBD5E1"/></bottom>
    </border>
  </borders>
  <cellStyleXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
  </cellStyleXfs>
  <cellXfs count="7">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment vertical="center"/></xf>
    <xf numFmtId="0" fontId="3" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="4" fillId="4" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="5" fillId="5" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="0" fillId="6" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
  </cellXfs>
</styleSheet>"""
}
