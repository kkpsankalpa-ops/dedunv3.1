package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.Locale

/**
 * QuickEntryActivity
 *
 * A lightweight, transparent/dialog-themed activity for quick spending and income logging.
 * It uses a headless WebView in the background to persist transactions directly to the
 * shared WebView localStorage and sync widget statistics immediately without launching MainActivity.
 */
class QuickEntryActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "extra_quick_entry_mode"
        const val MODE_SPEND = "spend"
        const val MODE_INCOME = "income"

        val SPEND_CATEGORIES = listOf(
            "Food",
            "Transport",
            "Education",
            "Entertainment",
            "Clothing",
            "Subscriptions",
            "Health",
            "Other"
        )

        val INCOME_SOURCES = listOf(
            "Weekly allowance",
            "Personal income",
            "Gift",
            "Other"
        )

        fun createIntent(context: Context, mode: String): Intent {
            return Intent(context, QuickEntryActivity::class.java).apply {
                action = "com.example.action.QUICK_ENTRY_${mode.uppercase(Locale.ROOT)}"
                putExtra(EXTRA_MODE, mode)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }

    private var headlessWebView: WebView? = null
    private var isPageLoaded = false
    private var pendingSaveAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure genuine floating dialog window parameters
        setFinishOnTouchOutside(true)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setDimAmount(0.6f)
        window.setGravity(Gravity.CENTER)

        val displayMetrics = resources.displayMetrics
        val dialogWidth = (displayMetrics.widthPixels * 0.92f).toInt()
            .coerceAtMost((420 * displayMetrics.density).toInt())
        window.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)

        val initialMode = intent?.getStringExtra(EXTRA_MODE)
            ?: intent?.getStringExtra("mode")
            ?: intent?.getStringExtra("entry_type")
            ?: MODE_SPEND

        // Read synchronized theme styling from widget preferences
        val prefs = getSharedPreferences(DedunWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
        val themeAccentHex = prefs.getString("theme_accent", "#c9a66b") ?: "#c9a66b"
        val themeBgHex = prefs.getString("theme_bg", "#1c1b17") ?: "#1c1b17"
        val accentColor = parseColor(themeAccentHex, Color(0xFFC9A66B))
        val cardBgColor = parseColor(themeBgHex, Color(0xFF1C1B17))

        // Initialize headless WebView to load the shared index.html in the background
        initHeadlessWebView()

        setContent {
            QuickEntryDialog(
                initialMode = initialMode,
                accentColor = accentColor,
                cardBgColor = cardBgColor,
                onDismiss = { finish() },
                onSave = { mode, amount, category, note, onDone ->
                    saveTransaction(mode, amount, category, note, onDone)
                }
            )
        }
    }

    private fun initHeadlessWebView() {
        try {
            headlessWebView = WebView(this).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    @Suppress("DEPRECATION")
                    databaseEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                // Attach AndroidBridge so that syncWidgetData updates widget_prefs immediately
                addJavascriptInterface(AndroidBridge(this@QuickEntryActivity), "AndroidBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (isDestroyed || isFinishing) return
                        isPageLoaded = true
                        val action = pendingSaveAction
                        pendingSaveAction = null
                        action?.invoke()
                    }
                }
                loadUrl("file:///android_asset/index.html")
            }
        } catch (e: Exception) {
            isPageLoaded = false
        }
    }

    private fun saveTransaction(
        mode: String,
        amount: Double,
        category: String,
        note: String,
        onDone: (Boolean) -> Unit
    ) {
        if (isDestroyed || isFinishing) {
            onDone(false)
            return
        }

        val doSave = {
            if (isDestroyed || isFinishing) {
                onDone(false)
            } else {
                val safeWebView = headlessWebView
                if (safeWebView != null) {
                    val catQuote = JSONObject.quote(category)
                    val noteQuote = JSONObject.quote(note.trim())
                    val script = """
                        (function() {
                            if (typeof window.processTransactionEntry === 'function') {
                                var res = window.processTransactionEntry('$mode', $amount, $catQuote, $noteQuote);
                                return res ? JSON.stringify(res.transaction) : null;
                            } else if (typeof window.addTransaction === 'function') {
                                return window.addTransaction('$mode', $amount, $catQuote, $noteQuote);
                            } else {
                                return null;
                            }
                        })();
                    """.trimIndent()

                    safeWebView.evaluateJavascript(script) { result ->
                        cleanUpHeadlessWebView()
                        onDone(true)

                        val prefix = if (mode == MODE_INCOME) "+" else "−"
                        val formattedAmt = String.format(Locale.US, "%,.2f", amount)
                        Toast.makeText(
                            this@QuickEntryActivity,
                            "✓ Saved $prefix Rs. $formattedAmt ($category)",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Return user straight to home screen
                        finish()
                    }
                } else {
                    onDone(false)
                    Toast.makeText(this@QuickEntryActivity, "Error saving entry", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }

        if (isPageLoaded) {
            doSave()
        } else {
            pendingSaveAction = doSave
            // Watchdog: If page load takes longer than 3 seconds, fire pending save or fail
            headlessWebView?.postDelayed({
                if (pendingSaveAction != null && !isDestroyed && !isFinishing) {
                    val action = pendingSaveAction
                    pendingSaveAction = null
                    action?.invoke()
                }
            }, 3000)
        }
    }

    /**
     * Fully destroys the headless WebView instance, clears JavaScript interfaces,
     * detaches from parent, stops loading, and nulls references to avoid memory leaks.
     */
    private fun cleanUpHeadlessWebView() {
        synchronized(this) {
            val wv = headlessWebView ?: return
            headlessWebView = null
            pendingSaveAction = null
            isPageLoaded = false
            try {
                wv.removeJavascriptInterface("AndroidBridge")
                wv.webViewClient = object : WebViewClient() {}
                wv.webChromeClient = null
                wv.stopLoading()
                wv.settings.javaScriptEnabled = false
                wv.clearHistory()
                wv.clearCache(false)
                wv.loadUrl("about:blank")
                wv.onPause()
                wv.removeAllViews()
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            } catch (e: Throwable) {
                // Ignore cleanup exceptions
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // If user navigates away from the quick entry dialog, dismiss it cleanly
        if (!isFinishing) {
            finish()
        }
        cleanUpHeadlessWebView()
    }

    override fun onDestroy() {
        cleanUpHeadlessWebView()
        super.onDestroy()
    }

    private fun parseColor(hex: String, fallback: Color): Color {
        return try {
            val clean = hex.trim().removePrefix("#")
            val colorLong = when (clean.length) {
                6 -> ("FF$clean").toLong(16)
                8 -> clean.toLong(16)
                else -> return fallback
            }
            Color(colorLong)
        } catch (e: Exception) {
            fallback
        }
    }
}

@Composable
fun QuickEntryDialog(
    initialMode: String,
    accentColor: Color,
    cardBgColor: Color,
    onDismiss: () -> Unit,
    onSave: (mode: String, amount: Double, category: String, note: String, onDone: (Boolean) -> Unit) -> Unit
) {
    var mode by remember { mutableStateOf(if (initialMode == QuickEntryActivity.MODE_INCOME) QuickEntryActivity.MODE_INCOME else QuickEntryActivity.MODE_SPEND) }
    var amountText by remember { mutableStateOf("") }
    var selectedCategory by remember(mode) {
        mutableStateOf(if (mode == QuickEntryActivity.MODE_INCOME) QuickEntryActivity.INCOME_SOURCES.first() else QuickEntryActivity.SPEND_CATEGORIES.first())
    }
    var noteText by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val parsedAmount = amountText.toDoubleOrNull() ?: 0.0
    val isValidAmount = parsedAmount > 0.0

    val presets = if (mode == QuickEntryActivity.MODE_SPEND) {
        listOf(100, 250, 500, 1000)
    } else {
        listOf(500, 1000, 2500, 5000)
    }

    val categories = if (mode == QuickEntryActivity.MODE_SPEND) {
        QuickEntryActivity.SPEND_CATEGORIES
    } else {
        QuickEntryActivity.INCOME_SOURCES
    }

    // Genuine dialog card (dimmed overlay handled by WindowManager)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .imePadding(),
        shape = RoundedCornerShape(20.dp),
        color = cardBgColor,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
        shadowElevation = 16.dp
    ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // Header: Mode Switcher & Close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Spend / Income Mode Switcher
                    Row(
                        modifier = Modifier
                            .background(Color(0xFF141310), RoundedCornerShape(10.dp))
                            .border(BorderStroke(1.dp, Color(0x22FFFFFF)), RoundedCornerShape(10.dp))
                            .padding(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ModeTab(
                            title = "− Spend",
                            isSelected = mode == QuickEntryActivity.MODE_SPEND,
                            activeColor = Color(0xFFD95F5F),
                            onClick = {
                                if (!isSaving) {
                                    mode = QuickEntryActivity.MODE_SPEND
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        ModeTab(
                            title = "+ Income",
                            isSelected = mode == QuickEntryActivity.MODE_INCOME,
                            activeColor = Color(0xFF52B788),
                            onClick = {
                                if (!isSaving) {
                                    mode = QuickEntryActivity.MODE_INCOME
                                }
                            }
                        )
                    }

                    // Close Button
                    IconButton(
                        onClick = onDismiss,
                        enabled = !isSaving,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel and Close",
                            tint = Color(0xFFA09C94)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Amount Input Label & Container
                Text(
                    text = if (mode == QuickEntryActivity.MODE_SPEND) "SPEND AMOUNT" else "INCOME AMOUNT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = accentColor
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF12110E), RoundedCornerShape(12.dp))
                        .border(
                            BorderStroke(
                                1.5.dp,
                                if (isValidAmount) accentColor else Color(0x33FFFFFF)
                            ),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Rs.",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    BasicTextField(
                        value = amountText,
                        onValueChange = { input ->
                            // Allow digits and up to one decimal point with 2 decimals
                            val filtered = input.filter { it.isDigit() || it == '.' }
                            val dots = filtered.count { it == '.' }
                            if (dots <= 1) {
                                val parts = filtered.split(".")
                                if (parts.size == 1 || (parts.size == 2 && parts[1].length <= 2)) {
                                    amountText = filtered
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .testTag("quick_entry_amount_input"),
                        textStyle = TextStyle(
                            color = Color(0xFFF5F2EB),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (isValidAmount && !isSaving) {
                                    isSaving = true
                                    onSave(mode, parsedAmount, selectedCategory, noteText) {
                                        isSaving = false
                                    }
                                }
                            }
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(accentColor),
                        decorationBox = { innerTextField ->
                            if (amountText.isEmpty()) {
                                Text(
                                    text = "0.00",
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF5A5852),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                // Preset amount quick buttons
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEach { preset ->
                        PresetButton(
                            preset = preset,
                            accentColor = accentColor,
                            onClick = {
                                val current = amountText.toDoubleOrNull() ?: 0.0
                                val next = if (current == 0.0) preset.toDouble() else current + preset
                                amountText = if (next % 1.0 == 0.0) {
                                    next.toInt().toString()
                                } else {
                                    String.format(Locale.US, "%.2f", next)
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Category selection
                Text(
                    text = if (mode == QuickEntryActivity.MODE_SPEND) "CATEGORY" else "INCOME SOURCE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = Color(0xFFA09C94)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { cat ->
                        val isSelected = cat == selectedCategory
                        val chipBg by animateColorAsState(
                            targetValue = if (isSelected) accentColor else Color(0xFF141310),
                            label = "chip_bg"
                        )
                        val chipText = if (isSelected) Color(0xFF12110E) else Color(0xFFD6D2CA)
                        val chipBorder = if (isSelected) accentColor else Color(0x22FFFFFF)

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(chipBg)
                                .border(BorderStroke(1.dp, chipBorder), RoundedCornerShape(8.dp))
                                .clickable(enabled = !isSaving) {
                                    selectedCategory = cat
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = chipText,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = cat,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = chipText
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Note Field (Optional)
                Text(
                    text = "NOTE (OPTIONAL)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = Color(0xFFA09C94)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF12110E), RoundedCornerShape(10.dp))
                        .border(BorderStroke(1.dp, Color(0x22FFFFFF)), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("quick_entry_note_input"),
                        textStyle = TextStyle(
                            color = Color(0xFFE8E5DD),
                            fontSize = 14.sp
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(accentColor),
                        decorationBox = { innerTextField ->
                            if (noteText.isEmpty()) {
                                Text(
                                    text = if (mode == QuickEntryActivity.MODE_SPEND) "e.g. Canteen lunch, bus fare" else "e.g. Weekly allowance from Dad",
                                    fontSize = 14.sp,
                                    color = Color(0xFF5A5852)
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Save Button
                Button(
                    onClick = {
                        if (isValidAmount && !isSaving) {
                            isSaving = true
                            onSave(mode, parsedAmount, selectedCategory, noteText) {
                                isSaving = false
                            }
                        }
                    },
                    enabled = isValidAmount && !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("save_quick_entry_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = Color(0xFF12110E),
                        disabledContainerColor = Color(0xFF2B2924),
                        disabledContentColor = Color(0xFF5A5852)
                    )
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color(0xFF12110E),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Saving to Passbook...",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        val verb = if (mode == QuickEntryActivity.MODE_INCOME) "+ Save Income" else "− Save Spend"
                        val amtStr = if (isValidAmount) " · Rs. ${String.format(Locale.US, "%,.2f", parsedAmount)}" else ""
                        Text(
                            text = "$verb$amtStr",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (e: Exception) {
            // Ignore focus error
        }
    }
}

@Composable
private fun ModeTab(
    title: String,
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    val bg = if (isSelected) activeColor else Color.Transparent
    val text = if (isSelected) Color(0xFFFFFFFF) else Color(0xFFA09C94)

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = text
        )
    }
}

@Composable
private fun PresetButton(
    preset: Int,
    accentColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF141310))
            .border(BorderStroke(1.dp, Color(0x22FFFFFF)), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "+$preset",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = accentColor,
            fontFamily = FontFamily.Monospace
        )
    }
}
