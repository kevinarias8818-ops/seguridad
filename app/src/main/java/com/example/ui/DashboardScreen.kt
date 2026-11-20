package com.example.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
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
import com.example.auth.GoogleAuthHelper
import com.example.model.AccessEntry
import com.example.model.EntryStatus
import com.example.model.UiState
import com.example.model.UserProfile
import com.example.model.UserRole
import com.example.util.DateTimeUtils
import com.example.util.TimeSlotFilter
import com.google.firebase.auth.FirebaseUser

private enum class AppTab(val label: String) {
    ENTRIES("Ingresos"),
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
    val usersState by viewModel.allUsersState.collectAsStateWithLifecycle()
    val filterState by viewModel.filterState.collectAsStateWithLifecycle()
    val simulateOperator by viewModel.simulateOperatorMode.collectAsStateWithLifecycle()
    val busyEntryId by viewModel.busyEntryId.collectAsStateWithLifecycle()
    val isSubmitting by viewModel.isSubmitting.collectAsStateWithLifecycle()

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
    var entryToDelete by remember { mutableStateOf<AccessEntry?>(null) }

    val profile = (profileState as? UiState.Success)?.data
    val isRealAdmin = profile?.parsedRole == UserRole.ADMIN
    val isEffectiveAdmin = isRealAdmin && !simulateOperator
    val isPendingApproval = profile != null &&
        (profile.parsedRole == UserRole.PENDING || !profile.active)
    val pendingUsersCount = (usersState as? UiState.Success)?.data
        ?.count { it.parsedRole == UserRole.PENDING } ?: 0

    if (currentTab != AppTab.ENTRIES) {
        BackHandler { currentTab = AppTab.ENTRIES }
    }

    LaunchedEffect(isEffectiveAdmin) {
        if (!isEffectiveAdmin && currentTab == AppTab.USERS) {
            currentTab = AppTab.ENTRIES
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
                                    else -> "Operador"
                                }
                                Text(
                                    text = "${profile.displayName} · $roleText",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isPendingApproval) {
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
                                GoogleAuthHelper.signOut(credentialManager, scope)
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
                            selected = currentTab == AppTab.USERS,
                            onClick = { currentTab = AppTab.USERS },
                            icon = { Icon(Icons.Filled.Group, contentDescription = null) },
                            label = {
                                Text(
                                    if (pendingUsersCount > 0) "Usuarios ($pendingUsersCount)" else "Usuarios y Roles"
                                )
                            },
                            modifier = Modifier.testTag("nav_users_tab")
                        )
                    }
                }
            },
            floatingActionButton = {
                if (isEffectiveAdmin && !isPendingApproval && currentTab == AppTab.ENTRIES) {
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
                                isSimulatingOperator = isRealAdmin && simulateOperator,
                                entriesState = entriesState,
                                filterState = filterState,
                                busyEntryId = busyEntryId,
                                gridColumns = gridColumns,
                                onSearchChange = viewModel::updateSearchQuery,
                                onStatusFilterChange = viewModel::updateStatusFilter,
                                onDateScopeChange = viewModel::updateDateScope,
                                onTimeSlotChange = viewModel::updateTimeSlot,
                                onOpenEmailImport = { showEmailImportDialog = true },
                                onOpenCreateEntry = { showCreateDialog = true },
                                onCheckInNow = viewModel::markCheckInNow,
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

    if (showCreateDialog) {
        EntryEditorDialog(
            existingEntry = null,
            isSaving = isSubmitting,
            onDismiss = { if (!isSubmitting) showCreateDialog = false },
            onSaveNew = { draft ->
                viewModel.createEntry(draft) { showCreateDialog = false }
            },
            onSaveEdit = { _, _, _, _ -> }
        )
    }

    editingEntry?.let { entry ->
        EntryEditorDialog(
            existingEntry = entry,
            isSaving = isSubmitting,
            onDismiss = { if (!isSubmitting) editingEntry = null },
            onSaveNew = {},
            onSaveEdit = { entryId, draft, status, existing ->
                viewModel.updateEntry(entryId, draft, status, existing) { editingEntry = null }
            }
        )
    }

    if (showEmailImportDialog) {
        EmailImportDialog(
            isSaving = isSubmitting,
            onDismiss = { if (!isSubmitting) showEmailImportDialog = false },
            onImportBatch = { drafts ->
                viewModel.importEntriesBatch(drafts) { showEmailImportDialog = false }
            }
        )
    }

    entryToDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            title = { Text("Eliminar registro") },
            text = { Text("¿Seguro que deseas eliminar el ingreso de ${entry.fullName}?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteEntry(entry)
                        entryToDelete = null
                    }
                ) { Text("Eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { entryToDelete = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun EntriesDashboardContent(
    profile: UserProfile,
    isEffectiveAdmin: Boolean,
    isSimulatingOperator: Boolean,
    entriesState: UiState<List<AccessEntry>>,
    filterState: DashboardFilterState,
    busyEntryId: String?,
    gridColumns: Int,
    onSearchChange: (String) -> Unit,
    onStatusFilterChange: (EntryStatusFilter) -> Unit,
    onDateScopeChange: (DateScopeFilter) -> Unit,
    onTimeSlotChange: (TimeSlotFilter) -> Unit,
    onOpenEmailImport: () -> Unit,
    onOpenCreateEntry: () -> Unit,
    onCheckInNow: (AccessEntry) -> Unit,
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

            // Metrics Summary Header
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SummaryMetricCard(
                        title = "Pendientes",
                        value = pendingCount.toString(),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryMetricCard(
                        title = "Ya ingresaron",
                        value = checkedInCount.toString(),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryMetricCard(
                        title = if (isEffectiveAdmin && filterState.dateScope == DateScopeFilter.FULL_HISTORY) "Total" else "Hoy",
                        value = allEntries.size.toString(),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Admin quick action bar
            if (isEffectiveAdmin) {
                item(span = { GridItemSpan(maxLineSpan) }) {
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
                            FilterChip(
                                selected = filterState.statusFilter == stFilter,
                                onClick = { onStatusFilterChange(stFilter) },
                                label = { Text(stFilter.label) },
                                modifier = Modifier.testTag("filter_status_${stFilter.name}")
                            )
                        }
                        if (isEffectiveAdmin) {
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
                                        text = if (isEffectiveAdmin && allEntries.isEmpty()) {
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
                                isAdminActionsEnabled = isEffectiveAdmin,
                                isBusy = busyEntryId == entry.id,
                                onCheckInNow = { onCheckInNow(entry) },
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
    modifier: Modifier = Modifier
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(14.dp),
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
                    text = "Tu cuenta (${profile.email}) fue registrada correctamente, pero no puede visualizar ingresos hasta que el Administrador te asigne el rol de Operador.",
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

