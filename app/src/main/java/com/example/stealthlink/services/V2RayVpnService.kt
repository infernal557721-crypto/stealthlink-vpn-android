package com.example.stealthlink.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
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
            stopSelf()
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
                // 1. Establish VPN interface (TUN)
                // We must do this on the main thread or ensure builder usage is correct, 
                // but usually builder.establish() is blocking is fine.
                // We need to parse routes/DNS from config or hardcode for now.
                if (pfd == null) {
                    val builder = Builder()
                    builder.setSession("VpnCode")
                    builder.setMtu(1500)
                    builder.addAddress("10.0.1.10", 24)
                    builder.addRoute("0.0.0.0", 0)
                    builder.addDnsServer("8.8.8.8")
                    builder.addDnsServer("1.1.1.1")
                    
                    // Allow the app to bypass VPN to reach the proxy server itself?
                    // V2Ray protects its own socket (fwmark), but we might need 
                    // builder.addDisallowedApplication(packageName) if V2Ray doesn't protect properly.
                    // Usually core.Dialer protects its socket.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                         builder.setMetered(false)
                    }
                    
                    pfd = builder.establish()
                }

                val fd = pfd?.fd ?: throw Exception("Failed to establish VPN")

                // 2. Initialize Core Env
                Libv2ray.initCoreEnv(filesDir.absolutePath, "asset_path")

                // 3. Create Controller
                coreController = Libv2ray.newCoreController(this@V2RayVpnService)

                // 4. Start Loop with Config AND FD
                // Note: tunFd is int (int32 in Go), passing local fd
                Log.d(TAG, "Starting Xray Core with FD: $fd")
                coreController?.startLoop(config, fd)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start V2Ray", e)
                stopVpn()
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
    
    // CoreCallbackHandler implementation
    // Go int -> Java long? Assuming long based on common gomobile output for 64-bit target
    override fun startup(): Long {
        Log.d(TAG, "Core Startup Callback")
        return 0
    }

    override fun shutdown(): Long {
        Log.d(TAG, "Core Shutdown Callback")
        return 0
    }

    override fun onEmitStatus(code: Long, msg: String?): Long {
        Log.d(TAG, "Core Status [$code]: $msg")
        return 0
    }

    // Unnecessary methods from old implementation removed (setup)

    private fun createNotification(): Notification {
        val channelId = "vpn_service_channel"
        val channelName = "VPN Service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Vpn Code")
            .setContentText("VPN Connected")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val TAG = "V2RayVpnService"
        private const val NOTIFICATION_ID = 1
    }
}
