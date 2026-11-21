package com.example

import com.example.model.AccessEntry
import com.example.model.EntrySource
import com.example.model.EntryStatus
import com.example.model.VisitorType
import com.example.util.DateTimeUtils
import com.example.util.ExcelReportGenerator
import com.example.util.ReportPeriod
import com.google.firebase.Timestamp
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.zip.ZipInputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExcelReportGeneratorTest {

    private val sampleJpegBase64 =
        "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA="

    @Test
    fun `filterEntriesForPeriod separates 24h and 7d windows accurately`() {
        val nowMs = System.currentTimeMillis()
        val threeHoursAgo = Timestamp(Date(nowMs - 3 * 3_600_000L))
        val fourDaysAgo = Timestamp(Date(nowMs - 4 * 86_400_000L))
        val twelveDaysAgo = Timestamp(Date(nowMs - 12 * 86_400_000L))

        val eToday = AccessEntry(
            id = "e1",
            entryDate = DateTimeUtils.todayIso(),
            fullName = "Ana Torres",
            documentNumber = "1010",
            destinationUnit = "Comandancia",
            status = EntryStatus.INGRESO.name,
            checkInAt = threeHoursAgo,
            createdAt = threeHoursAgo
        )
        val eFourDays = AccessEntry(
            id = "e2",
            entryDate = DateTimeUtils.isoDateDaysAgo(4, nowMs),
            fullName = "Luis Pérez",
            documentNumber = "2020",
            destinationUnit = "Logística",
            status = EntryStatus.SALIDA.name,
            checkInAt = fourDaysAgo,
            checkOutAt = fourDaysAgo,
            createdAt = fourDaysAgo
        )
        val eOld = AccessEntry(
            id = "e3",
            entryDate = DateTimeUtils.isoDateDaysAgo(12, nowMs),
            fullName = "Marco Díaz",
            documentNumber = "3030",
            destinationUnit = "Archivo",
            status = EntryStatus.SALIDA.name,
            checkInAt = twelveDaysAgo,
            checkOutAt = twelveDaysAgo,
            createdAt = twelveDaysAgo
        )

        val list24h = ExcelReportGenerator.filterEntriesForPeriod(
            listOf(eToday, eFourDays, eOld),
            ReportPeriod.LAST_24_HOURS,
            nowMs
        )
        assertTrue(list24h.any { it.id == "e1" })
        assertFalse(list24h.any { it.id == "e2" })
        assertFalse(list24h.any { it.id == "e3" })

        val list7d = ExcelReportGenerator.filterEntriesForPeriod(
            listOf(eToday, eFourDays, eOld),
            ReportPeriod.LAST_7_DAYS,
            nowMs
        )
        assertTrue(list7d.any { it.id == "e1" })
        assertTrue(list7d.any { it.id == "e2" })
        assertFalse(list7d.any { it.id == "e3" })
    }

    @Test
    fun `generateWorkbookBytes creates valid xlsx with entries checkout details and embedded plate image`() {
        val nowMs = System.currentTimeMillis()
        val tsIn = Timestamp(Date(nowMs - 2 * 3_600_000L))
        val tsOut = Timestamp(Date(nowMs - 30 * 60_000L))

        val entry = AccessEntry(
            id = "e_img",
            unitId = "UNIDAD_PRINCIPAL",
            entryDate = DateTimeUtils.todayIso(),
            rank = "Tte.",
            fullName = "Carlos Mendoza",
            documentNumber = "1020458912",
            plate = "ABC-123",
            plateImageName = "placa_abc123.jpg",
            plateImageData = sampleJpegBase64,
            destinationUnit = "Comandancia",
            reason = "Reunión operativa & revisión",
            visitorType = VisitorType.PERSONAL.name,
            expectedTime = "08:00",
            source = EntrySource.MANUAL.name,
            notes = "Ingreso con equipo",
            status = EntryStatus.SALIDA.name,
            checkInAt = tsIn,
            checkInByUid = "op1",
            checkInByName = "Op Garita 1",
            checkOutAt = tsOut,
            checkOutByUid = "op2",
            checkOutByName = "Op Garita 2",
            createdByUid = "admin1",
            createdByName = "Admin CENOP",
            createdAt = tsIn,
            updatedAt = tsOut
        )

        val bytes = ExcelReportGenerator.generateWorkbookBytes(
            entries = listOf(entry),
            period = ReportPeriod.LAST_24_HOURS,
            generatedBy = "Kevin Arias",
            unitId = "UNIDAD_PRINCIPAL",
            nowMillis = nowMs
        )

        val entriesMap = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val zipEntry = zis.nextEntry ?: break
                entriesMap[zipEntry.name] = zis.readBytes()
            }
        }

        assertTrue(entriesMap.containsKey("[Content_Types].xml"))
        assertTrue(entriesMap.containsKey("xl/workbook.xml"))
        assertTrue(entriesMap.containsKey("xl/worksheets/sheet1.xml"))
        assertTrue(entriesMap.containsKey("xl/worksheets/sheet2.xml"))
        assertTrue(entriesMap.containsKey("xl/styles.xml"))
        assertTrue(entriesMap.containsKey("xl/drawings/drawing1.xml"))
        assertTrue(entriesMap.containsKey("xl/drawings/_rels/drawing1.xml.rels"))
        assertTrue(entriesMap.containsKey("xl/media/plate_1.jpg"))

        val sheet1Xml = String(entriesMap.getValue("xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8)
        assertTrue(sheet1Xml.contains("Carlos Mendoza"))
        assertTrue(sheet1Xml.contains("ABC-123"))
        assertTrue(sheet1Xml.contains("placa_abc123.jpg"))
        assertTrue(sheet1Xml.contains("Op Garita 1"))
        assertTrue(sheet1Xml.contains("Op Garita 2"))
        assertTrue(sheet1Xml.contains("Reunión operativa &amp; revisión"))
        assertTrue(sheet1Xml.contains("<drawing r:id=\"rId1\"/>"))

        val sheet2Xml = String(entriesMap.getValue("xl/worksheets/sheet2.xml"), StandardCharsets.UTF_8)
        assertTrue(sheet2Xml.contains("Últimas 24 horas"))
        assertTrue(sheet2Xml.contains("Kevin Arias"))
    }

    @Test
    fun `isSafePlateImageDataUrl accepts raster images and rejects svg or malformed payloads`() {
        assertTrue(com.example.data.AccessRepository.isSafePlateImageDataUrl(sampleJpegBase64))
        assertTrue(
            com.example.data.AccessRepository.isSafePlateImageDataUrl(
                "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
            )
        )
        assertTrue(
            com.example.data.AccessRepository.isSafePlateImageDataUrl(
                "data:image/webp;base64,UklGRh4AAABXRUJQVlA4TBEAAAAvAAAAAAfQ//73v/+BiOh/AAA="
            )
        )

        assertFalse(
            com.example.data.AccessRepository.isSafePlateImageDataUrl(
                "data:image/svg+xml;base64,PHN2ZyBvbmxvYWQ9YWxlcnQoMSk+PC9zdmc+"
            )
        )
        assertFalse(
            com.example.data.AccessRepository.isSafePlateImageDataUrl(
                "javascript:alert(1)//data:image/jpeg;base64,AAAA"
            )
        )
        assertFalse(com.example.data.AccessRepository.isSafePlateImageDataUrl("data:image/jpeg;base64,"))
    }

    @Test
    fun `generateWorkbookBytes ignores svg data urls so untrusted xml cannot embed`() {
        val nowMs = System.currentTimeMillis()
        val entry = AccessEntry(
            id = "e_svg",
            entryDate = DateTimeUtils.todayIso(),
            fullName = "Prueba Seguridad",
            documentNumber = "9999",
            destinationUnit = "Guardia",
            plateImageName = "payload.svg",
            plateImageData = "data:image/svg+xml;base64,PHN2ZyBvbmxvYWQ9YWxlcnQoMSk+PC9zdmc+",
            status = EntryStatus.PENDIENTE.name,
            createdAt = Timestamp(Date(nowMs))
        )

        val bytes = ExcelReportGenerator.generateWorkbookBytes(
            entries = listOf(entry),
            period = ReportPeriod.LAST_24_HOURS,
            generatedBy = "Admin",
            unitId = "UNIDAD_PRINCIPAL",
            nowMillis = nowMs
        )

        val entriesMap = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val zipEntry = zis.nextEntry ?: break
                entriesMap[zipEntry.name] = zis.readBytes()
            }
        }

        assertFalse(entriesMap.containsKey("xl/media/plate_1.jpg"))
        assertFalse(entriesMap.containsKey("xl/drawings/drawing1.xml"))
    }
}
