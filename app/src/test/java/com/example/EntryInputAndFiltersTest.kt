package com.example

import com.example.model.AccessEntry
import com.example.model.EntryStatus
import com.example.ui.EntryStatusFilter
import com.example.util.EntryInputUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryInputAndFiltersTest {

    @Test
    fun `EntryStatusFilter provides direct En sitio filter right after Todos`() {
        val values = EntryStatusFilter.entries
        assertEquals(EntryStatusFilter.ALL, values[0])
        assertEquals(EntryStatusFilter.CHECKED_IN, values[1])
        assertEquals("En sitio", EntryStatusFilter.CHECKED_IN.label)
    }

    @Test
    fun `formatDocumentNumberInput allows only digits up to 20 chars`() {
        assertEquals("1020458912", EntryInputUtils.formatDocumentNumberInput("CC 1020.458-912abc"))
        assertEquals("", EntryInputUtils.formatDocumentNumberInput("abc-xyz"))
        assertEquals("12345678901234567890", EntryInputUtils.formatDocumentNumberInput("12345678901234567890123"))
    }

    @Test
    fun `formatPlateInput forces uppercase alphanumeric only`() {
        assertEquals("ABC123", EntryInputUtils.formatPlateInput("abc-123"))
        assertEquals("KLM48D", EntryInputUtils.formatPlateInput("klm 48d!"))
        assertEquals("ABCDEFGHIJ", EntryInputUtils.formatPlateInput("abcdefghijkl"))
    }

    @Test
    fun `formatEntryTimeDigits auto-inserts colon and supports clean backspace`() {
        assertEquals("0", EntryInputUtils.formatEntryTimeDigits("", "0"))
        assertEquals("08:", EntryInputUtils.formatEntryTimeDigits("0", "08"))
        assertEquals("08:3", EntryInputUtils.formatEntryTimeDigits("08:", "08:3"))
        assertEquals("08:30", EntryInputUtils.formatEntryTimeDigits("08:3", "08:30"))
        assertEquals("14:30", EntryInputUtils.formatEntryTimeDigits("", "14a3b0"))

        // Backspacing across the auto-inserted ':' should remove the 2nd hour digit cleanly
        assertEquals("08:", EntryInputUtils.formatEntryTimeDigits("08:3", "08:"))
        assertEquals("0", EntryInputUtils.formatEntryTimeDigits("08:", "08"))
        assertEquals("", EntryInputUtils.formatEntryTimeDigits("0", ""))
    }

    @Test
    fun `isValidEntryTime accepts empty or 24h HH_MM and rejects invalid times`() {
        assertTrue(EntryInputUtils.isValidEntryTime(""))
        assertTrue(EntryInputUtils.isValidEntryTime("00:00"))
        assertTrue(EntryInputUtils.isValidEntryTime("08:30"))
        assertTrue(EntryInputUtils.isValidEntryTime("23:59"))

        assertFalse(EntryInputUtils.isValidEntryTime("08:3"))
        assertFalse(EntryInputUtils.isValidEntryTime("24:00"))
        assertFalse(EntryInputUtils.isValidEntryTime("12:60"))
    }

    @Test
    fun `findActiveOnSiteDuplicates detects active INGRESO by document or plate and ignores SALIDA`() {
        val activePerson = AccessEntry(
            id = "e1",
            fullName = "Carlos Mendoza",
            documentNumber = "1020458912",
            plate = "KLM-482",
            destinationUnit = "Comandancia",
            status = EntryStatus.INGRESO.name
        )
        val exitedPerson = AccessEntry(
            id = "e2",
            fullName = "Laura Gómez",
            documentNumber = "52987412",
            plate = "XYZ999",
            destinationUnit = "Logística",
            status = EntryStatus.SALIDA.name
        )
        val pendingPerson = AccessEntry(
            id = "e3",
            fullName = "Javier Ramírez",
            documentNumber = "79654321",
            plate = "WXY905",
            destinationUnit = "Bodega",
            status = EntryStatus.PENDIENTE.name
        )
        val entries = listOf(activePerson, exitedPerson, pendingPerson)

        // Match active INGRESO by document
        val docMatches = EntryInputUtils.findActiveOnSiteDuplicates(entries, "1020458912", "")
        assertEquals(1, docMatches.size)
        assertTrue(docMatches[0].matchedByDocument)
        assertFalse(docMatches[0].matchedByPlate)

        // Match active INGRESO by plate even with formatting difference (KLM482 vs KLM-482)
        val plateMatches = EntryInputUtils.findActiveOnSiteDuplicates(entries, "999999", "klm482")
        assertEquals(1, plateMatches.size)
        assertFalse(plateMatches[0].matchedByDocument)
        assertTrue(plateMatches[0].matchedByPlate)

        // Ignore SALIDA and PENDIENTE
        val noMatches = EntryInputUtils.findActiveOnSiteDuplicates(entries, "52987412", "WXY905")
        assertTrue(noMatches.isEmpty())

        // Ignore self when editing e1
        val selfExcluded = EntryInputUtils.findActiveOnSiteDuplicates(entries, "1020458912", "KLM482", excludeEntryId = "e1")
        assertTrue(selfExcluded.isEmpty())
    }
}
