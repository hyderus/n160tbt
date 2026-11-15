package com.example.n160

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class AppUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "AppUpdateManager"
        const val CHANNEL_ID = "n160_updates_channel"
        const val NOTIFICATION_ID = 2001
        const val UPDATE_JSON_URL = "https://raw.githubusercontent.com/hyderus/n160tbt/main/version.json"
        var pendingInstallFile: File? = null
    }

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val releaseNotes: String,
        val publishedAt: String
    )

    sealed class UpdateCheckResult {
        data class Available(val info: UpdateInfo, val currentCode: Int, val currentName: String) : UpdateCheckResult()
        data class Latest(val currentCode: Int, val currentName: String) : UpdateCheckResult()
        data class Error(val error: String) : UpdateCheckResult()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for Pulsar N160 app updates"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            3
        }
    }

    fun getCurrentVersionName(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.2"
        } catch (e: Exception) {
            "1.2"
        }
    }

    fun checkForUpdate(isManualCheck: Boolean = false, onResult: (UpdateCheckResult) -> Unit) {
        thread(start = true, name = "AppUpdateCheckThread") {
            try {
                val currentCode = getCurrentVersionCode()
                val currentName = getCurrentVersionName()

                // Add cache-busting timestamp query parameter
                val urlString = "$UPDATE_JSON_URL?t=${System.currentTimeMillis()}"
                val url = URL(urlString)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "GET"
                    useCaches = false
                    setRequestProperty("User-Agent", "PulsarN160-App")
                }

                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    mainHandler.post {
                        onResult(UpdateCheckResult.Error("HTTP ${conn.responseCode}: Unable to reach update server"))
                    }
                    return@thread
                }

                val jsonString = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonString)

                val serverCode = json.optInt("versionCode", 0)
                val serverName = json.optString("versionName", "1.2")
                val apkUrl = json.optString("apkUrl", "https://raw.githubusercontent.com/hyderus/n160tbt/main/n160.apk")
                val releaseNotes = json.optString("releaseNotes", "New update available")
                val publishedAt = json.optString("publishedAt", "")

                val updateInfo = UpdateInfo(serverCode, serverName, apkUrl, releaseNotes, publishedAt)

                if (serverCode > currentCode) {
                    Log.i(TAG, "Update available: server=$serverCode ($serverName) > local=$currentCode ($currentName)")
                    showUpdateNotification(updateInfo)
                    mainHandler.post {
                        onResult(UpdateCheckResult.Available(updateInfo, currentCode, currentName))
                    }
                } else {
                    Log.i(TAG, "App is on latest version: local=$currentCode >= server=$serverCode")
                    mainHandler.post {
                        onResult(UpdateCheckResult.Latest(currentCode, currentName))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
                mainHandler.post {
                    onResult(UpdateCheckResult.Error(e.message ?: "Failed to check for updates"))
                }
            }
        }
    }

    fun showUpdateNotification(info: UpdateInfo) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = "ACTION_OPEN_UPDATE"
                putExtra("NEW_VERSION_NAME", info.versionName)
                putExtra("NEW_VERSION_CODE", info.versionCode)
                putExtra("RELEASE_NOTES", info.releaseNotes)
                putExtra("APK_URL", info.apkUrl)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val largeIcon = try {
                BitmapFactory.decodeResource(context.resources, R.drawable.ic_notification_large)
            } catch (e: Exception) {
                null
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("New Update Available: v${info.versionName}")
                .setContentText("Tap to download and install the new Pulsar N160 update")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Version ${info.versionName} is ready to download.\n${info.releaseNotes}"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            if (largeIcon != null) {
                builder.setLargeIcon(largeIcon)
            }

            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Error posting update notification", e)
        }
    }

    fun downloadAndInstallUpdate(
        apkUrl: String,
        onProgress: (progress: Int, currentBytes: Long, totalBytes: Long) -> Unit,
        onError: (String) -> Unit
    ) {
        thread(start = true, name = "ApkDownloadThread") {
            try {
                var currentUrl = apkUrl
                var connection: HttpURLConnection
                var redirects = 0
                val maxRedirects = 5

                // Follow HTTP 301/302 redirects (standard for GitHub raw links)
                while (true) {
                    val url = URL(currentUrl)
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 15000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "PulsarN160-Updater")
                    }

                    val code = connection.responseCode
                    if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                        val newLocation = connection.getHeaderField("Location")
                        if (newLocation != null && redirects < maxRedirects) {
                            currentUrl = newLocation
                            redirects++
                            continue
                        }
                    }
                    break
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    mainHandler.post {
                        onError("Download failed: HTTP ${connection.responseCode}")
                    }
                    return@thread
                }

                val totalLength = connection.contentLength.toLong()
                val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
                if (!targetDir.exists()) targetDir.mkdirs()
                val apkFile = File(targetDir, "n160_update.apk")
                if (apkFile.exists()) apkFile.delete()

                var downloaded: Long = 0
                val buffer = ByteArray(8192)

                connection.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        var bytesRead: Int
                        var lastReportedTime = System.currentTimeMillis()

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastReportedTime > 250 || (totalLength > 0 && downloaded == totalLength)) {
                                lastReportedTime = now
                                val percent = if (totalLength > 0) ((downloaded * 100) / totalLength).toInt() else 0
                                mainHandler.post {
                                    onProgress(percent, downloaded, totalLength)
                                }
                            }
                        }
                        output.flush()
                    }
                }

                Log.i(TAG, "APK download finished: size=${apkFile.length()} bytes at ${apkFile.absolutePath}")

                mainHandler.post {
                    onProgress(100, downloaded, totalLength)
                    installApk(apkFile)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Download error", e)
                mainHandler.post {
                    onError("Download error: ${e.localizedMessage ?: "Network failed"}")
                }
            }
        }
    }

    fun installApk(file: File) {
        try {
            if (!file.exists() || file.length() == 0L) {
                Log.e(TAG, "Cannot install empty or non-existent file: ${file.absolutePath}")
                return
            }

            // On Android 8.0+ verify package install permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Log.i(TAG, "Requesting ACTION_MANAGE_UNKNOWN_APP_SOURCES permission")
                    pendingInstallFile = file
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return
                }
            }

            pendingInstallFile = null
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            Log.i(TAG, "Starting system package installer with URI: $apkUri")
            context.startActivity(installIntent)

        } catch (e: Exception) {
            Log.e(TAG, "Error triggering APK installation", e)
        }
    }

    fun checkPendingInstallOnResume() {
        val file = pendingInstallFile
        if (file != null && file.exists()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) {
                installApk(file)
            }
        }
    }
}
