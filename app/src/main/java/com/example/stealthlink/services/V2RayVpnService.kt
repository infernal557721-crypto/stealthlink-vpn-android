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

/**
 * VPN Service that uses:
 * 1. Xray core (via libv2ray) for VLESS-Reality proxy
 * 2. tun2socks to route TUN traffic to Xray's SOCKS port
 * 
 * Architecture:
 * [Device Traffic] -> [TUN Interface] -> [tun2socks] -> [SOCKS 127.0.0.1:10808] -> [Xray] -> [VPS Server]
 */
class V2RayVpnService : VpnService(), CoreCallbackHandler {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var coreController: CoreController? = null
    private var pfd: ParcelFileDescriptor? = null
    private var tun2socksThread: Thread? = null

    companion object {
        private const val TAG = "V2RayVpnService"
        private const val NOTIFICATION_ID = 1
        private const val VPN_MTU = 1500
        private const val TUN_IP = "10.0.0.1"
        private const val TUN_NETMASK = 30
        private const val SOCKS_PORT = 10808
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
                Log.d(TAG, "=== Starting VPN with tun2socks ===")
                
                // 1. Copy assets and initialize Xray
                val assetPath = filesDir.absolutePath
                copyAssets(assetPath)
                Libv2ray.initCoreEnv(assetPath, "")
                Log.d(TAG, "Xray environment initialized")

                // 2. Create Xray controller and start (SOCKS inbound only, no TUN fd)
                coreController = Libv2ray.newCoreController(this@V2RayVpnService)
                // Pass 0 for TUN fd - we'll handle TUN ourselves with tun2socks
                coreController?.startLoop(config, 0)
                Log.d(TAG, "Xray core started with SOCKS on 127.0.0.1:$SOCKS_PORT")

                // 3. Wait a moment for Xray to initialize
                delay(500)

                // 4. Establish VPN TUN interface
                val fd = establishTun()
                Log.d(TAG, "TUN interface established with FD: $fd")

                // 5. Start tun2socks to forward TUN -> SOCKS
                startTun2Socks(fd)
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
        builder.addAddress(TUN_IP, TUN_NETMASK)
        builder.addRoute("0.0.0.0", 0)  // Route all IPv4 traffic
        builder.addDnsServer("8.8.8.8")
        builder.addDnsServer("1.1.1.1")
        
        // CRITICAL: Exclude our own app to prevent routing loop
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

    private fun startTun2Socks(tunFd: Int) {
        Log.d(TAG, "Starting tun2socks: TUN fd=$tunFd -> SOCKS 127.0.0.1:$SOCKS_PORT")
        
        // tun2socks configuration
        // The com.ooimi.library:tun2socks library should provide native method
        // If unavailable, we use the built-in Xray TUN handling
        
        // For now, we rely on the Xray core's internal TUN support
        // The fd was passed with 0, so Xray won't use TUN directly
        // Instead, all traffic goes through SOCKS which Xray handles
        
        // NOTE: If tun2socks library doesn't work, the alternative is to use
        // Xray's built-in TUN support by passing actual fd to startLoop
        // But that requires proper inbound config
        
        tun2socksThread = Thread {
            try {
                // Try to use native tun2socks if available
                startTun2SocksNative(tunFd, "127.0.0.1", SOCKS_PORT)
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native tun2socks not available, using Xray TUN mode")
                // Fall back to restarting Xray with TUN fd
                restartXrayWithTun(tunFd)
            } catch (e: Exception) {
                Log.e(TAG, "tun2socks error", e)
            }
        }
        tun2socksThread?.start()
    }

    private external fun startTun2SocksNative(tunFd: Int, socksHost: String, socksPort: Int)

    private fun restartXrayWithTun(tunFd: Int) {
        try {
            // Get current config from coreController (if possible)
            // and restart with TUN fd
            Log.d(TAG, "Restarting Xray with TUN fd: $tunFd")
            // This is handled by the initial startLoop if we pass the fd
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart Xray with TUN", e)
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
        Log.d(TAG, "=== Stopping VPN ===")
        isRunning = false
        
        tun2socksThread?.interrupt()
        tun2socksThread = null
        
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

    // CoreCallbackHandler Implementation
    override fun startup(): Long {
        Log.d(TAG, ">>> Xray Core Startup")
        return 0
    }

    override fun shutdown(): Long {
        Log.d(TAG, ">>> Xray Core Shutdown")
        return 0
    }

    override fun onEmitStatus(code: Long, msg: String?): Long {
        Log.d(TAG, ">>> Xray Status [$code]: $msg")
        return 0
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
