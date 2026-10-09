package com.example

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class AndroidBridge(
    private val activity: Activity
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun saveFile(base64Data: String, mimeType: String, fileName: String): Boolean {
        return try {
            val bytes = decodeBase64(base64Data)
            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(bytes, mimeType, fileName)
            } else {
                saveWithLegacyStorage(bytes, fileName)
            }

            mainHandler.post {
                if (success) {
                    Toast.makeText(activity, "Saved $fileName to Downloads/DEDUN", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(activity, "Failed to save $fileName", Toast.LENGTH_SHORT).show()
                }
            }
            success
        } catch (e: Exception) {
            Log.e("AndroidBridge", "Error saving file: $fileName", e)
            mainHandler.post {
                Toast.makeText(activity, "Error saving $fileName: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
            false
        }
    }

    @JavascriptInterface
    fun shareFile(base64Data: String, mimeType: String, fileName: String, title: String? = null) {
        try {
            val bytes = decodeBase64(base64Data)
            val shareDir = File(activity.cacheDir, "shared_files").apply { mkdirs() }
            val file = File(shareDir, fileName)
            FileOutputStream(file).use { it.write(bytes) }

            val uri: Uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType.ifEmpty { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title ?: fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            mainHandler.post {
                activity.startActivity(Intent.createChooser(shareIntent, title ?: "Share $fileName"))
            }
        } catch (e: Exception) {
            Log.e("AndroidBridge", "Error sharing file: $fileName", e)
            mainHandler.post {
                Toast.makeText(activity, "Failed to share: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun copyToClipboard(text: String, label: String? = null) {
        mainHandler.post {
            try {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(label ?: "DEDUN Data", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(activity, "${label ?: "Data"} copied to clipboard!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("AndroidBridge", "Failed to copy to clipboard", e)
            }
        }
    }

    @JavascriptInterface
    fun hideKeyboard() {
        mainHandler.post {
            try {
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                val currentFocus = activity.currentFocus ?: activity.window.decorView
                imm.hideSoftInputFromWindow(currentFocus.windowToken, 0)
            } catch (e: Exception) {
                Log.e("AndroidBridge", "Error hiding keyboard", e)
            }
        }
    }

    @JavascriptInterface
    fun setLauncherIcon(iconKey: String): Boolean {
        return try {
            val pm = activity.packageManager
            val packageName = activity.packageName

            val aliases = mapOf(
                "default" to "$packageName.MainActivityDefault",
                "slate" to "$packageName.MainActivitySlate",
                "vivid" to "$packageName.MainActivityVivid",
                "mint" to "$packageName.MainActivityMint"
            )

            val normalizedKey = iconKey.lowercase().trim()
            val targetAlias = aliases[normalizedKey] ?: aliases["default"]!!

            for ((_, aliasClass) in aliases) {
                val component = ComponentName(packageName, aliasClass)
                val newState = if (aliasClass == targetAlias) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                pm.setComponentEnabledSetting(
                    component,
                    newState,
                    PackageManager.DONT_KILL_APP
                )
            }

            mainHandler.post {
                val displayName = when (normalizedKey) {
                    "slate" -> "Premium Slate"
                    "vivid" -> "Vivid"
                    "mint" -> "Cool Mint"
                    else -> "Passbook Gold"
                }
                Toast.makeText(activity, "Launcher icon switched to $displayName", Toast.LENGTH_SHORT).show()
            }
            true
        } catch (e: Exception) {
            Log.e("AndroidBridge", "Error switching launcher icon to $iconKey", e)
            false
        }
    }

    @JavascriptInterface
    fun getActiveLauncherIcon(): String {
        return try {
            val pm = activity.packageManager
            val packageName = activity.packageName

            val aliases = mapOf(
                "slate" to "$packageName.MainActivitySlate",
                "vivid" to "$packageName.MainActivityVivid",
                "mint" to "$packageName.MainActivityMint",
                "default" to "$packageName.MainActivityDefault"
            )

            for ((key, aliasClass) in aliases) {
                val component = ComponentName(packageName, aliasClass)
                val state = pm.getComponentEnabledSetting(component)
                if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    return key
                }
            }
            "default"
        } catch (e: Exception) {
            Log.e("AndroidBridge", "Error getting active launcher icon", e)
            "default"
        }
    }

    @JavascriptInterface
    fun syncWidgetData(balance: Double, safeToSpend: Double, streak: Int, daysToAllowance: Int) {
        syncWidgetData(balance, safeToSpend, streak, daysToAllowance, 0.0, 2500.0, null, null, null)
    }

    @JavascriptInterface
    fun syncWidgetData(
        balance: Double,
        safeToSpend: Double,
        streak: Int,
        daysToAllowance: Int,
        dailySpent: Double,
        dailyLimit: Double
    ) {
        syncWidgetData(balance, safeToSpend, streak, daysToAllowance, dailySpent, dailyLimit, null, null, null)
    }

    @JavascriptInterface
    fun syncWidgetData(
        balance: Double,
        safeToSpend: Double,
        streak: Int,
        daysToAllowance: Int,
        dailySpent: Double,
        dailyLimit: Double,
        themeAccent: String?
    ) {
        syncWidgetData(balance, safeToSpend, streak, daysToAllowance, dailySpent, dailyLimit, themeAccent, null, null)
    }

    @JavascriptInterface
    fun syncWidgetData(
        balance: Double,
        safeToSpend: Double,
        streak: Int,
        daysToAllowance: Int,
        dailySpent: Double,
        dailyLimit: Double,
        themeAccent: String?,
        themeBg: String?,
        themeName: String?,
        dailyIncome: Double = 0.0,
        netCashFlowToday: Double = (dailyIncome - dailySpent)
    ) {
        try {
            val prefs = activity.getSharedPreferences(DedunWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("is_initialized", true)
                putFloat("balance", balance.toFloat())
                putFloat("safe_to_spend", safeToSpend.toFloat())
                putInt("streak", streak)
                putInt("days_to_allowance", daysToAllowance)
                putFloat("daily_spent", dailySpent.toFloat())
                putFloat("daily_limit", dailyLimit.toFloat())
                putFloat("daily_income", dailyIncome.toFloat())
                putFloat("net_cash_flow_today", netCashFlowToday.toFloat())
                if (!themeAccent.isNullOrBlank()) {
                    putString("theme_accent", themeAccent)
                }
                if (!themeBg.isNullOrBlank()) {
                    putString("theme_bg", themeBg)
                }
                if (!themeName.isNullOrBlank()) {
                    putString("theme_name", themeName)
                }
                putLong("last_updated", System.currentTimeMillis())
                apply()
            }

            // Immediately notify and update all active widgets
            mainHandler.post {
                DedunWidgetProvider.updateAllWidgets(activity)
                DedunWidgetSmallProvider.updateAllWidgets(activity)
                QuickSpendWidgetProvider.updateAllWidgets(activity)
                QuickIncomeWidgetProvider.updateAllWidgets(activity)
            }
        } catch (e: Exception) {
            Log.e("AndroidBridge", "Error syncing widget data", e)
        }
    }

    @JavascriptInterface
    fun syncWidgetData(
        balance: Double,
        safeToSpend: Double,
        streak: Int,
        daysToAllowance: Int,
        dailySpent: Double,
        dailyLimit: Double,
        themeAccent: String?,
        themeBg: String?,
        themeName: String?
    ) {
        syncWidgetData(
            balance,
            safeToSpend,
            streak,
            daysToAllowance,
            dailySpent,
            dailyLimit,
            themeAccent,
            themeBg,
            themeName,
            0.0,
            -dailySpent
        )
    }

    private fun decodeBase64(data: String): ByteArray {
        val cleanData = if (data.contains(",")) {
            data.substringAfter(",")
        } else {
            data
        }
        return Base64.decode(cleanData.trim(), Base64.DEFAULT)
    }

    private fun saveWithMediaStore(bytes: ByteArray, mimeType: String, fileName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return saveWithLegacyStorage(bytes, fileName)
        }
        val resolver = activity.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifEmpty { "application/octet-stream" })
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DEDUN")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { output: OutputStream ->
                output.write(bytes)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

    private fun saveWithLegacyStorage(bytes: ByteArray, fileName: String): Boolean {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dedunDir = File(downloadsDir, "DEDUN").apply { mkdirs() }
            val file = File(dedunDir, fileName)
            FileOutputStream(file).use { it.write(bytes) }
            true
        } catch (e: Exception) {
            val fallbackDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (fallbackDir != null) {
                val file = File(fallbackDir, fileName)
                FileOutputStream(file).use { it.write(bytes) }
                true
            } else {
                false
            }
        }
    }
}
