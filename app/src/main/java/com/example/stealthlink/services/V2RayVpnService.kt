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
import go.Seq
import libv2ray.Libv2ray
import libv2ray.CoreController
import libv2ray.CoreCallbackHandler
import java.io.File
import kotlinx.coroutines.*

/**
 * VPN Service that uses Xray core (via libv2ray) for VLESS-Reality proxy.
 * 
 * Architecture:
 * [Device Traffic] -> [TUN Interface] -> [Xray Core] -> [VPS Server]
 */
class V2RayVpnService : VpnService(), CoreCallbackHandler {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var coreController: CoreController? = null
    private var pfd: ParcelFileDescriptor? = null

    companion object {
        private const val TAG = "V2RayVpnService"
        private const val NOTIFICATION_ID = 1
        private const val VPN_MTU = 1500
        private const val TUN_IP = "26.26.26.1"
        private const val TUN_PREFIX = 30
        private const val DNS_PRIMARY = "1.1.1.1"
        private const val DNS_SECONDARY = "8.8.8.8"
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
                Log.d(TAG, "=== Starting VPN with Xray Core ===")
                
                // 1. Initialize Xray environment
                val assetPath = filesDir.absolutePath
                copyAssets(assetPath)
                
                // Initialize Go/Seq runtime
                Seq.setContext(applicationContext)
                
                // Initialize Xray core environment
                Libv2ray.initCoreEnv(assetPath, "")
                Log.d(TAG, "Xray environment initialized at: $assetPath")

                // 2. Establish VPN TUN interface first
                val tunFd = establishTun()
                Log.d(TAG, "TUN interface established with FD: $tunFd")

                // 3. Create CoreController and start Xray with TUN fd
                coreController = Libv2ray.newCoreController(this@V2RayVpnService)
                
                // Start the Xray core with config and TUN file descriptor
                coreController?.startLoop(config, tunFd.toLong())
                
                Log.d(TAG, "=== VPN fully connected ===")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                withContext(Dispatchers.Main) {
                    stopVpn()
                }
            }
        }
    }
    
    private fun establishTun(): Int {
        val builder = Builder()
        
        builder.setSession("StealthLink VPN")
        builder.setMtu(VPN_MTU)
        builder.addAddress(TUN_IP, TUN_PREFIX)
        builder.addRoute("0.0.0.0", 0)  // Route all IPv4 traffic
        builder.addDnsServer(DNS_PRIMARY)
        builder.addDnsServer(DNS_SECONDARY)
        
        // Exclude our own app to prevent routing loop
        try {
            builder.addDisallowedApplication(packageName)
            Log.d(TAG, "Excluded app from VPN: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to exclude app", e)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        
        pfd = builder.establish() ?: throw Exception("Failed to establish VPN")
        return pfd!!.fd
    }

    private fun copyAssets(targetDir: String) {
        // geoip.dat and geosite.dat are already in the AAR assets
        // They will be automatically available from the AAR
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
                // Assets might be in the AAR already
                Log.w(TAG, "Asset $filename not found in app assets, using AAR assets")
            }
        }
    }

    // CoreCallbackHandler implementation
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

    private fun stopVpn() {
        Log.d(TAG, "=== Stopping VPN ===")
        isRunning = false
        
        try {
            coreController?.stopLoop()
            coreController = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Xray", e)
        }
        
        try {
            pfd?.close()
            pfd = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TUN", e)
        }
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        scope.cancel()
    }

    override fun onRevoke() {
        super.onRevoke()
        Log.d(TAG, "VPN permission revoked")
        stopVpn()
    }
    
    private fun createNotification(): Notification {
        val channelId = "vpn_service_channel"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "VPN Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
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
