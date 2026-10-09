package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.GoldPrimary
import com.example.ui.theme.LedgerDarkBg
import com.example.ui.theme.MyApplicationTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val pendingQuickMode = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleQuickIntent(intent)
        ensureCleanWebViewCache()
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true) {
                DedunApp(
                    pendingQuickMode = pendingQuickMode.value,
                    onQuickModeConsumed = { pendingQuickMode.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleQuickIntent(intent)
    }

    private fun handleQuickIntent(intent: Intent?) {
        val mode = intent?.getStringExtra(QuickEntryActivity.EXTRA_MODE)
            ?: intent?.getStringExtra("mode")
            ?: intent?.getStringExtra("entry_type")
        if (!mode.isNullOrBlank()) {
            pendingQuickMode.value = mode
        }
    }

    private fun ensureCleanWebViewCache() {
        try {
            val webViewCache = File(cacheDir, "WebView")
            val defaultDir = File(webViewCache, "Default")
            val httpCacheDir = File(defaultDir, "HTTP Cache")
            if (httpCacheDir.exists() && !httpCacheDir.isDirectory) {
                httpCacheDir.delete()
            }
            val codeCacheDir = File(httpCacheDir, "Code Cache")
            if (codeCacheDir.exists() && !codeCacheDir.isDirectory) {
                codeCacheDir.delete()
            }
            val jsDir = File(codeCacheDir, "js")
            if (jsDir.exists() && !jsDir.isDirectory) {
                jsDir.delete()
            }
            val wasmDir = File(codeCacheDir, "wasm")
            if (wasmDir.exists() && !wasmDir.isDirectory) {
                wasmDir.delete()
            }
            listOf(webViewCache, defaultDir, httpCacheDir, codeCacheDir, jsDir, wasmDir).forEach { dir ->
                if (!dir.exists()) {
                    dir.mkdirs()
                }
            }
            fixCachePermissions(webViewCache)
        } catch (e: Exception) {
            Log.w("MainActivity", "WebView cache setup: ${e.message}")
        }
    }

    private fun fixCachePermissions(file: File) {
        try {
            file.setReadable(true, false)
            file.setWritable(true, false)
            if (file.isDirectory) {
                file.setExecutable(true, false)
                file.listFiles()?.forEach { fixCachePermissions(it) }
            }
        } catch (_: Exception) {}
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DedunApp(
    pendingQuickMode: String? = null,
    onQuickModeConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var webViewRecoveryKey by remember { mutableIntStateOf(0) }
    var fileUploadCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    LaunchedEffect(pendingQuickMode, webViewInstance) {
        if (pendingQuickMode != null && webViewInstance != null) {
            val target = if (pendingQuickMode.equals("income", ignoreCase = true)) "income" else "spend"
            webViewInstance?.evaluateJavascript(
                "if (typeof window.openEntryForm === 'function') { window.openEntryForm('$target'); }",
                null
            )
            onQuickModeConsumed()
        }
    }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            val intentData = result.data
            when {
                intentData?.clipData != null -> {
                    val count = intentData.clipData!!.itemCount
                    Array(count) { i -> intentData.clipData!!.getItemAt(i).uri }
                }
                intentData?.data != null -> {
                    arrayOf(intentData.data!!)
                }
                else -> null
            }
        } else {
            null
        }
        fileUploadCallback?.onReceiveValue(uris)
        fileUploadCallback = null
    }

    BackHandler(enabled = canGoBack) {
        webViewInstance?.let {
            if (it.canGoBack()) {
                it.goBack()
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(LedgerDarkBg)
            .testTag("dedun_main_container"),
        contentWindowInsets = WindowInsets.systemBars
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .background(LedgerDarkBg)
        ) {
            key(webViewRecoveryKey) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("dedun_webview"),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setBackgroundColor(Color.parseColor("#151411"))
                            isVerticalScrollBarEnabled = false
                            isHorizontalScrollBarEnabled = false
                            overScrollMode = View.OVER_SCROLL_NEVER
                            // Avoid forced hardware offscreen layer buffers that trigger DRM rendernode checks
                            setLayerType(View.LAYER_TYPE_NONE, null)

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                @Suppress("DEPRECATION")
                                databaseEnabled = true
                                allowFileAccess = true
                                allowContentAccess = true
                                loadWithOverviewMode = true
                                useWideViewPort = true
                                setSupportZoom(false)
                                builtInZoomControls = false
                                displayZoomControls = false
                                cacheMode = WebSettings.LOAD_DEFAULT
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            }

                            if (activity != null) {
                                addJavascriptInterface(AndroidBridge(activity), "AndroidBridge")
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onShowFileChooser(
                                    webView: WebView?,
                                    filePathCallback: ValueCallback<Array<Uri>>?,
                                    fileChooserParams: FileChooserParams?
                                ): Boolean {
                                    fileUploadCallback?.onReceiveValue(null)
                                    fileUploadCallback = filePathCallback
                                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                        type = "application/json"
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                    }
                                    return try {
                                        fileChooserLauncher.launch(intent)
                                        true
                                    } catch (e: Exception) {
                                        fileUploadCallback?.onReceiveValue(null)
                                        fileUploadCallback = null
                                        false
                                    }
                                }

                                override fun onJsAlert(
                                    view: WebView?,
                                    url: String?,
                                    message: String?,
                                    result: JsResult?
                                ): Boolean {
                                    AlertDialog.Builder(ctx)
                                        .setTitle("DEDUN")
                                        .setMessage(message ?: "")
                                        .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                                        .setOnCancelListener { result?.cancel() }
                                        .show()
                                    return true
                                }

                                override fun onJsConfirm(
                                    view: WebView?,
                                    url: String?,
                                    message: String?,
                                    result: JsResult?
                                ): Boolean {
                                    AlertDialog.Builder(ctx)
                                        .setTitle("DEDUN")
                                        .setMessage(message ?: "")
                                        .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                                        .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                                        .setOnCancelListener { result?.cancel() }
                                        .show()
                                    return true
                                }

                                override fun onJsPrompt(
                                    view: WebView?,
                                    url: String?,
                                    message: String?,
                                    defaultValue: String?,
                                    result: JsPromptResult?
                                ): Boolean {
                                    val input = EditText(ctx).apply {
                                        setText(defaultValue ?: "")
                                    }
                                    AlertDialog.Builder(ctx)
                                        .setTitle("DEDUN")
                                        .setMessage(message ?: "")
                                        .setView(input)
                                        .setPositiveButton(android.R.string.ok) { _, _ ->
                                            result?.confirm(input.text.toString())
                                        }
                                        .setNegativeButton(android.R.string.cancel) { _, _ ->
                                            result?.cancel()
                                        }
                                        .setOnCancelListener { result?.cancel() }
                                        .show()
                                    return true
                                }

                                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                    consoleMessage?.let {
                                        Log.d("DEDUN_WEB", "[${it.sourceId()}:${it.lineNumber()}] ${it.message()}")
                                    }
                                    return super.onConsoleMessage(consoleMessage)
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    canGoBack = view?.canGoBack() == true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    canGoBack = view?.canGoBack() == true
                                    if (pendingQuickMode != null) {
                                        val target = if (pendingQuickMode.equals("income", ignoreCase = true)) "income" else "spend"
                                        view?.postDelayed({
                                            view.evaluateJavascript(
                                                "if (typeof window.openEntryForm === 'function') { window.openEntryForm('$target'); }",
                                                null
                                            )
                                            onQuickModeConsumed()
                                        }, 150)
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    super.onReceivedError(view, request, error)
                                    Log.w("DEDUN_WEB", "WebView error: ${error?.description} for ${request?.url}")
                                }

                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?
                                ): Boolean {
                                    val crashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        detail?.didCrash() ?: false
                                    } else {
                                        false
                                    }
                                    Log.w("DEDUN_WEB", "Render process gone: didCrash=$crashed. Recovering view...")
                                    try {
                                        view?.let { deadView ->
                                            (deadView.parent as? ViewGroup)?.removeView(deadView)
                                            deadView.destroy()
                                        }
                                    } catch (_: Exception) {}
                                    webViewRecoveryKey++
                                    return true
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    if (url.startsWith("file://") || url.startsWith("data:")) {
                                        return false
                                    }
                                    return try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        ctx.startActivity(intent)
                                        true
                                    } catch (e: Exception) {
                                        false
                                    }
                                }
                            }

                            loadUrl("file:///android_asset/index.html")
                            webViewInstance = this
                        }
                    },
                    update = {
                        webViewInstance = it
                    }
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.destroy()
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LedgerDarkBg),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Text(
            text = "DEDUN: $name",
            color = GoldPrimary,
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
        )
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyApplicationTheme { Greeting("Android") }
}
