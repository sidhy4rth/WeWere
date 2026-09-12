package com.rollapp.shared.ui.joingroup

import android.Manifest
import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.rollapp.shared.data.remote.InviteCodes
import java.util.concurrent.Executors

/**
 * Scans a group's QR code.
 *
 * Typing a six-character code is the slowest part of joining, and the one people get
 * wrong. Pointing a camera at a friend's screen is the natural gesture for an app
 * that is already a camera.
 *
 * ML Kit's bundled model is used rather than the Play Services one so the first scan
 * works offline — often at exactly the moment a group is being formed, on hotel wifi.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ScanQrScreen(
    onClose: () -> Unit,
    onCodeScanned: (String) -> Unit
) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    if (!cameraPermission.status.isGranted) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.statusBarsPadding().padding(4.dp)
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Point your camera at the code",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "WeWere needs the camera to read a roll's QR code. Nothing is " +
                        "recorded — it only looks for a code.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(28.dp))
                Button(
                    onClick = { cameraPermission.launchPermissionRequest() },
                    shape = MaterialTheme.shapes.medium
                ) { Text("Allow camera") }
                TextButton(onClick = onClose) {
                    Text("Enter the code instead", color = Color.White)
                }
            }
        }
        return
    }

    ScannerContent(onClose = onClose, onCodeScanned = onCodeScanned)
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun ScannerContent(
    onClose: () -> Unit,
    onCodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    // Guards against firing navigation repeatedly: the analyser sees the same code in
    // every frame for as long as it stays in view.
    var handled by remember { mutableStateOf(false) }
    val provider = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(Unit) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).await()
        provider.value = cameraProvider

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val analysis = ImageAnalysis.Builder()
            // Dropping stale frames keeps the scanner responsive on a slow device.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(analysisExecutor) { proxy ->
            val mediaImage = proxy.image
            if (mediaImage == null || handled) {
                proxy.close()
                return@setAnalyzer
            }

            val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val code = barcodes
                        .asSequence()
                        .mapNotNull { it.rawValue }
                        // Accept a full invite link or a bare code; a QR from another
                        // app should simply not match rather than throw.
                        .mapNotNull { raw ->
                            InviteCodes.fromLink(raw)
                                ?: InviteCodes.normalise(raw).takeIf(InviteCodes::isPlausible)
                        }
                        .firstOrNull()

                    if (code != null && !handled) {
                        handled = true
                        ContextCompat.getMainExecutor(context).execute { onCodeScanned(code) }
                    }
                }
                .addOnCompleteListener { proxy.close() }
        }

        runCatching {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            provider.value?.unbindAll()
            scanner.close()
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Dimmed surround with a clear window, so it is obvious where to aim.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(250.dp)
                .clip(RoundedCornerShape(24.dp))
                .border(3.dp, Color.White, RoundedCornerShape(24.dp))
        )

        IconButton(
            onClick = onClose,
            modifier = Modifier.statusBarsPadding().padding(4.dp)
        ) {
            Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Point at a roll's QR code",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            TextButton(onClick = onClose) {
                Text("Type the code instead", color = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}
