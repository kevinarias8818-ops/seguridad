package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.UiState
import com.example.model.UserProfile
import com.example.model.UserRole
import com.example.ui.theme.Amber100
import com.example.ui.theme.Amber900
import com.example.ui.theme.Sky100
import com.example.ui.theme.Sky900

@Composable
fun UsersManagementScreen(
    currentUserId: String,
    usersState: UiState<List<UserProfile>>,
    onUpdateUserRole: (UserProfile, UserRole, Boolean) -> Unit,
    onDeleteUser: (UserProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    var userToDelete by remember { mutableStateOf<UserProfile?>(null) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 900.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Autorización Manual de Usuarios",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "Las cuentas nuevas ingresan sin acceso (Pendiente). Solo el Administrador puede otorgar los roles Operador, Diamante Alma (registra ingresos y envía a papelera) o Administrador.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            when (usersState) {
                is UiState.Loading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }

                is UiState.Error -> {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = usersState.message,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                is UiState.Success -> {
                    items(usersState.data, key = { it.userId }) { user ->
                        val isSelf = user.userId == currentUserId
                        val role = user.parsedRole
                        val isPending = role == UserRole.PENDING

                        val badgeColor = when (role) {
                            UserRole.PENDING -> Amber100
                            UserRole.ADMIN -> MaterialTheme.colorScheme.primaryContainer
                            UserRole.DIAMANTE_ALMA -> Sky100
                            UserRole.OPERATOR -> MaterialTheme.colorScheme.surfaceVariant
                        }
                        val badgeTextColor = when (role) {
                            UserRole.PENDING -> Amber900
                            UserRole.DIAMANTE_ALMA -> Sky900
                            else -> MaterialTheme.colorScheme.onSurface
                        }

                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            border = if (isPending) {
                                BorderStroke(1.5.dp, MaterialTheme.colorScheme.tertiary)
                            } else {
                                null
                            },
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("user_card_${user.userId}")
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (isSelf) "${user.displayName} (Tú)" else user.displayName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = user.email,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Surface(
                                        color = badgeColor,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = when (role) {
                                                    UserRole.PENDING -> Icons.Filled.HourglassTop
                                                    UserRole.ADMIN -> Icons.Filled.AdminPanelSettings
                                                    UserRole.DIAMANTE_ALMA -> Icons.Filled.Diamond
                                                    UserRole.OPERATOR -> Icons.Filled.Badge
                                                },
                                                contentDescription = null,
                                                tint = badgeTextColor,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = if (isPending) "Pendiente" else role.label,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = badgeTextColor
                                            )
                                        }
                                    }
                                }

                                if (isPending && !isSelf) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    onUpdateUserRole(user, UserRole.OPERATOR, true)
                                                },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(48.dp)
                                                    .testTag("approve_operator_${user.userId}"),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.CheckCircle,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Operador")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    onUpdateUserRole(user, UserRole.DIAMANTE_ALMA, true)
                                                },
                                                modifier = Modifier
                                                    .weight(1.1f)
                                                    .height(48.dp)
                                                    .testTag("approve_diamante_${user.userId}"),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Diamond,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Diamante Alma")
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                onUpdateUserRole(user, UserRole.ADMIN, true)
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                                .testTag("approve_admin_${user.userId}"),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.AdminPanelSettings,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Hacer Administrador")
                                        }
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            FilterChip(
                                                selected = role == UserRole.OPERATOR,
                                                onClick = {
                                                    if (!isSelf && role != UserRole.OPERATOR) {
                                                        onUpdateUserRole(user, UserRole.OPERATOR, true)
                                                    }
                                                },
                                                enabled = !isSelf,
                                                label = { Text("Operador") },
                                                modifier = Modifier.testTag("chip_operator_${user.userId}")
                                            )
                                            FilterChip(
                                                selected = role == UserRole.DIAMANTE_ALMA,
                                                onClick = {
                                                    if (!isSelf && role != UserRole.DIAMANTE_ALMA) {
                                                        onUpdateUserRole(user, UserRole.DIAMANTE_ALMA, true)
                                                    }
                                                },
                                                enabled = !isSelf,
                                                label = { Text("Diamante Alma") },
                                                modifier = Modifier.testTag("chip_diamante_${user.userId}")
                                            )
                                            FilterChip(
                                                selected = role == UserRole.ADMIN,
                                                onClick = {
                                                    if (!isSelf && role != UserRole.ADMIN) {
                                                        onUpdateUserRole(user, UserRole.ADMIN, true)
                                                    }
                                                },
                                                enabled = !isSelf,
                                                label = { Text("Administrador") },
                                                modifier = Modifier.testTag("chip_admin_${user.userId}")
                                            )
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (user.active) "Cuenta activa" else "Sin acceso",
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Switch(
                                                checked = user.active,
                                                enabled = !isSelf,
                                                onCheckedChange = { newActive ->
                                                    val updatedRole = if (!newActive) {
                                                        UserRole.PENDING
                                                    } else if (role == UserRole.PENDING) {
                                                        UserRole.OPERATOR
                                                    } else {
                                                        role
                                                    }
                                                    onUpdateUserRole(user, updatedRole, newActive)
                                                }
                                            )
                                        }
                                    }
                                }

                                if (!isSelf) {
                                    OutlinedButton(
                                        onClick = { userToDelete = user },
                                        border = BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                                        ),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("delete_user_${user.userId}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.DeleteOutline,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Eliminar usuario (permite reingreso)")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    userToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { userToDelete = null },
            title = { Text("Eliminar usuario") },
            text = {
                Text(
                    "¿Eliminar a ${target.displayName} (${target.email}) de la lista de usuarios?\n\nEsto no bloquea su cuenta de Google: si vuelve a iniciar sesión con el mismo correo podrá ingresar nuevamente para solicitar acceso."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteUser(target)
                        userToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_delete_user_button")
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { userToDelete = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}
