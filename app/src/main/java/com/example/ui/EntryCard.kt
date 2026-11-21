package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.AccessEntry
import com.example.model.EntrySource
import com.example.model.EntryStatus
import com.example.ui.theme.Amber100
import com.example.ui.theme.Amber900
import com.example.ui.theme.Emerald100
import com.example.ui.theme.Emerald600
import com.example.ui.theme.Emerald900
import com.example.ui.theme.Sky100
import com.example.ui.theme.Sky600
import com.example.ui.theme.Sky900
import com.example.util.DateTimeUtils
import com.example.util.PlateImageAttachment
import com.example.util.PlateImageAttachmentPreviewRow
import com.example.util.rememberPlateImagePickerLauncher

@Composable
fun EntryCard(
    entry: AccessEntry,
    isAdminActionsEnabled: Boolean,
    isBusy: Boolean,
    onCheckInNow: (PlateImageAttachment?) -> Unit,
    onCheckOutNow: () -> Unit,
    onAttachPlateImage: (PlateImageAttachment) -> Unit,
    onMediaError: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val parsedStatus = entry.parsedStatus
    val isPending = parsedStatus == EntryStatus.PENDIENTE
    val isCheckedIn = parsedStatus == EntryStatus.INGRESO
    val isCheckedOut = parsedStatus == EntryStatus.SALIDA
    val hasEntered = isCheckedIn || isCheckedOut

    var stagedAttachment by remember(entry.id) { mutableStateOf<PlateImageAttachment?>(null) }
    LaunchedEffect(entry.status, entry.plateImageData) {
        if (entry.parsedStatus != EntryStatus.PENDIENTE || entry.plateImageData.isNotBlank()) {
            stagedAttachment = null
        }
    }

    val openPlatePicker = rememberPlateImagePickerLauncher(
        onImageSelected = { attachment ->
            if (isPending) {
                stagedAttachment = attachment
            } else if (isCheckedIn) {
                onAttachPlateImage(attachment)
            }
        },
        onError = onMediaError
    )

    val displayPlateFileName = stagedAttachment?.fileName ?: entry.plateImageName
    val displayPlateDataUrl = stagedAttachment?.dataUrl ?: entry.plateImageData

    val borderColor = when (parsedStatus) {
        EntryStatus.PENDIENTE -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)
        EntryStatus.INGRESO -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        EntryStatus.SALIDA -> Sky600.copy(alpha = 0.45f)
    }

    val badgeContainerColor = when (parsedStatus) {
        EntryStatus.PENDIENTE -> Amber100
        EntryStatus.INGRESO -> Emerald100
        EntryStatus.SALIDA -> Sky100
    }
    val badgeContentColor = when (parsedStatus) {
        EntryStatus.PENDIENTE -> Amber900
        EntryStatus.INGRESO -> Emerald900
        EntryStatus.SALIDA -> Sky900
    }
    val badgeIcon = when (parsedStatus) {
        EntryStatus.PENDIENTE -> Icons.Filled.Schedule
        EntryStatus.INGRESO -> Icons.Filled.CheckCircle
        EntryStatus.SALIDA -> Icons.AutoMirrored.Filled.Logout
    }
    val badgeText = when (parsedStatus) {
        EntryStatus.PENDIENTE -> "PENDIENTE"
        EntryStatus.INGRESO -> "YA INGRESÓ"
        EntryStatus.SALIDA -> "YA SALIÓ"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("entry_card_${entry.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.5.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Status pill + Visitor type + Admin buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = badgeContainerColor,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = badgeIcon,
                                contentDescription = null,
                                tint = badgeContentColor,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = badgeContentColor
                            )
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = entry.parsedVisitorType.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }

                if (isAdminActionsEnabled) {
                    Row {
                        IconButton(
                            onClick = onEdit,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("edit_entry_${entry.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "Editar ingreso",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("delete_entry_${entry.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Eliminar ingreso",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Grado / Nombre
            Text(
                text = entry.displayTitleWithRank,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Cédula + Placa
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Badge,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "C.C. ${entry.documentNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (entry.plate.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DirectionsCar,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = entry.plate,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            // Dependencia + Motivo
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Apartment,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp)
                    )
                    Text(
                        text = "Dependencia: ${entry.destinationUnit}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (entry.reason.isNotBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Assignment,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Motivo: ${entry.reason}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Hora programada vs Hora real de ingreso vs Hora de salida
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1.35f)) {
                        Text(
                            text = "Programada",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AccessTime,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "${DateTimeUtils.formatIsoToDisplay(entry.entryDate)} · ${entry.expectedTime.ifBlank { "Sin hora" }}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(0.8f)
                    ) {
                        Text(
                            text = "Ingreso",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (hasEntered) DateTimeUtils.formatTime(entry.checkInAt) else "Pendiente",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (hasEntered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.weight(0.8f)
                    ) {
                        Text(
                            text = "Salida",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = when {
                                isCheckedOut -> DateTimeUtils.formatTime(entry.checkOutAt)
                                isCheckedIn -> "En sitio"
                                else -> "--:--"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                isCheckedOut -> Sky600
                                isCheckedIn -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            if (entry.notes.isNotBlank()) {
                Text(
                    text = "Observaciones: ${entry.notes}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (entry.parsedSource == EntrySource.CORREO_INSTITUCIONAL) {
                Text(
                    text = "Origen: Correo institucional · Cargado por ${entry.createdByName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (displayPlateDataUrl.isNotBlank()) {
                PlateImageAttachmentPreviewRow(
                    fileName = displayPlateFileName,
                    dataUrl = displayPlateDataUrl,
                    subtitle = if (stagedAttachment != null) {
                        "Lista para guardar al marcar ingreso"
                    } else {
                        "Foto de placa adjunta (toca para ampliar)"
                    },
                    onReplace = if (isPending || isCheckedIn) openPlatePicker else null,
                    onRemove = if (stagedAttachment != null) {
                        { stagedAttachment = null }
                    } else {
                        null
                    }
                )
            }

            // Action area: Check-in button + attach plate photo, Check-out button, or complete audit trail
            if (isPending) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { onCheckInNow(stagedAttachment) },
                        enabled = !isBusy,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("check_in_button_${entry.id}"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Emerald600,
                            contentColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.surface,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Registrando...", style = MaterialTheme.typography.labelLarge)
                        } else {
                            Icon(
                                imageVector = Icons.Filled.HowToReg,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Marcar ingreso ahora",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    FilledTonalIconButton(
                        onClick = openPlatePicker,
                        enabled = !isBusy,
                        modifier = Modifier
                            .size(50.dp)
                            .testTag("card_attach_plate_${entry.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AddAPhoto,
                            contentDescription = "Adjuntar imagen de la placa"
                        )
                    }
                }
            } else if (isCheckedIn) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Ingreso efectivo: ${DateTimeUtils.formatDateTime(entry.checkInAt)}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Registrado por: ${entry.checkInByName ?: "Personal en turno"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            if (displayPlateDataUrl.isBlank()) {
                                FilledTonalIconButton(
                                    onClick = openPlatePicker,
                                    enabled = !isBusy,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("card_attach_plate_${entry.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.AddAPhoto,
                                        contentDescription = "Adjuntar imagen de la placa"
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Ingreso completado",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onCheckOutNow,
                        enabled = !isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("check_out_button_${entry.id}"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Sky600,
                            contentColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.surface,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Registrando salida...", style = MaterialTheme.typography.labelLarge)
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Marcar salida ahora",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                Surface(
                    color = Sky100.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "Ingreso: ${DateTimeUtils.formatDateTime(entry.checkInAt)} · ${entry.checkInByName ?: "Personal en turno"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Sky900
                            )
                            Text(
                                text = "Salida efectiva: ${DateTimeUtils.formatDateTime(entry.checkOutAt)}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = Sky900
                            )
                            Text(
                                text = "Salida marcada por: ${entry.checkOutByName ?: "Personal en turno"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Sky900
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Salida completada",
                            tint = Sky600
                        )
                    }
                }
            }
        }
    }
}
