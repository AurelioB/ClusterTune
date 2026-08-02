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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.aure.clustertune.ui.designsystem.component.CtIcon

private const val KOFI_URL = "https://ko-fi.com/J3J518XVKR"
private const val SUPPORT_MESSAGE =
    "ClusterTune is built and maintained independently. If it helps you tune your device, consider supporting my work on Ko-fi."

@Composable
internal fun SupportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val qrCodeSize = if (isLandscape) 256.dp else 220.dp
    val qrCode = remember { generateQrCodeBitmap(KOFI_URL, 520).asImageBitmap() }
    val openKofi: () -> Unit = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL))) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CtIcon(
                    symbol = "favorite",
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    size = 28.dp,
                )
                Text(
                    text = "Support ClusterTune",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            TextButton(onClick = onBack) { Text("Done") }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (isLandscape) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    SupportQrCode(qrCode, qrCodeSize)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        SupportMessage(textAlign = TextAlign.Start)
                        SupportUrl(onClick = openKofi, textAlign = TextAlign.Start)
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SupportMessage(textAlign = TextAlign.Center)
                    SupportQrCode(qrCode, qrCodeSize)
                    SupportUrl(onClick = openKofi, textAlign = TextAlign.Center)
                }
            }
        }
        TextButton(
            onClick = openKofi,
            modifier = Modifier.align(Alignment.End),
        ) { Text("Open Ko-fi") }
    }
}

@Composable
private fun SupportMessage(textAlign: TextAlign) {
    Text(
        text = SUPPORT_MESSAGE,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
    )
}

@Composable
private fun SupportUrl(onClick: () -> Unit, textAlign: TextAlign) {
    Text(
        text = KOFI_URL,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        textAlign = textAlign,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SupportQrCode(qrCode: ImageBitmap, size: Dp) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
        Image(
            bitmap = qrCode,
            contentDescription = "Ko-fi donation QR code",
            modifier = Modifier
                .size(size)
                .padding(12.dp),
        )
    }
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
