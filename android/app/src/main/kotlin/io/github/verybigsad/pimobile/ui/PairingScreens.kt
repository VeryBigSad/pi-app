package io.github.verybigsad.pimobile.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.verybigsad.pimobile.state.PairingUiState

@Composable
fun PairingLandingScreen(onPair: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "π",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text("Pi Mobile", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "Your Mac stays in control",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Continue your real Pi sessions from Android without moving provider credentials, repositories, or execution off your Mac.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TrustRow("Passkey unlock", "No password fallback")
                TrustRow("End-to-end TLS", "Relay sees ciphertext only")
                TrustRow("Mac execution", "Secrets never reach Android")
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(
                onClick = onPair,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("Pair a Mac", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Scan the one-use QR shown by the Mac host",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrustRow(title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PairingFlowScreen(
    pairing: PairingUiState,
    onScanned: (String) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        when (pairing) {
            PairingUiState.AwaitingScan -> QrScanSurface(onScanned, onCancel)
            PairingUiState.Connecting -> {
                CircularProgressIndicator()
                Text("Connecting to your Mac…", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Provisional TLS 1.3 pinned to the scanned QR. No session data crosses this channel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
            }

            is PairingUiState.PasskeyRequired -> {
                CircularProgressIndicator()
                Text(
                    if (pairing.registration) "Registering this device with a passkey…" else "Verifying your passkey…",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Complete the system passkey prompt. There is no password fallback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
            }

            is PairingUiState.AwaitingMacConfirmation -> {
                Text("Confirm on your Mac", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (pairing.shortCode != null) {
                    Text(
                        pairing.shortCode,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 8.sp,
                    )
                    Text(
                        "The Mac shows the same code. Confirm there — this device cannot confirm for the Mac.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    CircularProgressIndicator()
                    Text(
                        "Waiting for the Mac to present its confirmation code…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }

            PairingUiState.IssuingCertificate -> {
                CircularProgressIndicator()
                Text("Installing the device certificate…", style = MaterialTheme.typography.titleMedium)
            }

            is PairingUiState.Failed -> {
                Text("Pairing failed", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    pairing.code,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCancel) { Text("Back") }
                    Button(onClick = onRetry) { Text("Try again") }
                }
            }
        }
    }
}

@Composable
private fun QrScanSurface(onScanned: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
    }
    if (!permissionGranted) {
        Text("Camera access scans the pairing QR", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            "The QR carries a one-use, five-minute pinned invitation. Without the camera you can only open a pimobile://pair link shown by the Mac.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        TextButton(onClick = onCancel) { Text("Back") }
        return
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanned = remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { scanned.value = true }
    }
    Text("Point at the Mac's pairing QR", style = MaterialTheme.typography.titleMedium)
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp),
        factory = { viewContext ->
            val previewView = androidx.camera.view.PreviewView(viewContext)
            val cameraProviderFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(viewContext)
            cameraProviderFuture.addListener(
                {
                    runCatching {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = androidx.camera.core.Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = androidx.camera.core.ImageAnalysis.Builder()
                            .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        val reader = com.google.zxing.MultiFormatReader().apply {
                            setHints(
                                mapOf(
                                    com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to
                                        listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                                ),
                            )
                        }
                        analysis.setAnalyzer(ContextCompat.getMainExecutor(viewContext)) { proxy ->
                            if (!scanned.value) {
                                runCatching {
                                    val buffer = proxy.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)
                                    val source = com.google.zxing.PlanarYUVLuminanceSource(
                                        bytes,
                                        proxy.width,
                                        proxy.height,
                                        0,
                                        0,
                                        proxy.width,
                                        proxy.height,
                                        false,
                                    )
                                    val result = reader.decodeWithState(
                                        com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)),
                                    )
                                    if (result.text.startsWith("pimobile://pair")) {
                                        scanned.value = true
                                        onScanned(result.text)
                                    }
                                    reader.reset()
                                }
                            }
                            proxy.close()
                        }
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }
                },
                ContextCompat.getMainExecutor(viewContext),
            )
            previewView
        },
    )
    TextButton(onClick = onCancel) { Text("Cancel") }
}
