package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.model.AccessEntry
import com.example.model.EntryDraft
import com.example.model.EntrySource
import com.example.model.EntryStatus
import com.example.model.VisitorType
import com.example.ui.theme.Amber100
import com.example.ui.theme.Amber600
import com.example.ui.theme.Amber900
import com.example.util.DateTimeUtils
import com.example.util.EntryInputUtils
import com.example.util.PlateImageAttachmentPreviewRow
import com.example.util.rememberPlateImagePickerLauncher

@Composable
fun EntryEditorDialog(
    existingEntry: AccessEntry?,
    activeOnSiteEntries: List<AccessEntry> = emptyList(),
    isSaving: Boolean = false,
    onDismiss: () -> Unit,
    onSaveNew: (EntryDraft) -> Unit,
    onSaveEdit: (entryId: String, draft: EntryDraft, status: EntryStatus, existing: AccessEntry) -> Unit,
    onMediaError: (String) -> Unit = {}
) {
    val isEditing = existingEntry != null
    var rank by remember(existingEntry) { mutableStateOf(existingEntry?.rank.orEmpty()) }
    var fullName by remember(existingEntry) { mutableStateOf(existingEntry?.fullName.orEmpty()) }
    var documentNumber by remember(existingEntry) { mutableStateOf(existingEntry?.documentNumber.orEmpty()) }
    var plate by remember(existingEntry) {
        mutableStateOf(EntryInputUtils.formatPlateInput(existingEntry?.plate.orEmpty()))
    }
    var destinationUnit by remember(existingEntry) { mutableStateOf(existingEntry?.destinationUnit.orEmpty()) }
    var reason by remember(existingEntry) { mutableStateOf(existingEntry?.reason.orEmpty()) }
    var entryDate by remember(existingEntry) {
        mutableStateOf(existingEntry?.entryDate?.ifBlank { DateTimeUtils.todayIso() } ?: DateTimeUtils.todayIso())
    }
    var expectedTimeField by remember(existingEntry) {
        val initial = EntryInputUtils.normalizeExistingTime(existingEntry?.expectedTime.orEmpty())
        mutableStateOf(TextFieldValue(text = initial, selection = TextRange(initial.length)))
    }
    var visitorType by remember(existingEntry) {
        mutableStateOf(existingEntry?.parsedVisitorType ?: VisitorType.VISITANTE)
    }
    var status by remember(existingEntry) {
        mutableStateOf(existingEntry?.parsedStatus ?: EntryStatus.PENDIENTE)
    }
    var notes by remember(existingEntry) { mutableStateOf(existingEntry?.notes.orEmpty()) }
    var plateImageName by remember(existingEntry) { mutableStateOf(existingEntry?.plateImageName.orEmpty()) }
    var plateImageData by remember(existingEntry) { mutableStateOf(existingEntry?.plateImageData.orEmpty()) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var duplicateConfirmed by remember(existingEntry) { mutableStateOf(false) }

    val duplicateMatches = remember(activeOnSiteEntries, documentNumber, plate, existingEntry?.id) {
        EntryInputUtils.findActiveOnSiteDuplicates(
            activeEntries = activeOnSiteEntries,
            documentNumber = documentNumber,
            plate = plate,
            excludeEntryId = existingEntry?.id
        )
    }

    val openPlatePicker = rememberPlateImagePickerLauncher(
        onImageSelected = { attachment ->
            plateImageName = attachment.fileName
            plateImageData = attachment.dataUrl
            validationError = null
        },
        onError = { message ->
            validationError = message
            onMediaError(message)
        }
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isEditing) "Editar ingreso" else "Nuevo ingreso manual",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = rank,
                        onValueChange = { rank = it.take(40) },
                        label = { Text("Grado / Cargo") },
                        placeholder = { Text("Tte. / Ing.") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(0.42f)
                            .testTag("entry_rank_input")
                    )
                    OutlinedTextField(
                        value = fullName,
                        onValueChange = { fullName = it.take(120) },
                        label = { Text("Nombre completo *") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(0.58f)
                            .testTag("entry_name_input")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = documentNumber,
                        onValueChange = {
                            documentNumber = EntryInputUtils.formatDocumentNumberInput(it)
                            duplicateConfirmed = false
                            validationError = null
                        },
                        label = { Text("Cédula *") },
                        placeholder = { Text("Solo números") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier
                            .weight(0.9f)
                            .testTag("entry_doc_input")
                    )
                    OutlinedTextField(
                        value = plate,
                        onValueChange = {
                            plate = EntryInputUtils.formatPlateInput(it)
                            duplicateConfirmed = false
                            validationError = null
                        },
                        label = { Text("Placa") },
                        placeholder = { Text("ABC123") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            keyboardType = KeyboardType.Ascii,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier
                            .weight(0.95f)
                            .testTag("entry_plate_input")
                    )
                    FilledTonalIconButton(
                        onClick = openPlatePicker,
                        enabled = !isSaving,
                        modifier = Modifier
                            .size(50.dp)
                            .testTag("attach_plate_image_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AddAPhoto,
                            contentDescription = "Adjuntar imagen de la placa"
                        )
                    }
                }

                if (duplicateMatches.isNotEmpty()) {
                    Surface(
                        color = Amber100,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Amber600),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("duplicate_onsite_warning")
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Filled.WarningAmber,
                                contentDescription = null,
                                tint = Amber900,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = "Posible duplicado: ya está En sitio",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Amber900
                                )
                                duplicateMatches.take(2).forEach { match ->
                                    val reasonLabel = buildList {
                                        if (match.matchedByDocument) add("C.C. ${match.entry.documentNumber}")
                                        if (match.matchedByPlate) add("Placa ${match.entry.plate}")
                                    }.joinToString(" y ")
                                    Text(
                                        text = "${match.entry.displayTitleWithRank} ($reasonLabel) ingresó a ${match.entry.destinationUnit} y aún no marca salida.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Amber900
                                    )
                                }
                            }
                        }
                    }
                }

                if (plateImageData.isNotBlank()) {
                    PlateImageAttachmentPreviewRow(
                        fileName = plateImageName,
                        dataUrl = plateImageData,
                        subtitle = "Imagen de placa lista para guardar con el ingreso",
                        onReplace = openPlatePicker,
                        onRemove = {
                            plateImageName = ""
                            plateImageData = ""
                        }
                    )
                } else {
                    OutlinedButton(
                        onClick = openPlatePicker,
                        enabled = !isSaving,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("attach_plate_image_row_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AddAPhoto,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Adjuntar foto de placa (opcional)")
                    }
                }

                OutlinedTextField(
                    value = destinationUnit,
                    onValueChange = { destinationUnit = it.take(80) },
                    label = { Text("Dependencia / Unidad destino *") },
                    placeholder = { Text("Ej. Comandancia / Torre A - Apt 301") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("entry_unit_input")
                )

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(160) },
                    label = { Text("Motivo del ingreso") },
                    placeholder = { Text("Ej. Reunión, Mantenimiento, Relevo de turno") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("entry_reason_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = entryDate,
                        onValueChange = { entryDate = it.take(10) },
                        label = { Text("Fecha (YYYY-MM-DD)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = expectedTimeField,
                        onValueChange = { nextField ->
                            val formatted = EntryInputUtils.formatEntryTimeDigits(
                                previous = expectedTimeField.text,
                                rawNext = nextField.text
                            )
                            expectedTimeField = TextFieldValue(
                                text = formatted,
                                selection = TextRange(formatted.length)
                            )
                            validationError = null
                        },
                        label = { Text("Hora de ingreso") },
                        placeholder = { Text("08:30") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    val nowHm = DateTimeUtils.currentTimeHm()
                                    expectedTimeField = TextFieldValue(
                                        text = nowHm,
                                        selection = TextRange(nowHm.length)
                                    )
                                    validationError = null
                                },
                                modifier = Modifier.testTag("fill_current_time_button")
                            ) {
                                Text("Ahora", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("entry_time_input")
                    )
                }

                Text(
                    text = "Tipo de ingreso",
                    style = MaterialTheme.typography.labelLarge
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VisitorType.entries.forEach { type ->
                        FilterChip(
                            selected = visitorType == type,
                            onClick = { visitorType = type },
                            label = { Text(type.label) }
                        )
                    }
                }

                if (isEditing) {
                    Text(
                        text = "Estado",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        EntryStatus.entries.forEach { st ->
                            FilterChip(
                                selected = status == st,
                                onClick = { status = st },
                                label = { Text(st.label) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(300) },
                    label = { Text("Observaciones (opcional)") },
                    placeholder = { Text("Equipo, acompañantes o indicaciones de garita") },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("entry_notes_input")
                )

                if (validationError != null) {
                    Text(
                        text = validationError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanName = fullName.trim()
                    val cleanDoc = documentNumber.trim()
                    val cleanPlate = EntryInputUtils.formatPlateInput(plate)
                    val cleanUnit = destinationUnit.trim()
                    val cleanDate = entryDate.trim()
                    val cleanTime = expectedTimeField.text.trim()
                    when {
                        cleanName.length < 2 ->
                            validationError = "Ingresa un nombre de al menos 2 caracteres."
                        cleanDoc.isEmpty() ->
                            validationError = "Ingresa el número de cédula (solo números)."
                        cleanUnit.isEmpty() ->
                            validationError = "Ingresa la dependencia o unidad destino."
                        !Regex("""^\d{4}-\d{2}-\d{2}$""").matches(cleanDate) ->
                            validationError = "La fecha debe tener formato YYYY-MM-DD."
                        !EntryInputUtils.isValidEntryTime(cleanTime) ->
                            validationError = "La hora debe tener formato 24h HH:MM (ej. 08:30)."
                        !isEditing && duplicateMatches.isNotEmpty() && !duplicateConfirmed -> {
                            duplicateConfirmed = true
                            validationError = "Advertencia: ya existe un ingreso 'En sitio' con esa cédula o placa. Verifica o presiona 'Registrar de todos modos'."
                        }
                        else -> {
                            validationError = null
                            val draft = EntryDraft(
                                entryDate = cleanDate,
                                rank = rank.trim(),
                                fullName = cleanName,
                                documentNumber = cleanDoc,
                                plate = cleanPlate,
                                destinationUnit = cleanUnit,
                                reason = reason.trim(),
                                visitorType = visitorType,
                                expectedTime = cleanTime,
                                source = existingEntry?.parsedSource ?: EntrySource.MANUAL,
                                notes = notes.trim(),
                                plateImageName = if (plateImageData.isNotBlank()) plateImageName else "",
                                plateImageData = plateImageData
                            )
                            if (existingEntry != null) {
                                onSaveEdit(existingEntry.id, draft, status, existingEntry)
                            } else {
                                onSaveNew(draft)
                            }
                        }
                    }
                },
                enabled = !isSaving,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("save_entry_button")
            ) {
                Text(
                    when {
                        isSaving -> "Guardando..."
                        isEditing -> "Guardar cambios"
                        duplicateMatches.isNotEmpty() && duplicateConfirmed -> "Registrar de todos modos"
                        else -> "Registrar ingreso"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
                modifier = Modifier.height(48.dp)
            ) {
                Text("Cancelar")
            }
        }
    )
}
