package com.example.notifications

import android.content.Context
import android.util.Log
import com.example.data.AccessRepository
import com.example.model.UserRole
import com.google.firebase.Firebase
import com.google.firebase.messaging.messaging
import kotlinx.coroutines.tasks.await

object FcmTokenManager {
    private const val TAG = "FcmTokenManager"

    fun adminTopicForUnit(unitId: String): String {
        val clean = sanitizeTopicUnit(unitId)
        return "admin_ingresos_$clean"
    }

    fun operatorTopicForUnit(unitId: String): String {
        val clean = sanitizeTopicUnit(unitId)
        return "operador_ingresos_$clean"
    }

    private fun sanitizeTopicUnit(unitId: String): String {
        return unitId.trim()
            .replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            .ifBlank { "UNIDAD_PRINCIPAL" }
    }

    suspend fun registerStaffPushToken(
        context: Context,
        repository: AccessRepository,
        uid: String,
        unitId: String,
        role: UserRole
    ) {
        if (role == UserRole.PENDING || role == UserRole.DIAMANTE_ALMA) {
            unregisterAllPushTopics(unitId)
            return
        }
        NotificationHelper.ensureChannel(context)
        try {
            val token = Firebase.messaging.token.await()
            if (token.isNotBlank()) {
                repository.saveFcmToken(uid = uid, unitId = unitId, token = token)
            }
            when (role) {
                UserRole.ADMIN -> {
                    Firebase.messaging.subscribeToTopic(adminTopicForUnit(unitId)).await()
                    Firebase.messaging.unsubscribeFromTopic(operatorTopicForUnit(unitId)).await()
                }
                UserRole.OPERATOR -> {
                    Firebase.messaging.subscribeToTopic(operatorTopicForUnit(unitId)).await()
                    Firebase.messaging.unsubscribeFromTopic(adminTopicForUnit(unitId)).await()
                }
                UserRole.PENDING, UserRole.DIAMANTE_ALMA -> Unit
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo sincronizar token FCM: ${e.message}")
        }
    }

    suspend fun unregisterAllPushTopics(unitId: String) {
        try {
            Firebase.messaging.unsubscribeFromTopic(adminTopicForUnit(unitId)).await()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo desuscribir tópico admin FCM: ${e.message}")
        }
        try {
            Firebase.messaging.unsubscribeFromTopic(operatorTopicForUnit(unitId)).await()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo desuscribir tópico operador FCM: ${e.message}")
        }
    }

    suspend fun clearPushRegistrationOnSignOut(
        repository: AccessRepository,
        uid: String,
        unitId: String
    ) {
        unregisterAllPushTopics(unitId)
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
