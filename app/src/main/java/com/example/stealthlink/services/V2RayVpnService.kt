package com.example.stealthlink.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.stealthlink.MainActivity
import com.example.stealthlink.R
import libv2ray.Libv2ray
import libv2ray.CoreController
import libv2ray.CoreCallbackHandler
import java.io.File
import kotlinx.coroutines.*

class V2RayVpnService : VpnService(), CoreCallbackHandler {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var isRunning = false
    private var coreController: CoreController? = null
    private var pfd: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent?.action
        if (command == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }

        val configContent = intent?.getStringExtra("V2RAY_CONFIG")
        if (configContent.isNullOrEmpty()) {
            if (!isRunning) stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification())
        startVpn(configContent)
        return START_STICKY
    }

    private fun startVpn(config: String) {
        if (isRunning) return
        isRunning = true

        scope.launch {
            try {
                Log.d(TAG, "Starting V2Ray init...")
                
                // 1. Establish VPN interface (TUN)
                if (pfd == null) {
                    val builder = Builder()
                    builder.setSession("StealthLink VPN")
                    builder.setMtu(1500)
                    builder.addAddress("10.0.1.10", 24)
                    builder.addRoute("0.0.0.0", 0)
                    builder.addDnsServer("8.8.8.8")
                    builder.addDnsServer("1.1.1.1")
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                         builder.setMetered(false)
                    }
                    
                    pfd = builder.establish()
                }

                val fd = pfd?.fd ?: throw Exception("Failed to establish VPN")
                Log.d(TAG, "VPN Interface established. FD: $fd")

                // 2. Initialize Core Env and Assets
                val assetPath = filesDir.absolutePath
                copyAssets(assetPath)
                Libv2ray.initCoreEnv(assetPath, "asset_path")

                // 3. Create Controller
                coreController = Libv2ray.newCoreController(this@V2RayVpnService)

                // 4. Start Loop with Config AND FD
                coreController?.startLoop(config, fd)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start V2Ray", e)
                stopVpn()
            }
        }
    }


    private fun copyAssets(targetDir: String) {
        val assetsToCopy = listOf("geoip.dat", "geosite.dat")
        assetsToCopy.forEach { filename ->
            try {
                val file = File(targetDir, filename)
                if (!file.exists()) {
                    Log.d(TAG, "Copying asset: $filename")
                    assets.open(filename).use { input ->
                        java.io.FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy asset: $filename", e)
            }
        }
    }

    private fun stopVpn() {
        isRunning = false
        try {
            coreController?.stopLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping core", e)
        }
        
        try {
           pfd?.close()
           pfd = null
        } catch (e: Exception) { 
           Log.e(TAG, "Error closing fd", e)
        }
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        scope.cancel()
    }

    // CoreCallbackHandler Implementation for V5+
    
    override fun startup(): Long {
        Log.d(TAG, "Core Startup")
        return 0
    }

    override fun shutdown(): Long {
        Log.d(TAG, "Core Shutdown")
        return 0
    }

    override fun onEmitStatus(code: Long, msg: String?): Long {
        Log.d(TAG, "Core Status [$code]: $msg")
        return 0
    }
    
    private fun createNotification(): Notification {
        val channelId = "vpn_service_channel"
        val channelName = "VPN Service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("StealthLink")
            .setContentText("VPN Connected")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    companion object {
        private const val TAG = "V2RayVpnService"
        private const val NOTIFICATION_ID = 1
    }
}
