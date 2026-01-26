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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var coreController: CoreController? = null
    private var pfd: ParcelFileDescriptor? = null

    companion object {
        private const val TAG = "V2RayVpnService"
        private const val NOTIFICATION_ID = 1
        private const val VPN_MTU = 1500
        private const val PRIVATE_VLAN4_CLIENT = "10.0.0.1"
        private const val PRIVATE_VLAN4_ROUTER = "10.0.0.2"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent?.action
        Log.d(TAG, "onStartCommand: action=$command")
        
        if (command == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }

        val configContent = intent?.getStringExtra("V2RAY_CONFIG")
        if (configContent.isNullOrEmpty()) {
            Log.e(TAG, "No config provided")
            if (!isRunning) stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification())
        startVpn(configContent)
        return START_STICKY
    }

    private fun startVpn(config: String) {
        if (isRunning) {
            Log.w(TAG, "VPN already running")
            return
        }
        isRunning = true

        scope.launch {
            try {
                Log.d(TAG, "=== Starting VPN ===")
                
                // 1. Copy assets first
                val assetPath = filesDir.absolutePath
                copyAssets(assetPath)
                Log.d(TAG, "Assets copied to: $assetPath")
                
                // 2. Initialize Xray environment
                Libv2ray.initCoreEnv(assetPath, "")
                Log.d(TAG, "Xray environment initialized")

                // 3. Establish VPN TUN interface
                val fd = establishVpn()
                Log.d(TAG, "VPN TUN established with FD: $fd")

                // 4. Create core controller with callback handler
                coreController = Libv2ray.newCoreController(this@V2RayVpnService)
                Log.d(TAG, "Core controller created")

                // 5. Start the Xray core with config and TUN fd
                Log.d(TAG, "Starting Xray core with TUN fd=$fd")
                Log.d(TAG, "Config: ${config.take(200)}...")
                coreController?.startLoop(config, fd)
                Log.d(TAG, "=== Xray core started successfully ===")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                withContext(Dispatchers.Main) {
                    stopVpn()
                }
            }
        }
    }

    private fun establishVpn(): Int {
        val builder = Builder()
        
        // Basic TUN configuration
        builder.setSession("StealthLink VPN")
        builder.setMtu(VPN_MTU)
        
        // IPv4 configuration
        builder.addAddress(PRIVATE_VLAN4_CLIENT, 30)
        
        // Route ALL traffic through VPN
        builder.addRoute("0.0.0.0", 0)
        
        // DNS servers
        builder.addDnsServer("8.8.8.8")
        builder.addDnsServer("1.1.1.1")
        builder.addDnsServer("8.8.4.4")
        
        // CRITICAL: Exclude our own app to prevent routing loop!
        try {
            builder.addDisallowedApplication(packageName)
            Log.d(TAG, "Excluded app from VPN: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to exclude app", e)
        }
        
        // Android Q+ settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        
        // Establish the TUN interface
        pfd = builder.establish() ?: throw Exception("Failed to establish VPN - check permissions")
        
        return pfd!!.fd
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
                    Log.d(TAG, "Copied $filename (${file.length()} bytes)")
                } else {
                    Log.d(TAG, "Asset already exists: $filename (${file.length()} bytes)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy asset: $filename", e)
            }
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "=== Stopping VPN ===")
        isRunning = false
        
        try {
            coreController?.stopLoop()
            coreController = null
            Log.d(TAG, "Core stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping core", e)
        }
        
        try {
            pfd?.close()
            pfd = null
            Log.d(TAG, "TUN closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TUN", e)
        }
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        stopVpn()
        scope.cancel()
    }

    override fun onRevoke() {
        super.onRevoke()
        Log.d(TAG, "VPN permission revoked")
        stopVpn()
    }

    // CoreCallbackHandler Implementation
    override fun startup(): Long {
        Log.d(TAG, ">>> Xray Core Startup callback")
        return 0
    }

    override fun shutdown(): Long {
        Log.d(TAG, ">>> Xray Core Shutdown callback")
        return 0
    }

    override fun onEmitStatus(code: Long, msg: String?): Long {
        Log.d(TAG, ">>> Xray Status [$code]: $msg")
        return 0
    }
    
    private fun createNotification(): Notification {
        val channelId = "vpn_service_channel"
        val channelName = "VPN Service"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            channel.description = "StealthLink VPN Service"
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("StealthLink VPN")
            .setContentText("VPN подключен")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
