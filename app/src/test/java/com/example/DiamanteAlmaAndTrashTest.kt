package com.example

import com.example.model.EntrySource
import com.example.model.EntryStatus
import com.example.model.TrashedEntry
import com.example.model.UserRole
import com.example.model.VisitorType
import com.example.ui.ControlAccesoViewModel
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiamanteAlmaAndTrashTest {

    @Test
    fun `UserRole parses DIAMANTE_ALMA with expected label`() {
        val role = UserRole.fromRaw("DIAMANTE_ALMA")
        assertEquals(UserRole.DIAMANTE_ALMA, role)
        assertEquals("Diamante Alma", role.label)
    }

    @Test
    fun `role permission matrix strictly isolates trash and user management to Admin`() {
        // ADMIN: full permissions
        assertTrue(UserRole.ADMIN.isActiveStaffRole)
        assertTrue(UserRole.ADMIN.canCreateEntries)
        assertTrue(UserRole.ADMIN.canSendToTrash)
        assertTrue(UserRole.ADMIN.canEditEntries)
        assertTrue(UserRole.ADMIN.canAccessTrash)
        assertTrue(UserRole.ADMIN.canManageUsers)
        assertTrue(UserRole.ADMIN.canDownloadExcel)

        // DIAMANTE_ALMA: register entries + soft-delete only; NO trash, NO users, NO edit, NO Excel
        assertTrue(UserRole.DIAMANTE_ALMA.isActiveStaffRole)
        assertTrue(UserRole.DIAMANTE_ALMA.canCreateEntries)
        assertTrue(UserRole.DIAMANTE_ALMA.canSendToTrash)
        assertFalse(UserRole.DIAMANTE_ALMA.canEditEntries)
        assertFalse(UserRole.DIAMANTE_ALMA.canAccessTrash)
        assertFalse(UserRole.DIAMANTE_ALMA.canManageUsers)
        assertFalse(UserRole.DIAMANTE_ALMA.canDownloadExcel)

        // OPERATOR: check-in/out only; NO create, NO trash, NO users
        assertTrue(UserRole.OPERATOR.isActiveStaffRole)
        assertFalse(UserRole.OPERATOR.canCreateEntries)
        assertFalse(UserRole.OPERATOR.canSendToTrash)
        assertFalse(UserRole.OPERATOR.canEditEntries)
        assertFalse(UserRole.OPERATOR.canAccessTrash)
        assertFalse(UserRole.OPERATOR.canManageUsers)
        assertFalse(UserRole.OPERATOR.canDownloadExcel)

        // PENDING: zero permissions
        assertFalse(UserRole.PENDING.isActiveStaffRole)
        assertFalse(UserRole.PENDING.canCreateEntries)
        assertFalse(UserRole.PENDING.canSendToTrash)
        assertFalse(UserRole.PENDING.canAccessTrash)
        assertFalse(UserRole.PENDING.canManageUsers)
    }

    @Test
    fun `TrashedEntry exposes deletion audit metadata and parsed enums`() {
        val deletedAt = Timestamp(1_760_000_000L, 0)
        val item = TrashedEntry(
            id = "entry-99",
            rank = "Subteniente",
            fullName = "Andrea Gómez",
            documentNumber = "10203040",
            plate = "XYZ-987",
            destinationUnit = "Guardia Principal",
            visitorType = VisitorType.PERSONAL.name,
            source = EntrySource.CORREO_INSTITUCIONAL.name,
            status = EntryStatus.INGRESO.name,
            deletedAt = deletedAt,
            deletedByUid = "uid-diamante-1",
            deletedByName = "Diamante Turno 1"
        )

        assertEquals("Subteniente Andrea Gómez", item.displayTitleWithRank)
        assertEquals(VisitorType.PERSONAL, item.parsedVisitorType)
        assertEquals(EntrySource.CORREO_INSTITUCIONAL, item.parsedSource)
        assertEquals(EntryStatus.INGRESO, item.parsedStatus)
        assertEquals("Diamante Turno 1", item.deletedByName)
        assertEquals("uid-diamante-1", item.deletedByUid)
        assertEquals(deletedAt, item.deletedAt)
    }

    @Test
    fun `shouldEmitEntryAlert does not send push alerts to DIAMANTE_ALMA`() {
        assertFalse(
            ControlAccesoViewModel.shouldEmitEntryAlert(
                role = UserRole.DIAMANTE_ALMA,
                isNewEntry = true,
                becameCheckedIn = true,
                becameCheckedOut = true
            )
        )
    }
}
