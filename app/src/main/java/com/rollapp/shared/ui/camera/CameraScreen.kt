package com.rollapp.shared.ui.camera

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.alpha
import com.rollapp.shared.core.Limits
import com.rollapp.shared.ui.components.GoldButton
import com.rollapp.shared.ui.components.Hairline
import com.rollapp.shared.ui.components.Readout
import com.rollapp.shared.ui.components.Settle
import com.rollapp.shared.ui.components.Shutter
import com.rollapp.shared.ui.theme.Gold
import com.rollapp.shared.ui.theme.Ink
import com.rollapp.shared.ui.theme.Ivory
import com.rollapp.shared.ui.theme.Muted
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.Grid3x3
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                text = "WeWere needs your camera",
                style = MaterialTheme.typography.headlineMedium,
                color = Ivory,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Hairline(Modifier.width(120.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (shouldExplain) {
                    "Without it, you can still upload from your gallery — but taking a " +
                        "photo straight into the roll needs camera access."
                } else {
                    "So you can take photos straight into the roll. Nothing is captured " +
                        "until you press the shutter."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            GoldButton(text = "Allow camera", onClick = onRequest)
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
    var showGrid by remember { mutableStateOf(false) }
    var timerSeconds by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    val camera = remember { mutableStateOf<Camera?>(null) }
    val scope = rememberCoroutineScope()

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember { mutableStateOf<ImageCapture?>(null) }
    val cameraProvider = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(Limits.MAX_GALLERY_SELECTION)
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
            camera.value = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            imageCapture.value = capture
            // Flipping the lens resets optics; carrying the old zoom across is wrong.
            zoomRatio = 1f
        }
    }

    // A tapped focus reticle should fade rather than sit there forever.
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(900)
            focusPoint = null
        }
    }

    fun capture() {
        val imageCaptureUseCase = imageCapture.value ?: return
        isCapturing = true
        imageCaptureUseCase.takePictureInto(context) { result ->
            isCapturing = false
            result.onSuccess { uri -> onPhotoCaptured(uri, System.currentTimeMillis()) }
        }
    }

    fun shutterPressed() {
        if (timerSeconds == 0) {
            capture()
            return
        }
        scope.launch {
            countdown = timerSeconds
            while (countdown > 0) {
                delay(1000)
                countdown -= 1
            }
            capture()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Release the camera for other apps as soon as this screen goes away.
            cameraProvider.value?.unbindAll()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(camera.value) {
                    detectTransformGestures { centroid, _, zoom, _ ->
                        val control = camera.value?.cameraControl ?: return@detectTransformGestures
                        val info = camera.value?.cameraInfo ?: return@detectTransformGestures
                        val max = info.zoomState.value?.maxZoomRatio ?: 1f
                        val min = info.zoomState.value?.minZoomRatio ?: 1f
                        zoomRatio = (zoomRatio * zoom).coerceIn(min, max)
                        control.setZoomRatio(zoomRatio)
                    }
                }
                .pointerInput(camera.value) {
                    detectTapGestures { offset ->
                        val control = camera.value?.cameraControl ?: return@detectTapGestures
                        focusPoint = offset

                        val factory = previewView.meteringPointFactory
                        val point = factory.createPoint(offset.x, offset.y)
                        control.startFocusAndMetering(
                            FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                                // Hand control back to continuous AF after a moment,
                                // so one tap does not lock focus for the whole session.
                                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                .build()
                        )
                    }
                }
        )

        if (showGrid) {
            // Rule-of-thirds guides. Hairline and low-contrast so they help composition
            // without competing with the scene.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 1.dp.toPx()
                val lineColor = Color.White.copy(alpha = 0.35f)
                for (i in 1..2) {
                    val x = size.width * i / 3f
                    val y = size.height * i / 3f
                    drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), stroke)
                    drawLine(lineColor, Offset(0f, y), Offset(size.width, y), stroke)
                }
            }
        }

        ViewfinderBrackets(modifier = Modifier.fillMaxSize())

        focusPoint?.let { point ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Gold,
                    radius = 38.dp.toPx(),
                    center = point,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        if (zoomRatio > 1.05f) {
            Readout(
                text = "%.1f×".format(zoomRatio),
                color = Gold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = 120.dp)
                    .background(Ink.copy(alpha = 0.55f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        if (countdown > 0) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$countdown",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp),
                    color = Gold
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Ivory)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showGrid = !showGrid }) {
                Icon(
                    imageVector = Icons.Rounded.Grid3x3,
                    contentDescription = if (showGrid) "Hide grid" else "Show grid",
                    tint = if (showGrid) Gold else Ivory
                )
            }
            IconButton(
                onClick = {
                    // Off -> 3s -> 10s -> off. Two useful delays beat a picker.
                    timerSeconds = when (timerSeconds) {
                        0 -> 3
                        3 -> 10
                        else -> 0
                    }
                }
            ) {
                if (timerSeconds == 0) {
                    Icon(Icons.Rounded.TimerOff, contentDescription = "Self-timer off", tint = Ivory)
                } else {
                    Readout(text = "${timerSeconds}s", color = Gold)
                }
            }
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
                    tint = if (flashMode == ImageCapture.FLASH_MODE_OFF) Ivory else Gold
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
                    .size(52.dp)
                    .background(Ink.copy(alpha = 0.55f), CircleShape)
                    .border(1.dp, Ivory.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(Icons.Rounded.PhotoLibrary, contentDescription = "Gallery", tint = Ivory)
            }

            val shutterEnabled = !isCapturing && countdown == 0
            Shutter(
                onClick = { if (shutterEnabled) shutterPressed() },
                size = 84.dp,
                pulsing = shutterEnabled,
                modifier = Modifier.alpha(if (shutterEnabled) 1f else 0.5f)
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
                    .size(52.dp)
                    .background(Ink.copy(alpha = 0.55f), CircleShape)
                    .border(1.dp, Ivory.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(Icons.Rounded.Cameraswitch, contentDescription = "Flip camera", tint = Ivory)
            }
        }
    }
}

/**
 * Gold corner brackets that breathe slowly — the viewfinder is alive without any
 * of it getting in the way of the scene.
 */
@Composable
private fun ViewfinderBrackets(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "viewfinder")
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = Settle), RepeatMode.Reverse),
        label = "breathe"
    )
    Canvas(modifier = modifier) {
        val inset = 34.dp.toPx()
        val top = size.height * 0.2f
        val bottom = size.height * 0.72f
        val len = 26.dp.toPx()
        val stroke = 2.dp.toPx()
        val grow = 3.dp.toPx() * breathe
        val color = Gold.copy(alpha = 0.7f + 0.3f * breathe)
        val l = inset - grow; val r = size.width - inset + grow
        val t = top - grow; val b = bottom + grow
        drawLine(color, Offset(l, t), Offset(l + len, t), stroke); drawLine(color, Offset(l, t), Offset(l, t + len), stroke)
        drawLine(color, Offset(r, t), Offset(r - len, t), stroke); drawLine(color, Offset(r, t), Offset(r, t + len), stroke)
        drawLine(color, Offset(l, b), Offset(l + len, b), stroke); drawLine(color, Offset(l, b), Offset(l, b - len), stroke)
        drawLine(color, Offset(r, b), Offset(r - len, b), stroke); drawLine(color, Offset(r, b), Offset(r, b - len), stroke)
        drawCircle(Gold, radius = 3.dp.toPx(), center = Offset(size.width / 2f, (top + bottom) / 2f))
    }
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
