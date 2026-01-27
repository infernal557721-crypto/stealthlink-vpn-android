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
import libv2ray.V2RayPoint
import libv2ray.V2RayVPNServiceSupportsSet
import java.io.File
import kotlinx.coroutines.*

/**
 * VPN Service that uses Xray core (via AndroidLibXrayLite) for VLESS-Reality proxy.
 * 
 * Architecture:
 * [Device Traffic] -> [TUN Interface] -> [Xray Core] -> [VPS Server]
 */
class V2RayVpnService : VpnService(), V2RayVPNServiceSupportsSet {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var v2rayPoint: V2RayPoint? = null
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
                
                // Initialize Seq (Go runtime)
                Seq.setContext(applicationContext)
                
                Log.d(TAG, "Xray environment initialized at: $assetPath")

                // 2. Create V2RayPoint
                v2rayPoint = Libv2ray.newV2RayPoint(this@V2RayVpnService, false)
                
                // 3. Configure and start Xray
                v2rayPoint?.configureFileContent = config
                v2rayPoint?.domainName = extractDomainFromConfig(config)
                
                Log.d(TAG, "Starting Xray core...")
                v2rayPoint?.runLoop(true)
                
                Log.d(TAG, "=== VPN fully connected ===")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                withContext(Dispatchers.Main) {
                    stopVpn()
                }
            }
        }
    }
    
    private fun extractDomainFromConfig(config: String): String {
        // Try to extract domain from outbound config
        try {
            val regex = """"address"\s*:\s*"([^"]+)"""".toRegex()
            val match = regex.find(config)
            return match?.groupValues?.get(1) ?: "v2ray.local"
        } catch (e: Exception) {
            return "v2ray.local"
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

    // V2RayVPNServiceSupportsSet implementation
    override fun setup(parameters: String): Long {
        Log.d(TAG, ">>> setup() called with parameters length: ${parameters.length}")
        
        try {
            // Establish VPN tunnel
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
            
            pfd = builder.establish()
            if (pfd != null) {
                Log.d(TAG, "VPN established with FD: ${pfd!!.fd}")
                return pfd!!.fd.toLong()
            } else {
                Log.e(TAG, "Failed to establish VPN")
                return -1
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in setup()", e)
            return -1
        }
    }
    
    override fun prepare(): Long {
        Log.d(TAG, ">>> prepare() called")
        return 0
    }
    
    override fun shutdown(): Long {
        Log.d(TAG, ">>> shutdown() called")
        return 0
    }
    
    override fun protect(fd: Long): Boolean {
        Log.d(TAG, ">>> protect() called for fd: $fd")
        return protect(fd.toInt())
    }
    
    override fun onEmitStatus(status: Long, msg: String?): Long {
        Log.d(TAG, ">>> Xray Status [$status]: $msg")
        return 0
    }
    
    override fun sendFd(): Long {
        Log.d(TAG, ">>> sendFd() called")
        return pfd?.fd?.toLong() ?: -1
    }

    private fun stopVpn() {
        Log.d(TAG, "=== Stopping VPN ===")
        isRunning = false
        
        try {
            v2rayPoint?.stopLoop()
            v2rayPoint = null
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
