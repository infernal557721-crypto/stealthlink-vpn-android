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

    private var clientPrivateKey: String? = null
    private var clientAddress: String? = null
    private var dnsServers: String? = null
    private var serverPublicKey: String? = null
    private var serverEndpoint: String? = null

    companion object {
        private const val TAG = "WireGuardVpnService"
        private const val NOTIFICATION_ID = 1
        
        const val EXTRA_PRIVATE_KEY = "private_key"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_DNS = "dns"
        const val EXTRA_PUBLIC_KEY = "public_key"
        const val EXTRA_ENDPOINT = "endpoint"
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

        // extract config
        clientPrivateKey = intent?.getStringExtra(EXTRA_PRIVATE_KEY)
        clientAddress = intent?.getStringExtra(EXTRA_ADDRESS)
        dnsServers = intent?.getStringExtra(EXTRA_DNS)
        serverPublicKey = intent?.getStringExtra(EXTRA_PUBLIC_KEY)
        serverEndpoint = intent?.getStringExtra(EXTRA_ENDPOINT)

        if (clientPrivateKey == null || serverEndpoint == null) {
            Log.e(TAG, "Missing config extras")
            stopSelf()
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
        interfaceBuilder.parsePrivateKey(clientPrivateKey!!)
        interfaceBuilder.parseAddresses(clientAddress!!)
        interfaceBuilder.parseDnsServers(dnsServers!!)
        
        val peerBuilder = Peer.Builder()
        peerBuilder.parsePublicKey(serverPublicKey!!)
        peerBuilder.parseEndpoint(serverEndpoint!!)
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
