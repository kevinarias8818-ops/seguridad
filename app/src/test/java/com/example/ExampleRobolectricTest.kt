package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.UserProfile
import com.example.model.UserRole
import com.example.ui.ControlAccesoViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Control GUSEF", appName)
  }

  @Test
  fun `only active admin not simulating operator can delete other users`() {
    val admin = UserProfile(userId = "admin_1", role = UserRole.ADMIN.name, active = true)
    val operator = UserProfile(userId = "op_1", role = UserRole.OPERATOR.name, active = true)
    val diamante = UserProfile(userId = "diam_1", role = UserRole.DIAMANTE_ALMA.name, active = true)
    val pending = UserProfile(userId = "pend_1", role = UserRole.PENDING.name, active = false)

    assertTrue(
      ControlAccesoViewModel.canDeleteUser(
        actingProfile = admin,
        targetUser = operator,
        actingUserId = admin.userId,
        simulateOperatorMode = false
      )
    )
    assertFalse(
      ControlAccesoViewModel.canDeleteUser(
        actingProfile = admin,
        targetUser = admin,
        actingUserId = admin.userId,
        simulateOperatorMode = false
      )
    )
    assertFalse(
      ControlAccesoViewModel.canDeleteUser(
        actingProfile = admin,
        targetUser = operator,
        actingUserId = admin.userId,
        simulateOperatorMode = true
      )
    )
    assertFalse(
      ControlAccesoViewModel.canDeleteUser(
        actingProfile = diamante,
        targetUser = operator,
        actingUserId = diamante.userId,
        simulateOperatorMode = false
      )
    )
    assertFalse(
      ControlAccesoViewModel.canDeleteUser(
        actingProfile = operator,
        targetUser = pending,
        actingUserId = operator.userId,
        simulateOperatorMode = false
      )
    )
  }
}
