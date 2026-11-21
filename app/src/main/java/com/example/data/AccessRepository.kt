package com.example.data

import android.content.Context
import com.example.R
import com.example.model.AccessEntry
import com.example.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.model.DEFAULT_UNIT_ID
import com.example.model.EntryDraft
import com.example.model.EntryStatus
import com.example.model.UserProfile
import com.example.model.UserRole
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class AccessRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Debe iniciar sesión con Google para continuar.")
    }

    suspend fun ensureUserProfile(firebaseUser: FirebaseUser): Result<UserProfile> {
        val uid = firebaseUser.uid
        val docPath = "users/$uid"
        return try {
            val docRef = db.collection("users").document(uid)
            val snapshot = docRef.get().await()
            if (snapshot.exists()) {
                val existing = snapshot.toObject(
                    UserProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                if (existing != null) {
                    return Result.success(existing)
                }
            }

            val email = firebaseUser.email?.trim()?.take(200).orEmpty().ifBlank { "usuario@unidad.local" }
            val displayName = firebaseUser.displayName?.trim()?.take(100).orEmpty()
                .ifBlank { email.substringBefore('@').take(100) }
                .ifBlank { "Usuario" }
            val isBootstrapped = email.equals(BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true) &&
                firebaseUser.isEmailVerified
            val assignedRole = if (isBootstrapped) UserRole.ADMIN.name else UserRole.PENDING.name
            val assignedActive = isBootstrapped

            val payload = mapOf(
                "userId" to uid,
                "email" to email,
                "displayName" to displayName,
                "role" to assignedRole,
                "unitId" to DEFAULT_UNIT_ID,
                "active" to assignedActive,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            val created = docRef.get().await().toObject(
                UserProfile::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: UserProfile(
                id = uid,
                userId = uid,
                email = email,
                displayName = displayName,
                role = assignedRole,
                unitId = DEFAULT_UNIT_ID,
                active = assignedActive
            )
            Result.success(created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    fun observeUserProfile(uid: String): Flow<UserProfile?> = flow {
        require(uid.isNotBlank()) { "UID inválido" }
        val path = "users/$uid"
        emitAll(
            db.collection("users").document(uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObject(
                        UserProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    fun observeAllUsers(currentUid: String): Flow<List<UserProfile>> = flow {
        require(currentUid.isNotBlank())
        val path = "users"
        emitAll(
            db.collection("users")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        UserProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedWith(
                        compareBy<UserProfile> { it.parsedRole != UserRole.PENDING }
                            .thenBy { it.parsedRole != UserRole.ADMIN }
                            .thenBy { it.displayName.lowercase() }
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == currentUid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun updateUserRoleAndStatus(
        targetUserId: String,
        role: UserRole,
        active: Boolean
    ): Result<Unit> {
        val docPath = "users/$targetUserId"
        return try {
            requireUserId()
            require(targetUserId.isNotBlank()) { "ID de usuario vacío" }
            db.collection("users").document(targetUserId)
                .update(
                    mapOf(
                        "role" to role.name,
                        "active" to active,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun saveFcmToken(
        uid: String,
        unitId: String,
        token: String
    ): Result<Unit> {
        val cleanToken = token.trim()
        if (uid.isBlank() || cleanToken.length !in 10..512) {
            return Result.failure(IllegalArgumentException("Token FCM inválido"))
        }
        val tokenId = tokenDocId(cleanToken)
        val docPath = "users/$uid/fcmTokens/$tokenId"
        return try {
            val currentUid = requireUserId()
            check(currentUid == uid) { "Sesión no coincide con el propietario del token." }
            val safeUnitId = unitId.trim().take(64).ifBlank { DEFAULT_UNIT_ID }
            db.collection("users")
                .document(uid)
                .collection("fcmTokens")
                .document(tokenId)
                .set(
                    mapOf(
                        "userId" to uid,
                        "token" to cleanToken,
                        "platform" to "ANDROID",
                        "unitId" to safeUnitId,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteFcmToken(
        uid: String,
        token: String
    ): Result<Unit> {
        val cleanToken = token.trim()
        if (uid.isBlank() || cleanToken.isBlank()) return Result.success(Unit)
        val tokenId = tokenDocId(cleanToken)
        val docPath = "users/$uid/fcmTokens/$tokenId"
        return try {
            requireUserId()
            db.collection("users")
                .document(uid)
                .collection("fcmTokens")
                .document(tokenId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    private fun tokenDocId(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    fun observeEntries(
        currentUid: String,
        unitId: String,
        entryDateFilter: String?
    ): Flow<List<AccessEntry>> = flow {
        require(currentUid.isNotBlank())
        val path = "entries"
        val baseQuery = db.collection("entries").whereEqualTo("unitId", unitId)
        val query = if (entryDateFilter != null) {
            baseQuery.whereEqualTo("entryDate", entryDateFilter)
        } else {
            baseQuery
        }
        emitAll(
            query.snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        AccessEntry::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedWith(
                        compareBy<AccessEntry> { statusSortPriority(it.status) }
                            .thenByDescending { it.entryDate }
                            .thenByDescending { it.createdAt?.seconds ?: 0L }
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == currentUid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun createEntry(
        draft: EntryDraft,
        actorUid: String,
        actorName: String,
        unitId: String
    ): Result<Unit> {
        var docPath = "entries"
        return try {
            val uid = requireUserId()
            check(uid == actorUid) { "La sesión cambió; reintente." }
            val payload = buildCreatePayload(draft, uid, actorName, unitId)
            val docRef = db.collection("entries").document()
            docPath = docRef.path
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun createEntriesBatch(
        drafts: List<EntryDraft>,
        actorUid: String,
        actorName: String,
        unitId: String
    ): Result<Int> {
        val path = "entries"
        return try {
            val uid = requireUserId()
            check(uid == actorUid) { "La sesión cambió; reintente." }
            require(drafts.isNotEmpty()) { "No hay registros válidos para importar." }

            val chunks = drafts.chunked(400)
            var totalInserted = 0
            for (chunk in chunks) {
                val batch = db.batch()
                for (draft in chunk) {
                    val docRef = db.collection("entries").document()
                    val payload = buildCreatePayload(draft, uid, actorName, unitId)
                    batch.set(docRef, payload)
                    totalInserted++
                }
                batch.commit().await()
            }
            Result.success(totalInserted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateEntry(
        entryId: String,
        draft: EntryDraft,
        newStatus: EntryStatus,
        existingEntry: AccessEntry,
        actorUid: String,
        actorName: String
    ): Result<Unit> {
        val docPath = "entries/$entryId"
        return try {
            val uid = requireUserId()
            check(uid == actorUid) { "La sesión cambió; reintente." }
            require(entryId.isNotBlank()) { "ID de registro inválido." }
            val validated = validateDraft(draft)
            val safeActorName = actorName.trim().take(120).ifBlank { "Administrador" }

            val updates = mutableMapOf<String, Any>(
                "entryDate" to validated.entryDate,
                "rank" to validated.rank,
                "fullName" to validated.fullName,
                "documentNumber" to validated.documentNumber,
                "plate" to validated.plate,
                "destinationUnit" to validated.destinationUnit,
                "reason" to validated.reason,
                "visitorType" to validated.visitorType.name,
                "expectedTime" to validated.expectedTime,
                "source" to validated.source.name,
                "notes" to validated.notes,
                "status" to newStatus.name,
                "updatedAt" to FieldValue.serverTimestamp()
            )

            when (newStatus) {
                EntryStatus.PENDIENTE -> {
                    if (existingEntry.checkInAt != null || !existingEntry.checkInByUid.isNullOrBlank() || !existingEntry.checkInByName.isNullOrBlank()) {
                        updates["checkInAt"] = FieldValue.delete()
                        updates["checkInByUid"] = FieldValue.delete()
                        updates["checkInByName"] = FieldValue.delete()
                    }
                    if (existingEntry.checkOutAt != null || !existingEntry.checkOutByUid.isNullOrBlank() || !existingEntry.checkOutByName.isNullOrBlank()) {
                        updates["checkOutAt"] = FieldValue.delete()
                        updates["checkOutByUid"] = FieldValue.delete()
                        updates["checkOutByName"] = FieldValue.delete()
                    }
                }
                EntryStatus.INGRESO -> {
                    if (existingEntry.checkInAt == null || existingEntry.checkInByUid.isNullOrBlank() || existingEntry.checkInByName.isNullOrBlank()) {
                        updates["checkInAt"] = FieldValue.serverTimestamp()
                        updates["checkInByUid"] = uid
                        updates["checkInByName"] = safeActorName
                    }
                    if (existingEntry.checkOutAt != null || !existingEntry.checkOutByUid.isNullOrBlank() || !existingEntry.checkOutByName.isNullOrBlank()) {
                        updates["checkOutAt"] = FieldValue.delete()
                        updates["checkOutByUid"] = FieldValue.delete()
                        updates["checkOutByName"] = FieldValue.delete()
                    }
                }
                EntryStatus.SALIDA -> {
                    if (existingEntry.checkInAt == null || existingEntry.checkInByUid.isNullOrBlank() || existingEntry.checkInByName.isNullOrBlank()) {
                        updates["checkInAt"] = FieldValue.serverTimestamp()
                        updates["checkInByUid"] = uid
                        updates["checkInByName"] = safeActorName
                    }
                    if (existingEntry.status != EntryStatus.SALIDA.name || existingEntry.checkOutAt == null) {
                        updates["checkOutAt"] = FieldValue.serverTimestamp()
                        updates["checkOutByUid"] = uid
                        updates["checkOutByName"] = safeActorName
                    }
                }
            }

            db.collection("entries").document(entryId).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun markCheckInNow(
        entryId: String,
        actorUid: String,
        actorName: String
    ): Result<Unit> {
        val docPath = "entries/$entryId"
        return try {
            val uid = requireUserId()
            check(uid == actorUid) { "La sesión cambió; reintente." }
            require(entryId.isNotBlank()) { "ID de registro inválido." }
            val safeActorName = actorName.trim().take(120).ifBlank { "Operador" }

            db.collection("entries").document(entryId).update(
                mapOf(
                    "status" to EntryStatus.INGRESO.name,
                    "checkInAt" to FieldValue.serverTimestamp(),
                    "checkInByUid" to uid,
                    "checkInByName" to safeActorName,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun markCheckOutNow(
        entryId: String,
        actorUid: String,
        actorName: String
    ): Result<Unit> {
        val docPath = "entries/$entryId"
        return try {
            val uid = requireUserId()
            check(uid == actorUid) { "La sesión cambió; reintente." }
            require(entryId.isNotBlank()) { "ID de registro inválido." }
            val safeActorName = actorName.trim().take(120).ifBlank { "Operador" }

            db.collection("entries").document(entryId).update(
                mapOf(
                    "status" to EntryStatus.SALIDA.name,
                    "checkOutAt" to FieldValue.serverTimestamp(),
                    "checkOutByUid" to uid,
                    "checkOutByName" to safeActorName,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    private fun statusSortPriority(status: String): Int = when (status) {
        EntryStatus.PENDIENTE.name -> 0
        EntryStatus.INGRESO.name -> 1
        EntryStatus.SALIDA.name -> 2
        else -> 3
    }

    suspend fun deleteEntry(entryId: String): Result<Unit> {
        val docPath = "entries/$entryId"
        return try {
            requireUserId()
            require(entryId.isNotBlank()) { "ID de registro inválido." }
            db.collection("entries").document(entryId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    private fun validateDraft(draft: EntryDraft): EntryDraft {
        val cleanDate = draft.entryDate.trim()
        require(Regex("""^\d{4}-\d{2}-\d{2}$""").matches(cleanDate)) {
            "Fecha inválida (use YYYY-MM-DD)."
        }
        val cleanName = draft.fullName.trim()
        require(cleanName.length in 2..120) {
            "El nombre debe tener entre 2 y 120 caracteres."
        }
        val cleanDoc = draft.documentNumber.trim()
        require(cleanDoc.length in 1..40) {
            "La cédula debe tener entre 1 y 40 caracteres."
        }
        val cleanUnit = draft.destinationUnit.trim()
        require(cleanUnit.length in 1..80) {
            "La dependencia o unidad destino es obligatoria (máx. 80 caracteres)."
        }
        return draft.copy(
            entryDate = cleanDate,
            rank = draft.rank.trim().take(40),
            fullName = cleanName,
            documentNumber = cleanDoc,
            plate = draft.plate.trim().uppercase().take(20),
            destinationUnit = cleanUnit,
            reason = draft.reason.trim().take(160),
            expectedTime = draft.expectedTime.trim().take(20),
            notes = draft.notes.trim().take(300)
        )
    }

    private fun buildCreatePayload(
        draft: EntryDraft,
        uid: String,
        actorName: String,
        unitId: String
    ): Map<String, Any> {
        val validated = validateDraft(draft)
        val safeActorName = actorName.trim().take(120).ifBlank { "Administrador" }
        val safeUnitId = unitId.trim().take(64).ifBlank { DEFAULT_UNIT_ID }
        return mapOf(
            "unitId" to safeUnitId,
            "entryDate" to validated.entryDate,
            "rank" to validated.rank,
            "fullName" to validated.fullName,
            "documentNumber" to validated.documentNumber,
            "plate" to validated.plate,
            "destinationUnit" to validated.destinationUnit,
            "reason" to validated.reason,
            "visitorType" to validated.visitorType.name,
            "expectedTime" to validated.expectedTime,
            "source" to validated.source.name,
            "notes" to validated.notes,
            "status" to EntryStatus.PENDIENTE.name,
            "createdByUid" to uid,
            "createdByName" to safeActorName,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }
}
