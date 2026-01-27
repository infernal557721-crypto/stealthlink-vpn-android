package com.example.stealthlink.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

/**
 * In-App Update Manager for StealthLink VPN
 * Downloads updates from GitHub Releases
 */
class UpdateManager(private val context: Context) {
    
    companion object {
        private const val TAG = "UpdateManager"
        private const val GITHUB_RELEASES_API = "https://api.github.com/repos/infernal557721-crypto/stealthlink-vpn-android/releases/latest"
    }
    
    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val downloadUrl: String,
        val releaseNotes: String,
        val hasUpdate: Boolean
    )
    
    /**
     * Check for updates from GitHub Releases
     */
    suspend fun checkForUpdate(): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            val response = URL(GITHUB_RELEASES_API).readText()
            val json = JSONObject(response)
            
            val latestVersion = json.getString("tag_name").removePrefix("v")
            val releaseNotes = json.optString("body", "")
            
            // Parse version code from tag (e.g., "1.0.1" -> 101)
            val latestVersionCode = parseVersionCode(latestVersion)
            val currentVersionCode = getCurrentVersionCode()
            
            // Find APK asset download URL
            val assets = json.getJSONArray("assets")
            var downloadUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    downloadUrl = asset.getString("browser_download_url")
                    break
                }
            }
            
            Log.d(TAG, "Current: $currentVersionCode, Latest: $latestVersionCode")
            
            UpdateInfo(
                versionName = latestVersion,
                versionCode = latestVersionCode,
                downloadUrl = downloadUrl,
                releaseNotes = releaseNotes,
                hasUpdate = latestVersionCode > currentVersionCode
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates", e)
            UpdateInfo("", 0, "", "", false)
        }
    }
    
    /**
     * Download and install update
     */
    fun downloadAndInstall(downloadUrl: String, onProgress: (Int) -> Unit, onComplete: () -> Unit) {
        val fileName = "stealthlink-update.apk"
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadDir, fileName)
        
        // Delete old file if exists
        if (file.exists()) file.delete()
        
        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("StealthLink VPN Update")
            .setDescription("Скачивание обновления...")
            .setDestinationUri(Uri.fromFile(file))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)
        
        // Register receiver for download complete
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
                if (id == downloadId) {
                    onComplete()
                    installApk(file)
                    context.unregisterReceiver(this)
                }
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }
    
    /**
     * Install APK file
     */
    private fun installApk(file: File) {
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } else {
                Uri.fromFile(file)
            }
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK", e)
        }
    }
    
    private fun getCurrentVersionCode(): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }
    
    private fun parseVersionCode(version: String): Int {
        return try {
            val parts = version.split(".")
            when (parts.size) {
                1 -> parts[0].toInt() * 10000
                2 -> parts[0].toInt() * 10000 + parts[1].toInt() * 100
                else -> parts[0].toInt() * 10000 + parts[1].toInt() * 100 + parts[2].toInt()
            }
        } catch (e: Exception) {
            1
        }
    }
}
