package com.example.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableView
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.FilterChipDefaults
import com.example.auth.GoogleAuthHelper
import com.example.model.AccessEntry
import com.example.model.EntryStatus
import com.example.model.TrashedEntry
import com.example.model.UiState
import com.example.model.UserProfile
import com.example.model.UserRole
import com.example.notifications.NotificationHelper
import com.example.ui.theme.Emerald100
import com.example.ui.theme.Emerald600
import com.example.ui.theme.Emerald900
import com.example.util.DateTimeUtils
import com.example.util.PlateImageAttachment
import com.example.util.TimeSlotFilter
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.launch

private enum class AppTab(val label: String) {
    ENTRIES("Ingresos"),
    TRASH("Papelera"),
    USERS("Usuarios")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardRoute(
    firebaseUser: FirebaseUser,
    viewModel: ControlAccesoViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val profileState by viewModel.profileState.collectAsStateWithLifecycle()
    val entriesState by viewModel.entriesState.collectAsStateWithLifecycle()
    val activeOnSiteEntries by viewModel.activeOnSiteEntriesState.collectAsStateWithLifecycle()
    val trashedState by viewModel.trashedEntriesState.collectAsStateWithLifecycle()
    val usersState by viewModel.allUsersState.collectAsStateWithLifecycle()
    val filterState by viewModel.filterState.collectAsStateWithLifecycle()
    val simulateOperator by viewModel.simulateOperatorMode.collectAsStateWithLifecycle()
    val busyEntryId by viewModel.busyEntryId.collectAsStateWithLifecycle()
    val isSubmitting by viewModel.isSubmitting.collectAsStateWithLifecycle()
    val excelExportState by viewModel.excelExportState.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatusState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    var currentTab by remember { mutableStateOf(AppTab.ENTRIES) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<AccessEntry?>(null) }
    var showEmailImportDialog by remember { mutableStateOf(false) }
    var showExcelExportDialog by remember { mutableStateOf(false) }
    var entryToDelete by remember { mutableStateOf<AccessEntry?>(null) }
    var trashedToPurge by remember { mutableStateOf<TrashedEntry?>(null) }

    val profile = (profileState as? UiState.Success)?.data
    val loadedRole = profile?.parsedRole ?: UserRole.PENDING
    val isRealAdmin = loadedRole == UserRole.ADMIN && profile?.active == true
    val isEffectiveAdmin = isRealAdmin && !simulateOperator
    val isActiveDiamanteAlma = profile != null && profile.active && loadedRole == UserRole.DIAMANTE_ALMA
    val isActiveStaff = profile != null && profile.active && loadedRole.isActiveStaffRole
    val canCreateEntries = isEffectiveAdmin || isActiveDiamanteAlma
    val canSendToTrash = isEffectiveAdmin || isActiveDiamanteAlma
    val canUseFullHistory = isEffectiveAdmin || isActiveDiamanteAlma
    val receivesPushAlerts = profile != null &&
        profile.active &&
        (loadedRole == UserRole.ADMIN || loadedRole == UserRole.OPERATOR)
    val isPendingApproval = profile != null &&
        (loadedRole == UserRole.PENDING || !profile.active)
    val pendingUsersCount = (usersState as? UiState.Success)?.data
        ?.count { it.parsedRole == UserRole.PENDING } ?: 0
    val trashedCount = (trashedState as? UiState.Success)?.data?.size ?: 0

    var hasNotificationPermission by remember {
        mutableStateOf(NotificationHelper.hasNotificationPermission(context))
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted || NotificationHelper.hasNotificationPermission(context)
    }

    LaunchedEffect(profile?.userId, profile?.role, profile?.active) {
        val loadedProfile = profile ?: return@LaunchedEffect
        val staffRole = loadedProfile.parsedRole
        if (loadedProfile.active && (staffRole == UserRole.ADMIN || staffRole == UserRole.OPERATOR)) {
            NotificationHelper.ensureChannel(context)
            viewModel.syncStaffPushToken(context, loadedProfile.unitId, staffRole)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !NotificationHelper.hasNotificationPermission(context)
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            viewModel.unregisterInactivePush(loadedProfile.unitId)
        }
    }

    LaunchedEffect(receivesPushAlerts) {
        if (!receivesPushAlerts) return@LaunchedEffect
        viewModel.entryAlerts.collect { alert ->
            val tag = if (alert.status.isNotBlank()) "${alert.entryId}_${alert.status}" else alert.entryId
            NotificationHelper.showEntryAlert(
                context = context,
                title = alert.title,
                body = alert.body,
                entryId = alert.entryId,
                notificationTag = tag
            )
            snackbarHostState.showSnackbar(alert.title)
        }
    }

    if (currentTab != AppTab.ENTRIES) {
        BackHandler { currentTab = AppTab.ENTRIES }
    }

    LaunchedEffect(isEffectiveAdmin) {
        if (!isEffectiveAdmin && (currentTab == AppTab.USERS || currentTab == AppTab.TRASH)) {
            currentTab = AppTab.ENTRIES
        }
        if (!isEffectiveAdmin && showExcelExportDialog) {
            showExcelExportDialog = false
            viewModel.resetExcelExportState()
        }
        if (!isEffectiveAdmin) {
            trashedToPurge = null
            editingEntry = null
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 720.dp
        val gridColumns = if (maxWidth >= 900.dp) 2 else 1

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    title = {
                        Column {
                            Text(
                                text = "Control Acceso",
                                style = MaterialTheme.typography.titleLarge
                            )
                            if (profile != null) {
                                val roleText = when {
                                    isPendingApproval -> "Pendiente de aprobación"
                                    isEffectiveAdmin -> "Administrador"
                                    isRealAdmin -> "Vista Operador"
                                    isActiveDiamanteAlma -> "Diamante Alma"
                                    else -> "Operador"
                                }
                                val statusSuffix = if (!syncStatus.isOnline) " · Sin internet (Modo Offline)" else ""
                                Text(
                                    text = "${profile.displayName} · $roleText$statusSuffix",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (!syncStatus.isOnline || isPendingApproval) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                )
                            }
                        }
                    },
                    actions = {
                        if (isRealAdmin) {
                            IconButton(
                                onClick = { viewModel.toggleSimulateOperatorMode(!simulateOperator) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("toggle_operator_preview_button")
                            ) {
                                Icon(
                                    imageVector = if (simulateOperator) {
                                        Icons.Filled.AdminPanelSettings
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = if (simulateOperator) {
                                        "Volver a modo Administrador"
                                    } else {
                                        "Probar vista Operador"
                                    },
                                    tint = if (simulateOperator) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                val unit = profile?.unitId ?: "UNIDAD_PRINCIPAL"
                                scope.launch {
                                    viewModel.clearPushRegistrationOnSignOut(unit)
                                    GoogleAuthHelper.signOut(credentialManager, scope)
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("sign_out_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = "Cerrar sesión"
                            )
                        }
                    }
                )
            },
            bottomBar = {
                if (!isWideScreen && isEffectiveAdmin) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentTab == AppTab.ENTRIES,
                            onClick = { currentTab = AppTab.ENTRIES },
                            icon = { Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null) },
                            label = { Text("Ingresos") },
                            modifier = Modifier.testTag("nav_entries_tab")
                        )
                        NavigationBarItem(
                            selected = currentTab == AppTab.TRASH,
                            onClick = { currentTab = AppTab.TRASH },
                            icon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
                            label = {
                                Text(if (trashedCount > 0) "Papelera ($trashedCount)" else "Papelera")
                            },
                            modifier = Modifier.testTag("nav_trash_tab")
                        )
                        NavigationBarItem(
                            selected = currentTab == AppTab.USERS,
                            onClick = { currentTab = AppTab.USERS },
                            icon = { Icon(Icons.Filled.Group, contentDescription = null) },
                            label = {
                                Text(
                                    if (pendingUsersCount > 0) "Usuarios ($pendingUsersCount)" else "Usuarios"
                                )
                            },
                            modifier = Modifier.testTag("nav_users_tab")
                        )
                    }
                }
            },
            floatingActionButton = {
                if (canCreateEntries && !isPendingApproval && currentTab == AppTab.ENTRIES) {
                    ExtendedFloatingActionButton(
                        onClick = { showCreateDialog = true },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("Nuevo ingreso") },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.testTag("add_entry_fab")
                    )
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWideScreen && isEffectiveAdmin) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        NavigationRailItem(
                            selected = currentTab == AppTab.ENTRIES,
                            onClick = { currentTab = AppTab.ENTRIES },
                            icon = { Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null) },
                            label = { Text("Ingresos") }
                        )
                        NavigationRailItem(
                            selected = currentTab == AppTab.TRASH,
                            onClick = { currentTab = AppTab.TRASH },
                            icon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
                            label = {
                                Text(if (trashedCount > 0) "Papelera ($trashedCount)" else "Papelera")
                            }
                        )
                        NavigationRailItem(
                            selected = currentTab == AppTab.USERS,
                            onClick = { currentTab = AppTab.USERS },
                            icon = { Icon(Icons.Filled.Group, contentDescription = null) },
                            label = {
                                Text(if (pendingUsersCount > 0) "Usuarios ($pendingUsersCount)" else "Usuarios")
                            }
                        )
                    }
                }

                when (val profState = profileState) {
                    is UiState.Loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator()
                                Text("Cargando perfil y permisos...")
                            }
                        }
                    }

                    is UiState.Error -> {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = profState.message,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Button(onClick = { viewModel.retryProfileInitialization(firebaseUser) }) {
                                        Text("Reintentar")
                                    }
                                }
                            }
                        }
                    }

                    is UiState.Success -> {
                        if (isPendingApproval) {
                            PendingAccessScreen(
                                profile = profState.data,
                                onSignOut = { GoogleAuthHelper.signOut(credentialManager, scope) },
                                modifier = Modifier.weight(1f)
                            )
                        } else if (currentTab == AppTab.TRASH && isEffectiveAdmin) {
                            TrashEntriesScreen(
                                trashedState = trashedState,
                                busyEntryId = busyEntryId,
                                onRestore = viewModel::restoreEntryFromTrash,
                                onPermanentDeleteRequest = { trashedToPurge = it },
                                modifier = Modifier.weight(1f)
                            )
                        } else if (currentTab == AppTab.USERS && isEffectiveAdmin) {
                            UsersManagementScreen(
                                currentUserId = profState.data.userId,
                                usersState = usersState,
                                onUpdateUserRole = { user, role, active ->
                                    viewModel.updateUserRoleAndStatus(user, role, active)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            EntriesDashboardContent(
                                profile = profState.data,
                                isEffectiveAdmin = isEffectiveAdmin,
                                canCreateEntries = canCreateEntries,
                                canSendToTrash = canSendToTrash,
                                canUseFullHistory = canUseFullHistory,
                                isSimulatingOperator = isRealAdmin && simulateOperator,
                                showEnableNotificationsBanner = receivesPushAlerts && !hasNotificationPermission,
                                onRequestNotifications = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(
                                            Manifest.permission.POST_NOTIFICATIONS
                                        )
                                    }
                                },
                                entriesState = entriesState,
                                syncStatus = syncStatus,
                                filterState = filterState,
                                busyEntryId = busyEntryId,
                                gridColumns = gridColumns,
                                isWideMetrics = this@BoxWithConstraints.maxWidth >= 560.dp,
                                onSearchChange = viewModel::updateSearchQuery,
                                onStatusFilterChange = viewModel::updateStatusFilter,
                                onDateScopeChange = viewModel::updateDateScope,
                                onTimeSlotChange = viewModel::updateTimeSlot,
                                onOpenEmailImport = { showEmailImportDialog = true },
                                onOpenCreateEntry = { showCreateDialog = true },
                                onOpenExcelExport = {
                                    viewModel.resetExcelExportState()
                                    showExcelExportDialog = true
                                },
                                onCheckInNow = viewModel::markCheckInNow,
                                onCheckOutNow = viewModel::markCheckOutNow,
                                onAttachPlateImage = viewModel::attachPlateImage,
                                onMediaError = viewModel::showMessage,
                                onEditEntry = { editingEntry = it },
                                onDeleteEntry = { entryToDelete = it },
                                onRestoreAdminMode = { viewModel.toggleSimulateOperatorMode(false) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog && canCreateEntries) {
        EntryEditorDialog(
            existingEntry = null,
            activeOnSiteEntries = activeOnSiteEntries,
            isSaving = isSubmitting,
            onDismiss = { if (!isSubmitting) showCreateDialog = false },
            onSaveNew = { draft ->
                viewModel.createEntry(draft) { showCreateDialog = false }
            },
            onSaveEdit = { _, _, _, _ -> },
            onMediaError = viewModel::showMessage
        )
    }

    editingEntry?.takeIf { isEffectiveAdmin }?.let { entry ->
        EntryEditorDialog(
            existingEntry = entry,
            activeOnSiteEntries = activeOnSiteEntries,
            isSaving = isSubmitting,
            onDismiss = { if (!isSubmitting) editingEntry = null },
            onSaveNew = {},
            onSaveEdit = { entryId, draft, status, existing ->
                viewModel.updateEntry(entryId, draft, status, existing) { editingEntry = null }
            },
            onMediaError = viewModel::showMessage
        )
    }

    if (showEmailImportDialog && canCreateEntries) {
        EmailImportDialog(
            isSaving = isSubmitting,
            onDismiss = { if (!isSubmitting) showEmailImportDialog = false },
            onImportBatch = { drafts ->
                viewModel.importEntriesBatch(drafts) { showEmailImportDialog = false }
            }
        )
    }

    if (showExcelExportDialog && isEffectiveAdmin) {
        ExcelExportDialog(
            exportState = excelExportState,
            onGenerate = { period -> viewModel.generateExcelReport(context, period) },
            onReset = viewModel::resetExcelExportState,
            onMessage = viewModel::showMessage,
            onDismiss = { showExcelExportDialog = false }
        )
    }

    entryToDelete?.takeIf { canSendToTrash }?.let { entry ->
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            title = { Text("Enviar a la papelera") },
            text = {
                Text(
                    "El ingreso de ${entry.fullName} se moverá a la papelera y dejará de contarse en los ingresos activos. Solo el Administrador podrá restaurarlo o eliminarlo definitivamente."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.moveEntryToTrash(entry)
                        entryToDelete = null
                    },
                    modifier = Modifier.testTag("confirm_send_to_trash_button")
                ) { Text("Enviar a papelera") }
            },
            dismissButton = {
                TextButton(onClick = { entryToDelete = null }) { Text("Cancelar") }
            }
        )
    }

    trashedToPurge?.takeIf { isEffectiveAdmin }?.let { item ->
        AlertDialog(
            onDismissRequest = { trashedToPurge = null },
            title = { Text("Eliminar definitivamente") },
            text = {
                Text(
                    "¿Seguro que deseas eliminar de forma permanente el registro de ${item.fullName}? Esta acción no se puede deshacer."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.permanentlyDeleteFromTrash(item)
                        trashedToPurge = null
                    },
                    modifier = Modifier.testTag("confirm_purge_trash_button")
                ) { Text("Eliminar definitivo") }
            },
            dismissButton = {
                TextButton(onClick = { trashedToPurge = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun EntriesDashboardContent(
    profile: UserProfile,
    isEffectiveAdmin: Boolean,
    canCreateEntries: Boolean,
    canSendToTrash: Boolean,
    canUseFullHistory: Boolean,
    isSimulatingOperator: Boolean,
    showEnableNotificationsBanner: Boolean,
    onRequestNotifications: () -> Unit,
    entriesState: UiState<List<AccessEntry>>,
    syncStatus: SyncStatusState,
    filterState: DashboardFilterState,
    busyEntryId: String?,
    gridColumns: Int,
    isWideMetrics: Boolean,
    onSearchChange: (String) -> Unit,
    onStatusFilterChange: (EntryStatusFilter) -> Unit,
    onDateScopeChange: (DateScopeFilter) -> Unit,
    onTimeSlotChange: (TimeSlotFilter) -> Unit,
    onOpenEmailImport: () -> Unit,
    onOpenCreateEntry: () -> Unit,
    onOpenExcelExport: () -> Unit,
    onCheckInNow: (AccessEntry, PlateImageAttachment?) -> Unit,
    onCheckOutNow: (AccessEntry) -> Unit,
    onAttachPlateImage: (AccessEntry, PlateImageAttachment) -> Unit,
    onMediaError: (String) -> Unit,
    onEditEntry: (AccessEntry) -> Unit,
    onDeleteEntry: (AccessEntry) -> Unit,
    onRestoreAdminMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val allEntries = (entriesState as? UiState.Success)?.data.orEmpty()
    val filteredEntries = remember(allEntries, filterState) {
        val q = filterState.searchQuery.trim().lowercase()
        allEntries.filter { entry ->
            val matchesQuery = q.isEmpty() ||
                entry.rank.lowercase().contains(q) ||
                entry.fullName.lowercase().contains(q) ||
                entry.documentNumber.lowercase().contains(q) ||
                entry.plate.lowercase().contains(q) ||
                entry.destinationUnit.lowercase().contains(q) ||
                entry.reason.lowercase().contains(q)
            val matchesStatus = when (filterState.statusFilter) {
                EntryStatusFilter.ALL -> true
                EntryStatusFilter.PENDING -> entry.parsedStatus == EntryStatus.PENDIENTE
                EntryStatusFilter.CHECKED_IN -> entry.parsedStatus == EntryStatus.INGRESO
                EntryStatusFilter.CHECKED_OUT -> entry.parsedStatus == EntryStatus.SALIDA
            }
            val matchesTime = DateTimeUtils.matchesTimeSlot(
                checkInAt = entry.checkInAt,
                createdAt = entry.createdAt,
                expectedTime = entry.expectedTime,
                filter = filterState.timeSlot
            )
            matchesQuery && matchesStatus && matchesTime
        }
    }

    val pendingCount = allEntries.count { it.parsedStatus == EntryStatus.PENDIENTE }
    val checkedInCount = allEntries.count { it.parsedStatus == EntryStatus.INGRESO }
    val checkedOutCount = allEntries.count { it.parsedStatus == EntryStatus.SALIDA }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumns),
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 1120.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!syncStatus.isOnline || syncStatus.pendingSyncIds.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val isOffline = !syncStatus.isOnline
                    val pendingCount = syncStatus.pendingSyncIds.size
                    Surface(
                        color = if (isOffline) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("offline_sync_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isOffline) Icons.Filled.CloudOff else Icons.Filled.CloudSync,
                                contentDescription = null,
                                tint = if (isOffline) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                modifier = Modifier.size(22.dp)
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = if (isOffline) {
                                        "Modo Offline activo (Sin conexión en guardia)"
                                    } else {
                                        "Sincronizando cambios con la nube..."
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isOffline) {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    }
                                )
                                Text(
                                    text = buildString {
                                        if (isOffline) {
                                            append("Puedes consultar la caché local, marcar ingresos/salidas y adjuntar placas. ")
                                        }
                                        if (pendingCount > 0) {
                                            append("$pendingCount registro(s) pendiente(s) de sincronizar al volver el internet.")
                                        } else {
                                            append("Los cambios quedarán guardados en el dispositivo.")
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isOffline) {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (showEnableNotificationsBanner) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.NotificationsActive,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = if (isEffectiveAdmin) {
                                        "Activa las notificaciones para recibir alertas de nuevos ingresos y salidas."
                                    } else {
                                        "Activa las notificaciones para recibir alertas de nuevos ingresos."
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            TextButton(
                                onClick = onRequestNotifications,
                                modifier = Modifier.testTag("enable_notifications_button")
                            ) {
                                Text("Activar")
                            }
                        }
                    }
                }
            }

            if (isSimulatingOperator) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Badge,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    text = "Simulando vista de Operador: solo ingresos de hoy y marcar llegada.",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            TextButton(onClick = onRestoreAdminMode) {
                                Text("Salir")
                            }
                        }
                    }
                }
            }

            // Metrics Summary Header (clickable quick filters aligned with horizontal chips)
            item(span = { GridItemSpan(maxLineSpan) }) {
                val totalLabel = if (canUseFullHistory && filterState.dateScope == DateScopeFilter.FULL_HISTORY) {
                    "Total"
                } else {
                    "Hoy"
                }
                val toggleStatus: (EntryStatusFilter) -> Unit = { target ->
                    onStatusFilterChange(
                        if (filterState.statusFilter == target) EntryStatusFilter.ALL else target
                    )
                }
                if (isWideMetrics) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SummaryMetricCard(
                            title = "Pendientes",
                            value = pendingCount.toString(),
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            selected = filterState.statusFilter == EntryStatusFilter.PENDING,
                            onClick = { toggleStatus(EntryStatusFilter.PENDING) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("kpi_pending_card")
                        )
                        SummaryMetricCard(
                            title = "En sitio",
                            value = checkedInCount.toString(),
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selected = filterState.statusFilter == EntryStatusFilter.CHECKED_IN,
                            onClick = { toggleStatus(EntryStatusFilter.CHECKED_IN) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("kpi_onsite_card")
                        )
                        SummaryMetricCard(
                            title = "Salieron",
                            value = checkedOutCount.toString(),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selected = filterState.statusFilter == EntryStatusFilter.CHECKED_OUT,
                            onClick = { toggleStatus(EntryStatusFilter.CHECKED_OUT) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("kpi_exited_card")
                        )
                        SummaryMetricCard(
                            title = totalLabel,
                            value = allEntries.size.toString(),
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            selected = filterState.statusFilter == EntryStatusFilter.ALL,
                            onClick = { onStatusFilterChange(EntryStatusFilter.ALL) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("kpi_total_card")
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SummaryMetricCard(
                                title = "Pendientes",
                                value = pendingCount.toString(),
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                selected = filterState.statusFilter == EntryStatusFilter.PENDING,
                                onClick = { toggleStatus(EntryStatusFilter.PENDING) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("kpi_pending_card")
                            )
                            SummaryMetricCard(
                                title = "En sitio",
                                value = checkedInCount.toString(),
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selected = filterState.statusFilter == EntryStatusFilter.CHECKED_IN,
                                onClick = { toggleStatus(EntryStatusFilter.CHECKED_IN) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("kpi_onsite_card")
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SummaryMetricCard(
                                title = "Salieron",
                                value = checkedOutCount.toString(),
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selected = filterState.statusFilter == EntryStatusFilter.CHECKED_OUT,
                                onClick = { toggleStatus(EntryStatusFilter.CHECKED_OUT) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("kpi_exited_card")
                            )
                            SummaryMetricCard(
                                title = totalLabel,
                                value = allEntries.size.toString(),
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                selected = filterState.statusFilter == EntryStatusFilter.ALL,
                                onClick = { onStatusFilterChange(EntryStatusFilter.ALL) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("kpi_total_card")
                            )
                        }
                    }
                }
            }

            // Quick action bar (Create for Admin & Diamante Alma; Excel strictly for Admin)
            if (canCreateEntries || isEffectiveAdmin) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (canCreateEntries) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onOpenEmailImport,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("open_email_import_button"),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Email,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Importar correo")
                                }
                                Button(
                                    onClick = onOpenCreateEntry,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("open_manual_create_button"),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Carga manual")
                                }
                            }
                        }

                        if (isEffectiveAdmin) {
                            OutlinedButton(
                                onClick = onOpenExcelExport,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("open_excel_export_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.TableView,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Descargar Excel (24h / 7 días)")
                            }
                        }
                    }
                }
            }

            // Search bar
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = filterState.searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text("Buscar por grado, nombre, cédula, placa, motivo o dependencia...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (filterState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchChange("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Limpiar búsqueda")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_entries_input")
                )
            }

            // Status & Date Scope Filter Chips
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        EntryStatusFilter.entries.forEach { stFilter ->
                            val isOnSiteChip = stFilter == EntryStatusFilter.CHECKED_IN
                            val isSelected = filterState.statusFilter == stFilter
                            FilterChip(
                                selected = isSelected,
                                onClick = { onStatusFilterChange(stFilter) },
                                leadingIcon = if (isOnSiteChip) {
                                    {
                                        Icon(
                                            imageVector = Icons.Filled.CheckCircle,
                                            contentDescription = null,
                                            tint = if (isSelected) Emerald900 else Emerald600,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else {
                                    null
                                },
                                label = {
                                    Text(
                                        if (isOnSiteChip) "${stFilter.label} ($checkedInCount)" else stFilter.label
                                    )
                                },
                                colors = if (isOnSiteChip) {
                                    FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Emerald100,
                                        selectedLabelColor = Emerald900
                                    )
                                } else {
                                    FilterChipDefaults.filterChipColors()
                                },
                                border = if (isOnSiteChip && !isSelected) {
                                    FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = false,
                                        borderColor = Emerald600.copy(alpha = 0.65f)
                                    )
                                } else {
                                    FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isSelected
                                    )
                                },
                                modifier = Modifier.testTag("filter_status_${stFilter.name}")
                            )
                        }
                        if (canUseFullHistory) {
                            DateScopeFilter.entries.forEach { scope ->
                                FilterChip(
                                    selected = filterState.dateScope == scope,
                                    onClick = { onDateScopeChange(scope) },
                                    label = { Text(scope.label) },
                                    modifier = Modifier.testTag("filter_scope_${scope.name}")
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TimeSlotFilter.entries.forEach { slot ->
                            FilterChip(
                                selected = filterState.timeSlot == slot,
                                onClick = { onTimeSlotChange(slot) },
                                label = { Text(slot.label) }
                            )
                        }
                    }
                }
            }

            when (entriesState) {
                is UiState.Loading -> {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }

                is UiState.Error -> {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = entriesState.message,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                is UiState.Success -> {
                    if (filteredEntries.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = if (allEntries.isEmpty()) {
                                            "Sin ingresos programados para hoy (${DateTimeUtils.formatIsoToDisplay(DateTimeUtils.todayIso())})"
                                        } else {
                                            "No hay resultados con los filtros actuales"
                                        },
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = if (canCreateEntries && allEntries.isEmpty()) {
                                            "Usa 'Importar correo' para pegar avisos del correo institucional o 'Carga manual' para crear un registro."
                                        } else {
                                            "Ajusta el buscador o cambia el filtro de estado / horario."
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        items(filteredEntries, key = { it.id }) { entry ->
                            EntryCard(
                                entry = entry,
                                canEdit = isEffectiveAdmin,
                                canSendToTrash = canSendToTrash,
                                isBusy = busyEntryId == entry.id,
                                isPendingSync = entry.id in syncStatus.pendingSyncIds,
                                onCheckInNow = { attachment -> onCheckInNow(entry, attachment) },
                                onCheckOutNow = { onCheckOutNow(entry) },
                                onAttachPlateImage = { attachment -> onAttachPlateImage(entry, attachment) },
                                onMediaError = onMediaError,
                                onEdit = { onEditEntry(entry) },
                                onDelete = { onDeleteEntry(entry) }
                            )
                        }
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(modifier = Modifier.height(72.dp))
            }
        }
    }
}

@Composable
private fun SummaryMetricCard(
    title: String,
    value: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    selected: Boolean = false,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = containerColor,
        shape = RoundedCornerShape(14.dp),
        border = if (selected) BorderStroke(2.dp, contentColor.copy(alpha = 0.75f)) else null,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = contentColor,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor
            )
        }
    }
}

@Composable
private fun PendingAccessScreen(
    profile: UserProfile,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .testTag("pending_approval_card")
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.HourglassTop,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .padding(14.dp)
                            .size(32.dp)
                    )
                }

                Text(
                    text = "Acceso pendiente de autorización",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "Tu cuenta (${profile.email}) fue registrada correctamente, pero no puede visualizar ingresos hasta que el Administrador te asigne un rol autorizado (Operador o Diamante Alma).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Cuando el Administrador habilite tu rol, esta pantalla se desbloqueará automáticamente en tiempo real.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedButton(
                    onClick = onSignOut,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("pending_sign_out_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cambiar cuenta / Cerrar sesión")
                }
            }
        }
    }
}

