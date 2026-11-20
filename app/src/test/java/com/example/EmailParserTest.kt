package com.example

import com.example.model.EntrySource
import com.example.model.VisitorType
import com.example.util.EmailParser
import org.junit.Assert.assertEquals
import org.junit.Test

class EmailParserTest {

    @Test
    fun `parseInstitutionalEmail parses Grado, Cédula, Dependencia, Motivo, Hora programada and Observaciones`() {
        val parsed = EmailParser.parseInstitutionalEmail(
            rawText = EmailParser.SAMPLE_INSTITUTIONAL_EMAIL,
            targetDateIso = "2026-11-18"
        )

        assertEquals(3, parsed.size)

        assertEquals("Tte.", parsed[0].rank)
        assertEquals("Carlos Alberto Mendoza", parsed[0].fullName)
        assertEquals("1020458912", parsed[0].documentNumber)
        assertEquals("KLM-482", parsed[0].plate)
        assertEquals("Comandancia - Bloque B", parsed[0].destinationUnit)
        assertEquals("Reunión de coordinación operativa", parsed[0].reason)
        assertEquals("08:30", parsed[0].expectedTime)
        assertEquals("Ingresa por garita norte con equipo portátil", parsed[0].notes)
        assertEquals(VisitorType.PERSONAL, parsed[0].visitorType)
        assertEquals(EntrySource.CORREO_INSTITUCIONAL, parsed[0].source)

        assertEquals("Ing.", parsed[1].rank)
        assertEquals("Laura Sofía Gómez", parsed[1].fullName)
        assertEquals("", parsed[1].plate)
        assertEquals("Mantenimiento preventivo de red", parsed[1].reason)
        assertEquals(VisitorType.CONTRATISTA, parsed[1].visitorType)

        assertEquals("Sr.", parsed[2].rank)
        assertEquals("Javier Ramírez", parsed[2].fullName)
        assertEquals("WXY-905", parsed[2].plate)
        assertEquals("Logística y Bodega 4", parsed[2].destinationUnit)
        assertEquals("Entrega de suministros", parsed[2].reason)
        assertEquals("10:15", parsed[2].expectedTime)
        assertEquals("Descargue autorizado", parsed[2].notes)
        assertEquals(VisitorType.PROVEEDOR, parsed[2].visitorType)
    }
}
