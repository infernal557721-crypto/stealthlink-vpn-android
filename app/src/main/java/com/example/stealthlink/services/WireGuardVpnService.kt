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
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.*
import java.net.InetAddress

/**
 * WireGuard VPN Service for StealthLink
 */
class WireGuardVpnService : VpnService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var vpnInterface: ParcelFileDescriptor? = null
    private var goBackend: com.wireguard.android.backend.GoBackend? = null
    private var tunnel: com.wireguard.android.backend.Tunnel? = null

    companion object {
        private const val TAG = "WireGuardVpnService"
        private const val NOTIFICATION_ID = 1
        
        // Server configuration
        const val SERVER_PUBLIC_KEY = "Xm8lIKPPtOT5L3B3AdoBijVXOQG+3fbdN0dxFMfVt3Y="
        const val SERVER_ENDPOINT = "81.200.154.49:51820"
        const val CLIENT_PRIVATE_KEY = "4KfsS4zDzVkSai2f3UMvHtw27otfuqjjSNvhVGy82Gg="
        const val CLIENT_ADDRESS = "10.66.66.2/32"
        const val DNS_SERVERS = "8.8.8.8, 1.1.1.1"
    }

    override fun onCreate() {
        super.onCreate()
        goBackend = com.wireguard.android.backend.GoBackend(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent?.action
        Log.d(TAG, "onStartCommand: action=$command")
        
        if (command == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification())
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) {
            Log.w(TAG, "VPN already running")
            return
        }
        isRunning = true

        scope.launch {
            try {
                Log.d(TAG, "=== Starting WireGuard VPN ===")
                
                // Create WireGuard config
                val config = createWireGuardConfig()
                
                // Create tunnel
                tunnel = object : com.wireguard.android.backend.Tunnel {
                    override fun getName() = "stealthlink"
                    override fun onStateChange(newState: com.wireguard.android.backend.Tunnel.State) {
                        Log.d(TAG, "Tunnel state changed: $newState")
                    }
                }
                
                // Start the tunnel
                goBackend?.setState(tunnel!!, com.wireguard.android.backend.Tunnel.State.UP, config)
                
                Log.d(TAG, "=== WireGuard VPN connected ===")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                withContext(Dispatchers.Main) {
                    stopVpn()
                }
            }
        }
    }

    private fun createWireGuardConfig(): Config {
        val interfaceBuilder = Interface.Builder()
        interfaceBuilder.parsePrivateKey(CLIENT_PRIVATE_KEY)
        interfaceBuilder.parseAddresses(CLIENT_ADDRESS)
        interfaceBuilder.parseDnsServers(DNS_SERVERS)
        
        val peerBuilder = Peer.Builder()
        peerBuilder.parsePublicKey(SERVER_PUBLIC_KEY)
        peerBuilder.parseEndpoint(SERVER_ENDPOINT)
        peerBuilder.parseAllowedIPs("0.0.0.0/0")  // Route all traffic
        peerBuilder.parsePersistentKeepalive("25")
        
        return Config.Builder()
            .setInterface(interfaceBuilder.build())
            .addPeer(peerBuilder.build())
            .build()
    }

    private fun stopVpn() {
        Log.d(TAG, "=== Stopping WireGuard VPN ===")
        isRunning = false
        
        try {
            tunnel?.let { 
                goBackend?.setState(it, com.wireguard.android.backend.Tunnel.State.DOWN, null)
            }
            tunnel = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WireGuard", e)
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
