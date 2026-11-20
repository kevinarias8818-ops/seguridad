package com.example.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

const val DEFAULT_UNIT_ID = "UNIDAD_PRINCIPAL"
const val BOOTSTRAPPED_ADMIN_EMAIL = "kevinarias.8818@gmail.com"

enum class UserRole(val label: String) {
    PENDING("Pendiente de autorización"),
    ADMIN("Administrador"),
    OPERATOR("Operador");

    companion object {
        fun fromRaw(raw: String?): UserRole =
            entries.firstOrNull { it.name == raw } ?: PENDING
    }
}

enum class VisitorType(val label: String) {
    PERSONAL("Personal"),
    VISITANTE("Visitante"),
    PROVEEDOR("Proveedor"),
    CONTRATISTA("Contratista");

    companion object {
        fun fromRaw(raw: String?): VisitorType =
            entries.firstOrNull { it.name == raw } ?: VISITANTE

        fun fromFlexibleText(raw: String): VisitorType {
            val normalized = raw.trim().lowercase()
            return when {
                normalized.contains("pers") || normalized.contains("empl") || normalized.contains("func") -> PERSONAL
                normalized.contains("prov") || normalized.contains("domic") || normalized.contains("mensaj") -> PROVEEDOR
                normalized.contains("contr") || normalized.contains("tecn") || normalized.contains("obra") -> CONTRATISTA
                else -> VISITANTE
            }
        }
    }
}

enum class EntrySource(val label: String) {
    MANUAL("Registro manual"),
    CORREO_INSTITUCIONAL("Correo institucional");

    companion object {
        fun fromRaw(raw: String?): EntrySource =
            entries.firstOrNull { it.name == raw } ?: MANUAL
    }
}

enum class EntryStatus(val label: String) {
    PENDIENTE("Pendiente"),
    INGRESO("Ya ingresó");

    companion object {
        fun fromRaw(raw: String?): EntryStatus =
            entries.firstOrNull { it.name == raw } ?: PENDIENTE
    }
}

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String = "",
    val displayName: String = "",
    val role: String = UserRole.PENDING.name,
    val unitId: String = DEFAULT_UNIT_ID,
    val active: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val parsedRole: UserRole get() = UserRole.fromRaw(role)
}

@Keep
@IgnoreExtraProperties
data class AccessEntry(
    @DocumentId val id: String = "",
    val unitId: String = DEFAULT_UNIT_ID,
    val entryDate: String = "",
    val rank: String = "",
    val fullName: String = "",
    val documentNumber: String = "",
    val plate: String = "",
    val destinationUnit: String = "",
    val reason: String = "",
    val visitorType: String = VisitorType.VISITANTE.name,
    val expectedTime: String = "",
    val source: String = EntrySource.MANUAL.name,
    val notes: String = "",
    val status: String = EntryStatus.PENDIENTE.name,
    val checkInAt: Timestamp? = null,
    val checkInByUid: String? = null,
    val checkInByName: String? = null,
    val createdByUid: String = "",
    val createdByName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val parsedVisitorType: VisitorType get() = VisitorType.fromRaw(visitorType)
    val parsedSource: EntrySource get() = EntrySource.fromRaw(source)
    val parsedStatus: EntryStatus get() = EntryStatus.fromRaw(status)
    val displayTitleWithRank: String
        get() = if (rank.isNotBlank()) "$rank $fullName" else fullName
}

data class EntryDraft(
    val entryDate: String,
    val rank: String,
    val fullName: String,
    val documentNumber: String,
    val plate: String,
    val destinationUnit: String,
    val reason: String,
    val visitorType: VisitorType,
    val expectedTime: String,
    val source: EntrySource,
    val notes: String
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
