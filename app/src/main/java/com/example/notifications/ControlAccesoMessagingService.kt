package com.example.notifications

import android.util.Log
import com.example.data.AccessRepository
import com.example.model.DEFAULT_UNIT_ID
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ControlAccesoMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed")
        val uid = Firebase.auth.currentUser?.uid
        if (uid.isNullOrBlank() || token.isBlank()) return

        serviceScope.launch {
            val repository = AccessRepository(applicationContext)
            repository.saveFcmToken(
                uid = uid,
                unitId = DEFAULT_UNIT_ID,
                token = token
            )
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val title = message.notification?.title
            ?: data["title"]
            ?: "Nuevo ingreso en CENOP"
        val person = data["fullName"].orEmpty()
        val unit = data["destinationUnit"].orEmpty()
        val fallbackBody = listOf(person, unit).filter { it.isNotBlank() }.joinToString(" · ")
            .ifBlank { "Se registró una actualización en control de ingresos." }
        val body = message.notification?.body ?: data["body"] ?: fallbackBody
        val entryId = data["entryId"]

        NotificationHelper.showEntryAlert(
            context = applicationContext,
            title = title,
            body = body,
            entryId = entryId
        )
    }

    companion object {
        private const val TAG = "ControlAccesoFCM"
    }
}
