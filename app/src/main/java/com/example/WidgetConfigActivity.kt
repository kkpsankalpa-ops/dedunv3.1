package com.example

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.GoldPrimary
import com.example.ui.theme.LedgerDarkBg
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.roundToInt

class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        enableEdgeToEdge()

        setContent {
            MyApplicationTheme(darkTheme = true) {
                WidgetConfigScreen(
                    appWidgetId = appWidgetId,
                    onFinishConfig = {
                        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        setResult(RESULT_OK, resultValue)
                        finish()
                    },
                    onCancel = {
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
fun WidgetConfigScreen(
    appWidgetId: Int,
    onFinishConfig: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }

    // Active cropped photo if set
    var currentPhotoBitmap by remember {
        mutableStateOf<Bitmap?>(loadCurrentPhoto(context, appWidgetId))
    }

    // Bitmap picked from gallery pending crop/repositioning
    var rawBitmapForCrop by remember { mutableStateOf<Bitmap?>(null) }

    // Read active theme name and colors
    val prefs = context.getSharedPreferences(DedunWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
    val activeThemeName = prefs.getString("theme_name", "Passbook Gold") ?: "Passbook Gold"
    val themeAccentHex = prefs.getString("theme_accent", "#c9a66b") ?: "#c9a66b"
    val themeBgHex = prefs.getString("theme_bg", "#1c1b17") ?: "#1c1b17"
    val activeThemeAccent = androidx.compose.ui.graphics.Color(DedunWidgetProvider.parseColor(themeAccentHex, 0xFFC9A66B.toInt()))
    val activeThemeBg = androidx.compose.ui.graphics.Color(DedunWidgetProvider.parseColor(themeBgHex, 0xFF1C1B17.toInt()))

    // Zero-permission Photo Picker Launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            isProcessing = true
            scope.launch {
                val loaded = loadSampledBitmapFromUri(context, uri, maxDimension = 1400)
                isProcessing = false
                if (loaded != null) {
                    rawBitmapForCrop = loaded
                } else {
                    Toast.makeText(context, "Could not open selected image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // If an image was picked, show the interactive cropping screen
    if (rawBitmapForCrop != null) {
        WidgetImageCropper(
            sourceBitmap = rawBitmapForCrop!!,
            onCropConfirmed = { croppedResult ->
                isProcessing = true
                scope.launch {
                    val saved = saveCroppedWidgetPhoto(context, croppedResult, appWidgetId)
                    isProcessing = false
                    rawBitmapForCrop = null
                    if (saved) {
                        currentPhotoBitmap = loadCurrentPhoto(context, appWidgetId)
                        updateWidget(context, appWidgetId)
                        Toast.makeText(context, "Widget photo background applied", Toast.LENGTH_SHORT).show()
                        onFinishConfig()
                    } else {
                        Toast.makeText(context, "Failed to save cropped image", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onCancel = {
                rawBitmapForCrop = null
            }
        )
        return
    }

    Scaffold(
        containerColor = LedgerDarkBg,
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = androidx.compose.ui.graphics.Color(0xFFEDE8DF)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Widget Appearance",
                    color = androidx.compose.ui.graphics.Color(0xFFEDE8DF),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Customize the background of your DEDUN home screen widget. You can pick any photo from your gallery, pinch and drag to crop it to fit, or synchronize with your active theme.",
                color = androidx.compose.ui.graphics.Color(0xFF9E988D),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Current Active Background Card Preview
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = androidx.compose.ui.graphics.Color(0xFF23211C)
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.5.dp,
                        androidx.compose.ui.graphics.Color(0xFF36342D),
                        RoundedCornerShape(18.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "ACTIVE PREVIEW",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = androidx.compose.ui.graphics.Color(0xFF9E988D),
                        letterSpacing = 1.sp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (currentPhotoBitmap != null) androidx.compose.ui.graphics.Color(0xFF14120E) else activeThemeBg)
                            .border(
                                1.dp,
                                activeThemeAccent.copy(alpha = 0.45f),
                                RoundedCornerShape(14.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (currentPhotoBitmap != null) {
                            Image(
                                bitmap = currentPhotoBitmap!!.asImageBitmap(),
                                contentDescription = "Custom Widget Photo",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        // Sample widget text over preview
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "DEDUN",
                                    color = activeThemeAccent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "🔥 5",
                                    color = activeThemeAccent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Column {
                                Text(
                                    text = "CURRENT BALANCE",
                                    color = androidx.compose.ui.graphics.Color(0xFFEDE8DF).copy(alpha = 0.8f),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Rs. 25,000.00",
                                    color = activeThemeAccent,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val statusLabel = if (currentPhotoBitmap != null) {
                        "Custom Photo Background Active"
                    } else {
                        "Synced to Theme ($activeThemeName)"
                    }
                    Text(
                        text = statusLabel,
                        color = if (currentPhotoBitmap != null) activeThemeAccent else androidx.compose.ui.graphics.Color(0xFF9E988D),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            if (isProcessing) {
                CircularProgressIndicator(
                    color = GoldPrimary,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Processing image...",
                    color = androidx.compose.ui.graphics.Color(0xFF9E988D),
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Option 1: Choose Photo from Gallery (triggers Crop flow)
            Button(
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = !isProcessing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GoldPrimary,
                    contentColor = androidx.compose.ui.graphics.Color(0xFF1C1B17)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (currentPhotoBitmap != null) "Change & Crop Photo" else "Pick & Crop Photo",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Option 2: Use Theme Background (Default / Skip)
            OutlinedButton(
                onClick = {
                    WidgetPhotoHelper.deleteWidgetPhoto(context, appWidgetId)
                    currentPhotoBitmap = null
                    updateWidget(context, appWidgetId)
                    Toast.makeText(context, "Using theme background", Toast.LENGTH_SHORT).show()
                    onFinishConfig()
                },
                enabled = !isProcessing,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = androidx.compose.ui.graphics.Color(0xFFEDE8DF)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    androidx.compose.ui.graphics.Color(0xFF44423A)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    tint = activeThemeAccent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Use Theme Background (Default)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Option 3: Remove custom photo if one is active
            if (currentPhotoBitmap != null) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        WidgetPhotoHelper.deleteWidgetPhoto(context, appWidgetId)
                        currentPhotoBitmap = null
                        updateWidget(context, appWidgetId)
                        Toast.makeText(context, "Removed photo", Toast.LENGTH_SHORT).show()
                    },
                    enabled = !isProcessing,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = androidx.compose.ui.graphics.Color(0xFFD95F5F)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        androidx.compose.ui.graphics.Color(0xFFD95F5F).copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color(0xFFD95F5F),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Remove Photo",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Close without changes",
                color = androidx.compose.ui.graphics.Color(0xFF9E988D),
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { onFinishConfig() }
                    .padding(8.dp)
            )
        }
    }
}

/**
 * Interactive Jetpack Compose Image Cropper
 * Allows pinch-to-zoom and drag/pan to position the photo within the target aspect ratio viewport.
 */
@Composable
fun WidgetImageCropper(
    sourceBitmap: Bitmap,
    onCropConfirmed: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    val density = LocalDensity.current
    val bmpW = sourceBitmap.width.toFloat()
    val bmpH = sourceBitmap.height.toFloat()

    // Aspect ratio modes: 2:1 (Large Widget 4x2) vs 1:1 (Small Widget / Quick Widgets)
    var isWideAspect by remember { mutableStateOf(true) }
    val targetAspect = if (isWideAspect) 2.0f else 1.0f

    var userScale by remember { mutableFloatStateOf(1f) }
    var userOffset by remember { mutableStateOf(Offset.Zero) }

    // Measurement of the crop viewport
    var cropViewportWidthPx by remember { mutableFloatStateOf(1f) }
    var cropViewportHeightPx by remember { mutableFloatStateOf(1f) }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color(0xFF0F0E0C),
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = androidx.compose.ui.graphics.Color(0xFFEDE8DF)
                    )
                }

                Text(
                    text = "Crop Widget Photo",
                    color = androidx.compose.ui.graphics.Color(0xFFEDE8DF),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                IconButton(
                    onClick = {
                        val cropped = executeCrop(
                            source = sourceBitmap,
                            cropWidthPx = cropViewportWidthPx,
                            cropHeightPx = cropViewportHeightPx,
                            userScale = userScale,
                            userOffset = userOffset,
                            isWide = isWideAspect
                        )
                        onCropConfirmed(cropped)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Confirm Crop",
                        tint = GoldPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Aspect Ratio Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        isWideAspect = true
                        userScale = 1f
                        userOffset = Offset.Zero
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isWideAspect) GoldPrimary else androidx.compose.ui.graphics.Color(0xFF26241E),
                        contentColor = if (isWideAspect) androidx.compose.ui.graphics.Color(0xFF1C1B17) else androidx.compose.ui.graphics.Color(0xFFD4CEBA)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Wide 2:1 (Large)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.width(10.dp))

                Button(
                    onClick = {
                        isWideAspect = false
                        userScale = 1f
                        userOffset = Offset.Zero
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!isWideAspect) GoldPrimary else androidx.compose.ui.graphics.Color(0xFF26241E),
                        contentColor = if (!isWideAspect) androidx.compose.ui.graphics.Color(0xFF1C1B17) else androidx.compose.ui.graphics.Color(0xFFD4CEBA)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Square 1:1 (Small)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Pinch to zoom • Drag to reposition",
                color = androidx.compose.ui.graphics.Color(0xFF9E988D),
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Viewport Box
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val availableW = constraints.maxWidth.toFloat()
                val availableH = constraints.maxHeight.toFloat()

                // Calculate crop box dimensions to fit inside available constraints
                val cropBoxW: Float
                val cropBoxH: Float
                if (availableW / availableH > targetAspect) {
                    cropBoxH = (availableH * 0.82f).coerceAtLeast(100f)
                    cropBoxW = cropBoxH * targetAspect
                } else {
                    cropBoxW = (availableW * 0.92f).coerceAtLeast(100f)
                    cropBoxH = cropBoxW / targetAspect
                }

                cropViewportWidthPx = cropBoxW
                cropViewportHeightPx = cropBoxH

                val cropBoxWDp = with(density) { cropBoxW.toDp() }
                val cropBoxHDp = with(density) { cropBoxH.toDp() }

                // Base scale: image fills the crop box completely at scale=1
                val baseScale = maxOf(cropBoxW / bmpW, cropBoxH / bmpH)
                val effectiveScale = baseScale * userScale

                val dispW = bmpW * effectiveScale
                val dispH = bmpH * effectiveScale

                // Limit panning so image never reveals blank empty areas inside the crop frame
                val maxOffsetX = ((dispW - cropBoxW) / 2f).coerceAtLeast(0f)
                val maxOffsetY = ((dispH - cropBoxH) / 2f).coerceAtLeast(0f)

                // Clamp current offset within allowed bounds
                val clampedOffset = Offset(
                    x = userOffset.x.coerceIn(-maxOffsetX, maxOffsetX),
                    y = userOffset.y.coerceIn(-maxOffsetY, maxOffsetY)
                )

                // The Crop Container
                Box(
                    modifier = Modifier
                        .size(cropBoxWDp, cropBoxHDp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(
                            2.dp,
                            GoldPrimary,
                            RoundedCornerShape(16.dp)
                        )
                        .pointerInput(isWideAspect, sourceBitmap) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                userScale = (userScale * zoom).coerceIn(1f, 5f)
                                userOffset = Offset(
                                    x = (userOffset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                    y = (userOffset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Transformed Image
                    Image(
                        bitmap = sourceBitmap.asImageBitmap(),
                        contentDescription = "Image for cropping",
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = effectiveScale
                                scaleY = effectiveScale
                                translationX = clampedOffset.x
                                translationY = clampedOffset.y
                            },
                        contentScale = ContentScale.None
                    )

                    // Subtle Grid Overlay (Rule of thirds)
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        val stepX = size.width / 3f
                        val stepY = size.height / 3f
                        val gridColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.22f)

                        // Vertical lines
                        drawLine(gridColor, Offset(stepX, 0f), Offset(stepX, size.height), strokeWidth = 1.dp.toPx())
                        drawLine(gridColor, Offset(stepX * 2, 0f), Offset(stepX * 2, size.height), strokeWidth = 1.dp.toPx())

                        // Horizontal lines
                        drawLine(gridColor, Offset(0f, stepY), Offset(size.width, stepY), strokeWidth = 1.dp.toPx())
                        drawLine(gridColor, Offset(0f, stepY * 2), Offset(size.width, stepY * 2), strokeWidth = 1.dp.toPx())
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Zoom Slider & Reset controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Zoom",
                    color = androidx.compose.ui.graphics.Color(0xFF9E988D),
                    fontSize = 13.sp,
                    modifier = Modifier.width(44.dp)
                )

                Slider(
                    value = userScale,
                    onValueChange = { userScale = it },
                    valueRange = 1f..5f,
                    colors = SliderDefaults.colors(
                        thumbColor = GoldPrimary,
                        activeTrackColor = GoldPrimary,
                        inactiveTrackColor = androidx.compose.ui.graphics.Color(0xFF333027)
                    ),
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = {
                        userScale = 1f
                        userOffset = Offset.Zero
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFF26241E),
                        contentColor = androidx.compose.ui.graphics.Color(0xFFEDE8DF)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Reset", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Confirm Crop Button
            Button(
                onClick = {
                    val cropped = executeCrop(
                        source = sourceBitmap,
                        cropWidthPx = cropViewportWidthPx,
                        cropHeightPx = cropViewportHeightPx,
                        userScale = userScale,
                        userOffset = userOffset,
                        isWide = isWideAspect
                    )
                    onCropConfirmed(cropped)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = GoldPrimary,
                    contentColor = androidx.compose.ui.graphics.Color(0xFF1C1B17)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Apply & Set Background", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

/**
 * Executes the exact pixel crop based on viewport dimensions and user transformations.
 */
private fun executeCrop(
    source: Bitmap,
    cropWidthPx: Float,
    cropHeightPx: Float,
    userScale: Float,
    userOffset: Offset,
    isWide: Boolean
): Bitmap {
    val bmpW = source.width.toFloat()
    val bmpH = source.height.toFloat()

    val baseScale = maxOf(cropWidthPx / bmpW, cropHeightPx / bmpH)
    val effectiveScale = baseScale * userScale

    val dispW = bmpW * effectiveScale
    val dispH = bmpH * effectiveScale

    val maxOffsetX = ((dispW - cropWidthPx) / 2f).coerceAtLeast(0f)
    val maxOffsetY = ((dispH - cropHeightPx) / 2f).coerceAtLeast(0f)

    val clampedOffsetX = userOffset.x.coerceIn(-maxOffsetX, maxOffsetX)
    val clampedOffsetY = userOffset.y.coerceIn(-maxOffsetY, maxOffsetY)

    // Calculate crop rectangle in original bitmap coordinates
    val dispCropLeft = (cropWidthPx / 2f) - (cropWidthPx / 2f + clampedOffsetX) + (dispW / 2f) - (cropWidthPx / 2f)
    val dispCropTop = (cropHeightPx / 2f) - (cropHeightPx / 2f + clampedOffsetY) + (dispH / 2f) - (cropHeightPx / 2f)

    val srcLeft = (dispCropLeft / effectiveScale).toInt().coerceIn(0, source.width - 1)
    val srcTop = (dispCropTop / effectiveScale).toInt().coerceIn(0, source.height - 1)
    val srcWidth = (cropWidthPx / effectiveScale).toInt().coerceAtMost(source.width - srcLeft).coerceAtLeast(1)
    val srcHeight = (cropHeightPx / effectiveScale).toInt().coerceAtMost(source.height - srcTop).coerceAtLeast(1)

    val cropped = Bitmap.createBitmap(source, srcLeft, srcTop, srcWidth, srcHeight)

    // Scale to standard target dimension: 560x280 for 2:1 or 400x400 for 1:1
    val targetW = if (isWide) 560 else 400
    val targetH = if (isWide) 280 else 400

    val scaledCropped = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
    if (scaledCropped != cropped) {
        cropped.recycle()
    }

    // Apply 55% dark legibility scrim
    val composited = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(composited)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    canvas.drawBitmap(scaledCropped, 0f, 0f, paint)

    val scrimPaint = Paint().apply {
        color = Color.argb(145, 14, 15, 20)
    }
    canvas.drawRect(0f, 0f, targetW.toFloat(), targetH.toFloat(), scrimPaint)
    scaledCropped.recycle()

    return composited
}

private fun loadSampledBitmapFromUri(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        var is1: InputStream? = context.contentResolver.openInputStream(uri)
        BitmapFactory.decodeStream(is1, null, options)
        is1?.close()

        val origW = options.outWidth
        val origH = options.outHeight
        if (origW <= 0 || origH <= 0) return null

        var sampleSize = 1
        while (origW / (sampleSize * 2) >= maxDimension || origH / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val is2 = context.contentResolver.openInputStream(uri)
        val decoded = BitmapFactory.decodeStream(is2, null, decodeOptions)
        is2?.close()
        decoded
    } catch (e: Exception) {
        Log.e("WidgetConfigActivity", "Error loading sampled bitmap", e)
        null
    }
}

private suspend fun saveCroppedWidgetPhoto(context: Context, cropped: Bitmap, appWidgetId: Int): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val outFile = WidgetPhotoHelper.getPhotoFile(context, appWidgetId)
            FileOutputStream(outFile).use { fos ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            val prefs = context.getSharedPreferences(DedunWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("widget_photo_$appWidgetId", outFile.absolutePath).apply()
            true
        } catch (e: Exception) {
            Log.e("WidgetConfigActivity", "Error saving cropped photo", e)
            false
        }
    }
}

private fun loadCurrentPhoto(context: Context, appWidgetId: Int): Bitmap? {
    val file = WidgetPhotoHelper.getPhotoFile(context, appWidgetId)
    return if (file.exists() && file.length() > 0) {
        BitmapFactory.decodeFile(file.absolutePath)
    } else {
        null
    }
}

private fun updateWidget(context: Context, appWidgetId: Int) {
    val appWidgetManager = AppWidgetManager.getInstance(context)
    val ids = intArrayOf(appWidgetId)
    DedunWidgetProvider.updateAppWidgets(context, appWidgetManager, ids)
    DedunWidgetSmallProvider.updateAppWidgets(context, appWidgetManager, ids)
    QuickSpendWidgetProvider.updateAppWidgets(context, appWidgetManager, ids)
    QuickIncomeWidgetProvider.updateAppWidgets(context, appWidgetManager, ids)
}

object WidgetPhotoHelper {
    fun getPhotoFile(context: Context, appWidgetId: Int): File {
        return File(context.filesDir, "widget_bg_${appWidgetId}.jpg")
    }

    fun hasCustomPhoto(context: Context, appWidgetId: Int): Boolean {
        val file = getPhotoFile(context, appWidgetId)
        return file.exists() && file.length() > 0
    }

    fun deleteWidgetPhoto(context: Context, appWidgetId: Int) {
        try {
            val file = getPhotoFile(context, appWidgetId)
            if (file.exists()) {
                file.delete()
            }
            val prefs = context.getSharedPreferences(DedunWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove("widget_photo_$appWidgetId").apply()
        } catch (e: Exception) {
            Log.e("WidgetPhotoHelper", "Error deleting photo", e)
        }
    }
}
