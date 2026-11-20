package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.EntryDraft
import com.example.util.DateTimeUtils
import com.example.util.EmailParser

@Composable
fun EmailImportDialog(
    onDismiss: () -> Unit,
    onImportBatch: (List<EntryDraft>) -> Unit
) {
    var rawEmailText by remember { mutableStateOf("") }
    var targetDate by remember { mutableStateOf(DateTimeUtils.todayIso()) }
    var parsedEntries by remember { mutableStateOf<List<EntryDraft>>(emptyList()) }
    var parseAttempted by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.Filled.Email,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Importar desde correo",
                    style = MaterialTheme.typography.titleLarge
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Pega el contenido del correo institucional (bloques Grado/Nombre/Cédula/Dependencia/Motivo/Hora programada/Observaciones o filas separadas por |, ; o tabulador).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = targetDate,
                    onValueChange = { targetDate = it.take(10) },
                    label = { Text("Fecha asignada (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = rawEmailText,
                    onValueChange = {
                        rawEmailText = it
                        parseAttempted = false
                    },
                    label = { Text("Texto del correo institucional") },
                    placeholder = {
                        Text("Grado: ...\nNombre: ...\nCédula: ...\nDependencia: ...\nMotivo: ...\nHora programada: ...")
                    },
                    minLines = 5,
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("email_import_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            rawEmailText = EmailParser.SAMPLE_INSTITUTIONAL_EMAIL
                            parsedEntries = EmailParser.parseInstitutionalEmail(
                                rawEmailText,
                                targetDate.trim().ifBlank { DateTimeUtils.todayIso() }
                            )
                            parseAttempted = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("load_sample_email_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cargar ejemplo")
                    }

                    Button(
                        onClick = {
                            val date = targetDate.trim().ifBlank { DateTimeUtils.todayIso() }
                            parsedEntries = EmailParser.parseInstitutionalEmail(rawEmailText, date)
                            parseAttempted = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("parse_email_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Analizar texto")
                    }
                }

                if (parseAttempted && parsedEntries.isEmpty()) {
                    Text(
                        text = "No se detectaron registros válidos. Verifica que cada bloque incluya al menos 'Nombre:' o filas separadas por '|'.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (parsedEntries.isNotEmpty()) {
                    Text(
                        text = "Vista previa (${parsedEntries.size} registros detectados):",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    parsedEntries.forEach { draft ->
                        val title = if (draft.rank.isNotBlank()) "${draft.rank} ${draft.fullName}" else draft.fullName
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "$title · ${draft.visitorType.label}",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    text = "Cédula: ${draft.documentNumber} | Placa: ${draft.plate.ifBlank { "Peatonal" }} | Prog: ${draft.expectedTime.ifBlank { "S/H" }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = buildString {
                                        append(draft.destinationUnit)
                                        if (draft.reason.isNotBlank()) append(" · Motivo: ${draft.reason}")
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onImportBatch(parsedEntries) },
                enabled = parsedEntries.isNotEmpty() &&
                    Regex("""^\d{4}-\d{2}-\d{2}$""").matches(targetDate.trim()),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("confirm_email_import_button")
            ) {
                Text("Importar (${parsedEntries.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) {
                Text("Cancelar")
            }
        }
    )
}
