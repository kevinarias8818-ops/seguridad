package com.example.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AccessRepository
import com.example.model.AccessEntry
import com.example.model.EntryDraft
import com.example.model.EntryStatus
import com.example.model.UiState
import com.example.model.UserProfile
import com.example.model.UserRole
import com.example.notifications.FcmTokenManager
import com.example.util.DateTimeUtils
import com.example.util.ExcelReportGenerator
import com.example.util.ExcelReportResult
import com.example.util.NetworkMonitor
import com.example.util.PlateImageAttachment
import com.example.util.ReportPeriod
import com.example.util.TimeSlotFilter
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ExcelExportUiState {
    data object Idle : ExcelExportUiState
    data class Generating(val period: ReportPeriod) : ExcelExportUiState
    data class Ready(val report: ExcelReportResult) : ExcelExportUiState
    data class Error(val message: String) : ExcelExportUiState
}

data class SyncStatusState(
    val isOnline: Boolean = true,
    val isFromCache: Boolean = false,
    val pendingSyncIds: Set<String> = emptySet()
)

data class EntryPushAlert(
    val entryId: String,
    val title: String,
    val body: String,
    val status: String = ""
)

enum class EntryStatusFilter(val label: String) {
    ALL("Todos"),
    PENDING("Pendiente por ingresar"),
    CHECKED_IN("Ya ingresó"),
    CHECKED_OUT("Ya salió")
}

enum class DateScopeFilter(val label: String) {
    TODAY("Hoy"),
    FULL_HISTORY("Historial completo")
}

data class DashboardFilterState(
    val searchQuery: String = "",
    val statusFilter: EntryStatusFilter = EntryStatusFilter.ALL,
    val dateScope: DateScopeFilter = DateScopeFilter.TODAY,
    val timeSlot: TimeSlotFilter = TimeSlotFilter.ALL
)

@OptIn(ExperimentalCoroutinesApi::class)
class ControlAccesoViewModel(
    private val repository: AccessRepository,
    private val currentUserId: String,
    private val networkMonitor: NetworkMonitor? = null
) : ViewModel() {

    private val _profileReady = MutableStateFlow(false)
    private val _profileInitError = MutableStateFlow<String?>(null)

    private val _simulateOperatorMode = MutableStateFlow(false)
    val simulateOperatorMode: StateFlow<Boolean> = _simulateOperatorMode.asStateFlow()

    private val _filterState = MutableStateFlow(DashboardFilterState())
    val filterState: StateFlow<DashboardFilterState> = _filterState.asStateFlow()

    private val _busyEntryId = MutableStateFlow<String?>(null)
    val busyEntryId: StateFlow<String?> = _busyEntryId.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _isFromCache = MutableStateFlow(false)
    private val _pendingSyncIds = MutableStateFlow<Set<String>>(emptySet())

    val syncStatusState: StateFlow<SyncStatusState> =
        combine(
            networkMonitor?.isOnline ?: MutableStateFlow(true),
            _isFromCache,
            _pendingSyncIds
        ) { online, fromCache, pendingIds ->
            SyncStatusState(
                isOnline = online,
                isFromCache = fromCache,
                pendingSyncIds = pendingIds
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SyncStatusState(isOnline = networkMonitor?.isCurrentlyOnline() ?: true)
        )

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarEvents = _snackbarEvents.asSharedFlow()

    private val _entryAlerts = MutableSharedFlow<EntryPushAlert>(extraBufferCapacity = 8)
    val entryAlerts = _entryAlerts.asSharedFlow()

    private val _excelExportState = MutableStateFlow<ExcelExportUiState>(ExcelExportUiState.Idle)
    val excelExportState: StateFlow<ExcelExportUiState> = _excelExportState.asStateFlow()

    private var hasLoadedInitialEntries = false
    private val knownEntryStatuses = mutableMapOf<String, String>()

    val profileState: StateFlow<UiState<UserProfile>> =
        combine(_profileReady, _profileInitError) { ready, initError -> ready to initError }
            .flatMapLatest { (ready, initError) ->
                if (initError != null) {
                    flowOf<UiState<UserProfile>>(UiState.Error(initError))
                } else if (!ready) {
                    flowOf<UiState<UserProfile>>(UiState.Loading)
                } else {
                    repository.observeUserProfile(currentUserId)
                        .map<UserProfile?, UiState<UserProfile>> { profile ->
                            if (profile == null) {
                                UiState.Loading
                            } else if (profile.parsedRole != UserRole.PENDING && !profile.active) {
                                UiState.Error("Tu cuenta está suspendida por el Administrador.")
                            } else {
                                UiState.Success(profile)
                            }
                        }
                        .catch { error ->
                            val firestoreError = generateSequence(error) { it.cause }
                                .filterIsInstance<FirebaseFirestoreException>()
                                .firstOrNull()
                            if (firestoreError == null && error is CancellationException) throw error
                            val rootError = firestoreError ?: error
                            Log.w(TAG, "Error observing profile", rootError)
                            emit(UiState.Error(rootError.message ?: "Error al cargar perfil"))
                        }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading
            )

    val entriesState: StateFlow<UiState<List<AccessEntry>>> =
        combine(profileState, _filterState, _simulateOperatorMode) { profileUi, filters, simOperator ->
            Triple(profileUi, filters.dateScope, simOperator)
        }.flatMapLatest { (profileUi, dateScope, simOperator) ->
            when (profileUi) {
                is UiState.Loading -> flowOf<UiState<List<AccessEntry>>>(UiState.Loading)
                is UiState.Error -> flowOf<UiState<List<AccessEntry>>>(UiState.Error(profileUi.message))
                is UiState.Success -> {
                    val profile = profileUi.data
                    val isAuthorized = profile.active &&
                        (profile.parsedRole == UserRole.ADMIN || profile.parsedRole == UserRole.OPERATOR)
                    if (!isAuthorized) {
                        _isFromCache.value = false
                        _pendingSyncIds.value = emptySet()
                        flowOf<UiState<List<AccessEntry>>>(UiState.Success(emptyList()))
                    } else {
                        val effectiveIsAdmin = profile.parsedRole == UserRole.ADMIN && !simOperator
                        val dateFilter = if (effectiveIsAdmin && dateScope == DateScopeFilter.FULL_HISTORY) {
                            null
                        } else {
                            DateTimeUtils.todayIso()
                        }
                        hasLoadedInitialEntries = false
                        knownEntryStatuses.clear()
                        repository.observeEntries(
                            currentUid = currentUserId,
                            unitId = profile.unitId,
                            entryDateFilter = dateFilter
                        )
                            .onEach { snap ->
                                _isFromCache.value = snap.isFromCache
                                _pendingSyncIds.value = snap.pendingSyncIds
                                if (profile.active &&
                                    (profile.parsedRole == UserRole.ADMIN || profile.parsedRole == UserRole.OPERATOR)
                                ) {
                                    detectNewEntryAlerts(snap.entries, profile.parsedRole)
                                }
                            }
                            .map<com.example.data.EntriesSnapshot, UiState<List<AccessEntry>>> { snap ->
                                UiState.Success(snap.entries)
                            }
                            .catch { error ->
                                val firestoreError = generateSequence(error) { it.cause }
                                    .filterIsInstance<FirebaseFirestoreException>()
                                    .firstOrNull()
                                if (firestoreError == null && error is CancellationException) throw error
                                val rootError = firestoreError ?: error
                                Log.w(TAG, "Error observing entries", rootError)
                                emit(UiState.Error(rootError.message ?: "Error al sincronizar ingresos"))
                            }
                    }
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val allUsersState: StateFlow<UiState<List<UserProfile>>> =
        profileState.flatMapLatest { profileUi ->
            if (profileUi is UiState.Success && profileUi.data.parsedRole == UserRole.ADMIN) {
                repository.observeAllUsers(currentUserId)
                    .map<List<UserProfile>, UiState<List<UserProfile>>> { UiState.Success(it) }
                    .catch { error ->
                        val firestoreError = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (firestoreError == null && error is CancellationException) throw error
                        val rootError = firestoreError ?: error
                        Log.w(TAG, "Error observing users", rootError)
                        emit(UiState.Error(rootError.message ?: "Error al cargar usuarios"))
                    }
            } else {
                flowOf(UiState.Success(emptyList()))
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    fun initializeProfile(firebaseUser: FirebaseUser) {
        if (_profileReady.value) return
        viewModelScope.launch {
            _profileInitError.value = null
            val result = repository.ensureUserProfile(firebaseUser)
            result.onSuccess {
                _profileReady.value = true
            }.onFailure { error ->
                _profileInitError.value = error.localizedMessage ?: "No se pudo inicializar tu perfil."
            }
        }
    }

    fun retryProfileInitialization(firebaseUser: FirebaseUser) {
        _profileReady.value = false
        initializeProfile(firebaseUser)
    }

    fun updateSearchQuery(query: String) {
        _filterState.value = _filterState.value.copy(searchQuery = query)
    }

    fun updateStatusFilter(statusFilter: EntryStatusFilter) {
        _filterState.value = _filterState.value.copy(statusFilter = statusFilter)
    }

    fun updateDateScope(dateScope: DateScopeFilter) {
        _filterState.value = _filterState.value.copy(dateScope = dateScope)
    }

    fun updateTimeSlot(timeSlot: TimeSlotFilter) {
        _filterState.value = _filterState.value.copy(timeSlot = timeSlot)
    }

    fun toggleSimulateOperatorMode(enabled: Boolean) {
        _simulateOperatorMode.value = enabled
        if (enabled && _filterState.value.dateScope == DateScopeFilter.FULL_HISTORY) {
            _filterState.value = _filterState.value.copy(dateScope = DateScopeFilter.TODAY)
        }
        val msg = if (enabled) {
            "Vista previa de Operador activada (solo lectura del día y marcar ingreso)"
        } else {
            "Modo Administrador restaurado"
        }
        _snackbarEvents.tryEmit(msg)
    }

    fun markCheckInNow(
        entry: AccessEntry,
        plateAttachment: PlateImageAttachment? = null
    ) {
        val profile = (profileState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            _busyEntryId.value = entry.id
            val result = repository.markCheckInNow(
                entryId = entry.id,
                actorUid = currentUserId,
                actorName = profile.displayName,
                plateImageName = plateAttachment?.fileName,
                plateImageData = plateAttachment?.dataUrl
            )
            _busyEntryId.value = null
            result.onSuccess { outcome ->
                val baseMsg = if (plateAttachment != null) {
                    "Ingreso y foto de placa registrados: ${entry.fullName}"
                } else {
                    "Ingreso registrado: ${entry.fullName}"
                }
                val finalMsg = if (outcome.queuedOffline) {
                    "$baseMsg (guardado offline; sincronizará al volver el internet)"
                } else {
                    baseMsg
                }
                _snackbarEvents.tryEmit(finalMsg)
            }.onFailure { error ->
                _snackbarEvents.tryEmit(error.localizedMessage ?: "No se pudo marcar el ingreso")
            }
        }
    }

    fun attachPlateImage(entry: AccessEntry, attachment: PlateImageAttachment) {
        viewModelScope.launch {
            _busyEntryId.value = entry.id
            val result = repository.attachPlateImage(
                entryId = entry.id,
                plateImageName = attachment.fileName,
                plateImageData = attachment.dataUrl
            )
            _busyEntryId.value = null
            result.onSuccess { outcome ->
                val msg = if (outcome.queuedOffline) {
                    "Placa guardada offline (${attachment.fileName}). Se sincronizará al volver el internet."
                } else {
                    "Imagen de placa guardada: ${attachment.fileName}"
                }
                _snackbarEvents.tryEmit(msg)
            }.onFailure { error ->
                _snackbarEvents.tryEmit(error.localizedMessage ?: "No se pudo guardar la imagen de la placa")
            }
        }
    }

    fun showMessage(message: String) {
        if (message.isNotBlank()) {
            _snackbarEvents.tryEmit(message)
        }
    }

    fun generateExcelReport(context: Context, period: ReportPeriod) {
        val profile = (profileState.value as? UiState.Success)?.data
        val isAuthorizedAdmin = profile != null &&
            profile.active &&
            profile.parsedRole == UserRole.ADMIN &&
            !_simulateOperatorMode.value
        if (!isAuthorizedAdmin) {
            val msg = "Solo el rol Administrador puede descargar reportes en Excel."
            _excelExportState.value = ExcelExportUiState.Error(msg)
            _snackbarEvents.tryEmit(msg)
            return
        }
        val appContext = context.applicationContext
        viewModelScope.launch {
            _excelExportState.value = ExcelExportUiState.Generating(period)
            val fetchResult = repository.fetchEntriesForUnit(profile.unitId)
            fetchResult.onSuccess { entries ->
                val reportResult = runCatching {
                    withContext(Dispatchers.IO) {
                        ExcelReportGenerator.generateReportFile(
                            context = appContext,
                            entries = entries,
                            period = period,
                            generatedBy = profile.displayName.ifBlank { profile.email },
                            unitId = profile.unitId
                        )
                    }
                }
                reportResult.onSuccess { report ->
                    _excelExportState.value = ExcelExportUiState.Ready(report)
                }.onFailure { err ->
                    Log.w(TAG, "Error generating Excel report", err)
                    _excelExportState.value = ExcelExportUiState.Error(
                        err.localizedMessage ?: "No se pudo construir el archivo Excel."
                    )
                }
            }.onFailure { err ->
                _excelExportState.value = ExcelExportUiState.Error(
                    err.localizedMessage ?: "No se pudieron consultar los registros de Firestore."
                )
            }
        }
    }

    fun resetExcelExportState() {
        _excelExportState.value = ExcelExportUiState.Idle
    }

    fun markCheckOutNow(entry: AccessEntry) {
        val profile = (profileState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            _busyEntryId.value = entry.id
            val result = repository.markCheckOutNow(
                entryId = entry.id,
                actorUid = currentUserId,
                actorName = profile.displayName
            )
            _busyEntryId.value = null
            result.onSuccess { outcome ->
                val msg = if (outcome.queuedOffline) {
                    "Salida guardada offline (${entry.fullName}). Se sincronizará al volver el internet."
                } else {
                    "Salida registrada: ${entry.fullName}"
                }
                _snackbarEvents.tryEmit(msg)
            }.onFailure { error ->
                _snackbarEvents.tryEmit(error.localizedMessage ?: "No se pudo marcar la salida")
            }
        }
    }

    fun createEntry(draft: EntryDraft, onDone: () -> Unit) {
        if (_isSubmitting.value) return
        val profile = (profileState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                val result = repository.createEntry(
                    draft = draft,
                    actorUid = currentUserId,
                    actorName = profile.displayName,
                    unitId = profile.unitId
                )
                result.onSuccess { outcome ->
                    val msg = if (outcome.queuedOffline) {
                        "Ingreso guardado offline (${draft.fullName}). Se sincronizará al reconectar."
                    } else {
                        "Ingreso creado: ${draft.fullName}"
                    }
                    _snackbarEvents.tryEmit(msg)
                    onDone()
                }.onFailure { error ->
                    _snackbarEvents.tryEmit(error.localizedMessage ?: "Error al guardar el ingreso")
                }
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    fun importEntriesBatch(drafts: List<EntryDraft>, onDone: () -> Unit) {
        if (_isSubmitting.value) return
        val profile = (profileState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                val result = repository.createEntriesBatch(
                    drafts = drafts,
                    actorUid = currentUserId,
                    actorName = profile.displayName,
                    unitId = profile.unitId
                )
                result.onSuccess { outcome ->
                    val msg = if (outcome.queuedOffline) {
                        "${outcome.count} registros guardados offline. Se sincronizarán al reconectar."
                    } else {
                        "${outcome.count} registros importados desde correo"
                    }
                    _snackbarEvents.tryEmit(msg)
                    onDone()
                }.onFailure { error ->
                    _snackbarEvents.tryEmit(error.localizedMessage ?: "Error al importar registros")
                }
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    fun updateEntry(
        entryId: String,
        draft: EntryDraft,
        status: EntryStatus,
        existingEntry: AccessEntry,
        onDone: () -> Unit
    ) {
        if (_isSubmitting.value) return
        val profile = (profileState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                val result = repository.updateEntry(
                    entryId = entryId,
                    draft = draft,
                    newStatus = status,
                    existingEntry = existingEntry,
                    actorUid = currentUserId,
                    actorName = profile.displayName
                )
                result.onSuccess { outcome ->
                    val msg = if (outcome.queuedOffline) {
                        "Cambios guardados offline (${draft.fullName}). Se sincronizarán al reconectar."
                    } else {
                        "Registro actualizado: ${draft.fullName}"
                    }
                    _snackbarEvents.tryEmit(msg)
                    onDone()
                }.onFailure { error ->
                    _snackbarEvents.tryEmit(error.localizedMessage ?: "No se pudo actualizar el registro")
                }
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    fun deleteEntry(entry: AccessEntry) {
        viewModelScope.launch {
            val result = repository.deleteEntry(entry.id)
            result.onSuccess { outcome ->
                val msg = if (outcome.queuedOffline) {
                    "Eliminación en cola offline. Se aplicará al reconectar."
                } else {
                    "Registro eliminado"
                }
                _snackbarEvents.tryEmit(msg)
            }.onFailure { error ->
                _snackbarEvents.tryEmit(error.localizedMessage ?: "No se pudo eliminar")
            }
        }
    }

    fun updateUserRoleAndStatus(targetUser: UserProfile, role: UserRole, active: Boolean) {
        viewModelScope.launch {
            val result = repository.updateUserRoleAndStatus(targetUser.userId, role, active)
            result.onSuccess { outcome ->
                val msg = if (outcome.queuedOffline) {
                    "Permisos guardados offline para ${targetUser.displayName}. Se sincronizarán al reconectar."
                } else {
                    "Permisos actualizados para ${targetUser.displayName}"
                }
                _snackbarEvents.tryEmit(msg)
            }.onFailure { error ->
                _snackbarEvents.tryEmit(error.localizedMessage ?: "Error al actualizar permisos")
            }
        }
    }

    fun syncStaffPushToken(context: Context, unitId: String, role: UserRole) {
        viewModelScope.launch {
            FcmTokenManager.registerStaffPushToken(
                context = context.applicationContext,
                repository = repository,
                uid = currentUserId,
                unitId = unitId,
                role = role
            )
        }
    }

    fun unregisterInactivePush(unitId: String) {
        viewModelScope.launch {
            FcmTokenManager.unregisterAllPushTopics(unitId)
        }
    }

    suspend fun clearPushRegistrationOnSignOut(unitId: String) {
        FcmTokenManager.clearPushRegistrationOnSignOut(
            repository = repository,
            uid = currentUserId,
            unitId = unitId
        )
    }

    private fun detectNewEntryAlerts(entries: List<AccessEntry>, role: UserRole) {
        if (!hasLoadedInitialEntries) {
            entries.forEach { knownEntryStatuses[it.id] = it.status }
            hasLoadedInitialEntries = true
            return
        }

        for (entry in entries) {
            val prevStatus = knownEntryStatuses[entry.id]
            val personLabel = listOf(entry.rank, entry.fullName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { entry.fullName }

            val becameCheckedIn = prevStatus != null &&
                prevStatus == EntryStatus.PENDIENTE.name &&
                entry.status == EntryStatus.INGRESO.name &&
                entry.checkInByUid != currentUserId

            val becameCheckedOut = prevStatus != null &&
                prevStatus != EntryStatus.SALIDA.name &&
                entry.status == EntryStatus.SALIDA.name &&
                entry.checkOutByUid != currentUserId

            val createdBySomeoneElse = prevStatus == null &&
                entry.createdByUid != currentUserId

            if (shouldEmitEntryAlert(
                    role = role,
                    isNewEntry = createdBySomeoneElse,
                    becameCheckedIn = becameCheckedIn,
                    becameCheckedOut = becameCheckedOut
                )
            ) {
                when {
                    becameCheckedIn -> {
                        val actor = entry.checkInByName.orEmpty().ifBlank { "Operador" }
                        _entryAlerts.tryEmit(
                            EntryPushAlert(
                                entryId = entry.id,
                                title = "Nuevo ingreso registrado: $personLabel",
                                body = "${entry.destinationUnit} · Marcado por $actor",
                                status = entry.status
                            )
                        )
                    }
                    becameCheckedOut -> {
                        val actor = entry.checkOutByName.orEmpty().ifBlank { "Operador" }
                        _entryAlerts.tryEmit(
                            EntryPushAlert(
                                entryId = entry.id,
                                title = "Salida registrada: $personLabel",
                                body = "${entry.destinationUnit} · Marcado por $actor",
                                status = entry.status
                            )
                        )
                    }
                    createdBySomeoneElse -> {
                        val stateLabel = entry.parsedStatus.label
                        _entryAlerts.tryEmit(
                            EntryPushAlert(
                                entryId = entry.id,
                                title = "Nuevo ingreso autorizado: $personLabel",
                                body = "${entry.destinationUnit} · Estado: $stateLabel",
                                status = entry.status
                            )
                        )
                    }
                }
            }
            knownEntryStatuses[entry.id] = entry.status
        }
    }

    override fun onCleared() {
        networkMonitor?.dispose()
        super.onCleared()
    }

    companion object {
        private const val TAG = "ControlAccesoVM"
        private const val STOP_TIMEOUT_MILLIS = 5000L

        fun shouldEmitEntryAlert(
            role: UserRole,
            isNewEntry: Boolean,
            becameCheckedIn: Boolean,
            becameCheckedOut: Boolean
        ): Boolean = when (role) {
            UserRole.ADMIN -> isNewEntry || becameCheckedIn || becameCheckedOut
            UserRole.OPERATOR -> isNewEntry || becameCheckedIn
            UserRole.PENDING -> false
        }
    }
}
