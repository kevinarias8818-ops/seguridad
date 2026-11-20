package com.example.notifications

import android.content.Context
import android.util.Log
import com.example.data.AccessRepository
import com.google.firebase.Firebase
import com.google.firebase.messaging.messaging
import kotlinx.coroutines.tasks.await

object FcmTokenManager {
    private const val TAG = "FcmTokenManager"

    fun adminTopicForUnit(unitId: String): String {
        val clean = unitId.trim().replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            .ifBlank { "UNIDAD_PRINCIPAL" }
        return "admin_ingresos_$clean"
    }

    suspend fun registerAdminPushToken(
        context: Context,
        repository: AccessRepository,
        uid: String,
        unitId: String
    ) {
        NotificationHelper.ensureChannel(context)
        try {
            val token = Firebase.messaging.token.await()
            if (token.isNotBlank()) {
                repository.saveFcmToken(uid = uid, unitId = unitId, token = token)
            }
            Firebase.messaging.subscribeToTopic(adminTopicForUnit(unitId)).await()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo sincronizar token FCM: ${e.message}")
        }
    }

    suspend fun unregisterNonAdminTopic(unitId: String) {
        try {
            Firebase.messaging.unsubscribeFromTopic(adminTopicForUnit(unitId)).await()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo desuscribir tópico FCM: ${e.message}")
        }
    }

    suspend fun clearPushRegistrationOnSignOut(
        repository: AccessRepository,
        uid: String,
        unitId: String
    ) {
        try {
            Firebase.messaging.unsubscribeFromTopic(adminTopicForUnit(unitId)).await()
        } catch (e: Exception) {
            Log.w(TAG, "Error desuscribiendo tópico FCM al salir: ${e.message}")
        }
        try {
            val token = Firebase.messaging.token.await()
            if (token.isNotBlank()) {
                repository.deleteFcmToken(uid = uid, token = token)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error eliminando token FCM al salir: ${e.message}")
        }
    }
}
