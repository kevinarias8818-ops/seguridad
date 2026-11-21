package com.example.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_PLATE_IMAGE_DIM_PX = 640
private const val MAX_PLATE_DATA_URL_CHARS = 220_000
private const val DATA_URL_JPEG_PREFIX = "data:image/jpeg;base64,"

data class PlateImageAttachment(
    val fileName: String,
    val dataUrl: String
)

class RearCameraPictureContract : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent {
        return super.createIntent(context, input).apply {
            putExtra("android.intent.extras.CAMERA_FACING", 0)
            putExtra("android.intent.extras.LENS_FACING_BACK", 0)
            putExtra("android.intent.extra.USE_FRONT_CAMERA", false)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

object PlateImageUtils {
    fun createTempCameraImageUri(context: Context): Pair<Uri, String> {
        val dir = File(context.cacheDir, "plate_captures").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "placa_$stamp.jpg"
        val file = File(dir, fileName)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return uri to fileName
    }

    suspend fun processSelectedPlateImage(
        context: Context,
        uri: Uri,
        fallbackName: String = "placa.jpg"
    ): PlateImageAttachment? = withContext(Dispatchers.IO) {
        runCatching {
            val resolvedName = queryDisplayName(context, uri)
                ?.trim()
                ?.take(160)
                ?.ifBlank { fallbackName }
                ?: fallbackName.take(160)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val sampleSize = calculateInSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                MAX_PLATE_IMAGE_DIM_PX,
                MAX_PLATE_IMAGE_DIM_PX
            )
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: return@runCatching null

            val orientation = readExifOrientation(context, uri)
            val oriented = rotateBitmapIfNeeded(rawBitmap, orientation)
            if (oriented !== rawBitmap) rawBitmap.recycle()

            val scaled = scaleDownToMax(oriented, MAX_PLATE_IMAGE_DIM_PX)
            if (scaled !== oriented) oriented.recycle()

            var encodedDataUrl: String? = null
            for (quality in listOf(78, 64, 50, 36)) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                val candidate = "$DATA_URL_JPEG_PREFIX$b64"
                if (candidate.length <= MAX_PLATE_DATA_URL_CHARS) {
                    encodedDataUrl = candidate
                    break
                }
            }
            scaled.recycle()

            val finalUrl = encodedDataUrl ?: return@runCatching null
            PlateImageAttachment(
                fileName = resolvedName,
                dataUrl = finalUrl
            )
        }.getOrNull()
    }

    fun decodePlateImageBitmap(dataUrl: String?): ImageBitmap? {
        if (dataUrl.isNullOrBlank()) return null
        val commaIdx = dataUrl.indexOf(',')
        val base64Part = if (commaIdx >= 0) dataUrl.substring(commaIdx + 1) else dataUrl
        return runCatching {
            val bytes = Base64.decode(base64Part, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.lastPathSegment
        }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        }.getOrNull()
    }

    private fun readExifOrientation(context: Context, uri: Uri): Int {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    }

    private fun rotateBitmapIfNeeded(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleDownToMax(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / maxOf(w, h).toFloat()
        val newW = (w * ratio).toInt().coerceAtLeast(1)
        val newH = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}

@Composable
fun rememberPlateImagePickerLauncher(
    onImageSelected: (PlateImageAttachment) -> Unit,
    onError: (String) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSourceDialog by remember { mutableStateOf(false) }
    var pendingCameraCapture by remember { mutableStateOf<Pair<Uri, String>?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val attachment = PlateImageUtils.processSelectedPlateImage(context, uri, "placa_adjunta.jpg")
                if (attachment != null) {
                    onImageSelected(attachment)
                } else {
                    onError("No se pudo procesar la imagen seleccionada (prueba con otro archivo).")
                }
            }
        }
    }

    val rearCameraLauncher = rememberLauncherForActivityResult(
        contract = RearCameraPictureContract()
    ) { captured: Boolean ->
        val captureInfo = pendingCameraCapture
        pendingCameraCapture = null
        if (captured && captureInfo != null) {
            scope.launch {
                val attachment = PlateImageUtils.processSelectedPlateImage(
                    context = context,
                    uri = captureInfo.first,
                    fallbackName = captureInfo.second
                )
                if (attachment != null) {
                    onImageSelected(attachment)
                } else {
                    onError("No se pudo procesar la fotografía de la cámara.")
                }
            }
        }
    }

    if (showSourceDialog) {
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            title = { Text("Adjuntar imagen de la placa") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Selecciona cómo deseas capturar o adjuntar la fotografía de la placa vehicular:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            showSourceDialog = false
                            try {
                                val capture = PlateImageUtils.createTempCameraImageUri(context)
                                pendingCameraCapture = capture
                                rearCameraLauncher.launch(capture.first)
                            } catch (_: ActivityNotFoundException) {
                                pendingCameraCapture = null
                                onError("No hay una aplicación de cámara disponible en este dispositivo.")
                            } catch (_: Exception) {
                                pendingCameraCapture = null
                                onError("No se pudo abrir la cámara trasera.")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("plate_source_camera_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PhotoCamera,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Tomar foto (cámara trasera)")
                    }
                    OutlinedButton(
                        onClick = {
                            showSourceDialog = false
                            try {
                                filePickerLauncher.launch("image/*")
                            } catch (_: Exception) {
                                onError("No se pudo abrir el selector de imágenes.")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("plate_source_gallery_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PhotoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Elegir archivo o galería (image/*)")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSourceDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    return { showSourceDialog = true }
}

@Composable
fun PlateImageAttachmentPreviewRow(
    fileName: String,
    dataUrl: String,
    subtitle: String? = null,
    onRemove: (() -> Unit)? = null,
    onReplace: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(dataUrl) { PlateImageUtils.decodePlateImageBitmap(dataUrl) }
    var showFullPreview by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(enabled = bitmap != null) { showFullPreview = true },
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Vista previa placa $fileName",
                        modifier = Modifier.size(56.dp),
                        contentScale = ContentScale.Crop
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ZoomIn,
                            contentDescription = "Ampliar imagen",
                            tint = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .padding(2.dp)
                                .size(12.dp)
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Filled.PhotoCamera,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = fileName.ifBlank { "placa_vehiculo.jpg" },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle ?: "Foto de placa adjunta (toca para ampliar)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (onReplace != null) {
                IconButton(
                    onClick = onReplace,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PhotoCamera,
                        contentDescription = "Cambiar imagen de placa",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (onRemove != null) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Quitar imagen adjunta",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    if (showFullPreview && bitmap != null) {
        AlertDialog(
            onDismissRequest = { showFullPreview = false },
            title = {
                Text(
                    text = fileName.ifBlank { "Imagen de placa" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Imagen ampliada de la placa",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit
                )
            },
            confirmButton = {
                TextButton(onClick = { showFullPreview = false }) {
                    Text("Cerrar")
                }
            }
        )
    }
}
