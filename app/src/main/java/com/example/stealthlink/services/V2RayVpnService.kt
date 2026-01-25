
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
import libv2ray.V2RayPoint
import libv2ray.V2RayVPNServiceSupportsSet
import java.io.File
import kotlinx.coroutines.*

class V2RayVpnService : VpnService(), V2RayVPNServiceSupportsSet {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var isRunning = false
    private var pfd: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent?.action
        if (command == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }

        val configContent = intent?.getStringExtra("V2RAY_CONFIG")
        if (configContent.isNullOrEmpty()) {
            // If already running (from UI toggle), ignore? 
            // Or stop if no config?
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
                
                // 1. Write config to file
                val configFile = File(filesDir, "config.json")
                configFile.writeText(config)

                // 2. Set strict V2RayPoint reference for callback
                V2RayPoint.setV2RayVPNServiceSupportsSet(this@V2RayVpnService)

                // 3. Start V2Ray
                // The library calls 'setup(params)' internally when it needs the TUN interface.
                // We just need to trigger the start.
                val result = Libv2ray.startV2Ray(
                    filesDir.absolutePath, 
                    "config.json", 
                    "assets" // 'assets' is a dummy path if we don't use geoip/site
                )
                 
                if (!result.isNullOrEmpty()) { 
                   Log.d(TAG, "V2Ray start result/pid: $result")
                } else {
                   Log.e(TAG, "V2Ray start returned empty string (failure?)")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start V2Ray", e)
                stopVpn()
            }
        }
    }

    private fun stopVpn() {
        isRunning = false
        try {
            Libv2ray.stopV2Ray()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Libv2ray", e)
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

    /**
     * Callback from Go Native Lib to establish VPN interface.
     * Expects 'fd' (int) as return value.
     */
    override fun setup(parameters: String): Long {
        Log.d(TAG, "Native setup() requested. Params: $parameters")
        
        try {
            if (pfd != null) {
                pfd?.close()
                pfd = null
            }
            
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
            val fd = pfd?.fd ?: 0
            Log.d(TAG, "VPN Interface established. FD: $fd")
            
            // Go 'int' on 64-bit is 64-bit, so returning Long is correct for 'int' return type in Java/Kotlin 
            // representing Go 'int'.
            return fd.toLong()
            
        } catch (e: Exception) {
            Log.e(TAG, "VPN Setup failed", e)
            return 0
        }
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
            .setContentText("VPN Connected via V2Ray")
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
