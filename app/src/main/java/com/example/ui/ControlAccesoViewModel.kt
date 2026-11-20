package com.example.ui

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
import com.example.util.DateTimeUtils
import com.example.util.TimeSlotFilter
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class EntryStatusFilter(val label: String) {
    ALL("Todos"),
    PENDING("Pendiente por ingresar"),
    CHECKED_IN("Ya ingresó")
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
    private val currentUserId: String
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

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarEvents = _snackbarEvents.asSharedFlow()

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
                        flowOf<UiState<List<AccessEntry>>>(UiState.Success(emptyList()))
                    } else {
                        val effectiveIsAdmin = profile.parsedRole == UserRole.ADMIN && !simOperator
                        val dateFilter = if (effectiveIsAdmin && dateScope == DateScopeFilter.FULL_HISTORY) {
                            null
                        } else {
                            DateTimeUtils.todayIso()
                        }
                        repository.observeEntries(
                            currentUid = currentUserId,
                            unitId = profile.unitId,
                            entryDateFilter = dateFilter
                        )
                            .map<List<AccessEntry>, UiState<List<AccessEntry>>> { UiState.Success(it) }
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

    fun markCheckInNow(entry: AccessEntry) {
        val profile = (profileState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            _busyEntryId.value = entry.id
            val result = repository.markCheckInNow(
                entryId = entry.id,
                actorUid = currentUserId,
                actorName = profile.displayName
            )
            _busyEntryId.value = null
            result.onSuccess {
                _snackbarEvents.tryEmit("Ingreso registrado: ${entry.fullName}")
            }.onFailure { error ->
                _snackbarEvents.tryEmit(error.localizedMessage ?: "No se pudo marcar el ingreso")
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
                result.onSuccess {
                    _snackbarEvents.tryEmit("Ingreso creado: ${draft.fullName}")
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
                result.onSuccess { count ->
                    _snackbarEvents.tryEmit("$count registros importados desde correo")
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
                result.onSuccess {
                    _snackbarEvents.tryEmit("Registro actualizado: ${draft.fullName}")
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
            result.onSuccess {
                _snackbarEvents.tryEmit("Registro eliminado")
            }.onFailure { error ->
                _snackbarEvents.tryEmit(error.localizedMessage ?: "No se pudo eliminar")
            }
        }
    }

    fun updateUserRoleAndStatus(targetUser: UserProfile, role: UserRole, active: Boolean) {
        viewModelScope.launch {
            val result = repository.updateUserRoleAndStatus(targetUser.userId, role, active)
            result.onSuccess {
                _snackbarEvents.tryEmit("Permisos actualizados para ${targetUser.displayName}")
            }.onFailure { error ->
                _snackbarEvents.tryEmit(error.localizedMessage ?: "Error al actualizar permisos")
            }
        }
    }

    companion object {
        private const val TAG = "ControlAccesoVM"
        private const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
