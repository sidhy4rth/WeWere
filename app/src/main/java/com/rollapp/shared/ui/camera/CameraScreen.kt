package com.rollapp.shared.ui.camera

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.google.accompanist.permissions.shouldShowRationale
import java.io.File

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    onClose: () -> Unit,
    onPhotoCaptured: (Uri, Long) -> Unit,
    onPickFromGallery: (List<Uri>) -> Unit
) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    if (!cameraPermission.status.isGranted) {
        CameraPermissionGate(
            shouldExplain = cameraPermission.status.shouldShowRationale,
            onRequest = { cameraPermission.launchPermissionRequest() },
            onClose = onClose
        )
        return
    }

    CameraContent(
        onClose = onClose,
        onPhotoCaptured = onPhotoCaptured,
        onPickFromGallery = onPickFromGallery
    )
}

/**
 * Permission is asked for here — at the moment the user taps the camera — rather than
 * at launch, with the reason stated before the system dialog appears.
 */
@Composable
private fun CameraPermissionGate(
    shouldExplain: Boolean,
    onRequest: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .statusBarsPadding()
                .padding(4.dp)
        ) {
            Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Roll needs your camera",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (shouldExplain) {
                    "Without it, you can still upload from your gallery — but taking a " +
                        "photo straight into the group needs camera access."
                } else {
                    "So you can take photos straight into the group. Nothing is captured " +
                        "until you press the shutter."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onRequest, shape = MaterialTheme.shapes.medium) {
                Text("Allow camera")
            }
        }
    }
}

@Composable
private fun CameraContent(
    onClose: () -> Unit,
    onPhotoCaptured: (Uri, Long) -> Unit,
    onPickFromGallery: (List<Uri>) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashMode by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) }
    var isCapturing by remember { mutableStateOf(false) }

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember { mutableStateOf<ImageCapture?>(null) }
    val cameraProvider = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30)
    ) { uris -> if (uris.isNotEmpty()) onPickFromGallery(uris) }

    // Rebind whenever the lens or flash changes; CameraX use cases are immutable in
    // those respects once bound.
    LaunchedEffect(lensFacing, flashMode) {
        // CameraX 1.4 hands back a ListenableFuture; concurrent-futures-ktx turns it
        // into a suspend call so the binding stays on the composition's coroutine.
        val provider = ProcessCameraProvider.getInstance(context).await()
        cameraProvider.value = provider

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flashMode)
            .build()

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            imageCapture.value = capture
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Release the camera for other apps as soon as this screen goes away.
            cameraProvider.value?.unbindAll()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    flashMode = when (flashMode) {
                        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                        ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                }
            ) {
                Icon(
                    imageVector = when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Rounded.FlashOn
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Rounded.FlashAuto
                        else -> Icons.Rounded.FlashOff
                    },
                    contentDescription = "Flash",
                    tint = Color.White
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = {
                    galleryPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.White.copy(alpha = 0.15f), CircleShape)
            ) {
                Icon(Icons.Rounded.PhotoLibrary, contentDescription = "Gallery", tint = Color.White)
            }

            ShutterButton(
                enabled = !isCapturing,
                onClick = {
                    val capture = imageCapture.value ?: return@ShutterButton
                    isCapturing = true
                    capture.takePictureInto(context) { result ->
                        isCapturing = false
                        result.onSuccess { uri -> onPhotoCaptured(uri, System.currentTimeMillis()) }
                    }
                }
            )

            IconButton(
                onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.White.copy(alpha = 0.15f), CircleShape)
            ) {
                Icon(Icons.Rounded.Cameraswitch, contentDescription = "Flip camera", tint = Color.White)
            }
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(78.dp)
            .border(3.dp, Color.White.copy(alpha = if (enabled) 1f else 0.4f), CircleShape)
            .padding(6.dp)
            .background(Color.White.copy(alpha = if (enabled) 1f else 0.4f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
    )
}

/**
 * Captures to a file in the app cache. Writing to a private file rather than straight
 * to MediaStore keeps a photo out of the user's own gallery until they choose to share
 * it — a shot they retake should leave no trace.
 */
private fun ImageCapture.takePictureInto(
    context: Context,
    onResult: (Result<Uri>) -> Unit
) {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    val options = ImageCapture.OutputFileOptions.Builder(file).build()

    takePicture(
        options,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onResult(Result.success(Uri.fromFile(file)))
            }

            override fun onError(exception: ImageCaptureException) {
                onResult(Result.failure(exception))
            }
        }
    )
}
