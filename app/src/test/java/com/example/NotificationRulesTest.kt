package com.example

import com.example.model.UserRole
import com.example.notifications.FcmTokenManager
import com.example.ui.ControlAccesoViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRulesTest {

    @Test
    fun `shouldEmitEntryAlert notifies admins on entries and exits`() {
        assertTrue(
            ControlAccesoViewModel.shouldEmitEntryAlert(
                role = UserRole.ADMIN,
                isNewEntry = true,
                becameCheckedIn = false,
                becameCheckedOut = false
            )
        )
        assertTrue(
            ControlAccesoViewModel.shouldEmitEntryAlert(
                role = UserRole.ADMIN,
                isNewEntry = false,
                becameCheckedIn = true,
                becameCheckedOut = false
            )
        )
        assertTrue(
            ControlAccesoViewModel.shouldEmitEntryAlert(
                role = UserRole.ADMIN,
                isNewEntry = false,
                becameCheckedIn = false,
                becameCheckedOut = true
            )
        )
        assertFalse(
            ControlAccesoViewModel.shouldEmitEntryAlert(
                role = UserRole.ADMIN,
                isNewEntry = false,
                becameCheckedIn = false,
                becameCheckedOut = false
            )
        )
    }

    @Test
    fun `shouldEmitEntryAlert notifies operators only on new entries and ignores exits`() {
        assertTrue(
            ControlAccesoViewModel.shouldEmitEntryAlert(
                role = UserRole.OPERATOR,
                isNewEntry = true,
                becameCheckedIn = false,
                becameCheckedOut = false
            )
        )
        assertTrue(
            ControlAccesoViewModel.shouldEmitEntryAlert(
                role = UserRole.OPERATOR,
                isNewEntry = false,
                becameCheckedIn = true,
                becameCheckedOut = false
            )
        )
        assertFalse(
            ControlAccesoViewModel.shouldEmitEntryAlert(
                role = UserRole.OPERATOR,
                isNewEntry = false,
                becameCheckedIn = false,
                becameCheckedOut = true
            )
        )
    }

    @Test
    fun `shouldEmitEntryAlert never notifies pending accounts`() {
        assertFalse(
            ControlAccesoViewModel.shouldEmitEntryAlert(
                role = UserRole.PENDING,
                isNewEntry = true,
                becameCheckedIn = true,
                becameCheckedOut = true
            )
        )
    }

    @Test
    fun `fcm topic helpers separate admin and operator topics cleanly`() {
        assertEquals("admin_ingresos_UNIDAD_PRINCIPAL", FcmTokenManager.adminTopicForUnit("UNIDAD_PRINCIPAL"))
        assertEquals("operador_ingresos_UNIDAD_PRINCIPAL", FcmTokenManager.operatorTopicForUnit("UNIDAD_PRINCIPAL"))
        assertEquals("admin_ingresos_CENOP_1", FcmTokenManager.adminTopicForUnit("CENOP 1"))
        assertEquals("operador_ingresos_CENOP_1", FcmTokenManager.operatorTopicForUnit("CENOP 1"))
    }
}
