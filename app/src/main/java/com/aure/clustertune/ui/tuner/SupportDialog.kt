package com.aure.clustertune.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.aure.clustertune.ui.designsystem.component.CtIcon

private const val KOFI_URL = "https://ko-fi.com/J3J518XVKR"
private const val SUPPORT_MESSAGE =
    "ClusterTune is built and maintained independently. If it helps you tune your device, consider supporting my work on Ko-fi."

@Composable
internal fun SupportClusterTuneDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val qrCode = remember { generateQrCodeBitmap(KOFI_URL, 520).asImageBitmap() }
    val openKofi: () -> Unit = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL))) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            CtIcon(
                symbol = "favorite",
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                size = 28.dp,
            )
        },
        title = {
            Text(
                text = "Support ClusterTune",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = SUPPORT_MESSAGE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
                    Image(
                        bitmap = qrCode,
                        contentDescription = "Ko-fi donation QR code",
                        modifier = Modifier
                            .size(196.dp)
                            .padding(12.dp),
                    )
                }
                Text(
                    text = KOFI_URL,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable(onClick = openKofi),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = openKofi) { Text("Open Ko-fi") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun generateQrCodeBitmap(content: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until size) {
            for (y in 0 until size) {
                setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
    }
}
