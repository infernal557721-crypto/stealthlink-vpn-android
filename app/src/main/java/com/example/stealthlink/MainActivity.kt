package com.example.stealthlink

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stealthlink.services.V2RayVpnService
import com.example.stealthlink.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED
}

class MainActivity : ComponentActivity() {
    
    // Shared state for connection
    private var connectionState = mutableStateOf(ConnectionState.DISCONNECTED)
    private var pendingConfig: String? = null
    
    // VPN permission launcher
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Permission granted - start VPN
            pendingConfig?.let { config ->
                startVpnService(config)
            }
        } else {
            // Permission denied
            connectionState.value = ConnectionState.DISCONNECTED
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StealthLinkTheme {
                MainScreen(
                    connectionState = connectionState.value,
                    onConnect = { config -> requestVpnPermissionAndConnect(config) },
                    onDisconnect = { stopVpnService() }
                )
            }
        }
    }
    
    private fun requestVpnPermissionAndConnect(config: String) {
        connectionState.value = ConnectionState.CONNECTING
        pendingConfig = config
        
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            // Permission needed - show system dialog
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            // Already have permission
            startVpnService(config)
        }
    }
    
    private fun startVpnService(config: String) {
        try {
            val intent = Intent(this, V2RayVpnService::class.java)
            intent.putExtra("V2RAY_CONFIG", config)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            // Update state after a short delay
            connectionState.value = ConnectionState.CONNECTED
        } catch (e: Exception) {
            e.printStackTrace()
            connectionState.value = ConnectionState.DISCONNECTED
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun stopVpnService() {
        val intent = Intent(this, V2RayVpnService::class.java)
        intent.action = "STOP"
        startService(intent)
        connectionState.value = ConnectionState.DISCONNECTED
    }
}

@Composable
fun MainScreen(
    connectionState: ConnectionState,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(1) }

    Scaffold(
        containerColor = DarkBackground,
        bottomBar = {
            NavigationBar(containerColor = DarkSurface) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.ShoppingCart, contentDescription = "Оплата") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = GoldPrimary)
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Главная") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = GoldPrimary)
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Настройки") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = GoldPrimary)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(DarkBackground)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (selectedTab) {
                0 -> SubscriptionScreen()
                1 -> HomeScreen(connectionState, onConnect, onDisconnect)
                2 -> Text("Настройки", color = Color.White)
            }
        }
    }
}

@Composable
fun HomeScreen(
    connectionState: ConnectionState,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Vpn Code", fontSize = 36.sp, fontWeight = FontWeight.Black, color = GoldPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        
        val statusText = when (connectionState) {
            ConnectionState.DISCONNECTED -> "ОТКЛЮЧЕНО"
            ConnectionState.CONNECTING -> "ПОДКЛЮЧЕНИЕ..."
            ConnectionState.CONNECTED -> "ПОДКЛЮЧЕНО"
        }
        val statusColor = when (connectionState) {
            ConnectionState.DISCONNECTED -> TextGray
            ConnectionState.CONNECTING -> GoldPrimary
            ConnectionState.CONNECTED -> GreenSuccess
        }
        
        Text(statusText, color = statusColor)
        
        Spacer(modifier = Modifier.height(30.dp))
        
        // Power Button
        Button(
            onClick = { 
                if (connectionState == ConnectionState.DISCONNECTED) {
                    val config = """
{
  "log": {
    "loglevel": "debug"
  },
  "dns": {
    "servers": [
      "8.8.8.8",
      "1.1.1.1"
    ]
  },
  "inbounds": [
    {
      "tag": "socks",
      "port": 10808,
      "listen": "127.0.0.1",
      "protocol": "socks",
      "sniffing": {
        "enabled": true,
        "destOverride": ["http", "tls"]
      },
      "settings": {
        "auth": "noauth",
        "udp": true
      }
    },
    {
      "tag": "transparent",
      "port": 12345,
      "listen": "127.0.0.1",
      "protocol": "dokodemo-door",
      "sniffing": {
        "enabled": true,
        "destOverride": ["http", "tls"]
      },
      "settings": {
        "network": "tcp,udp",
        "followRedirect": true
      }
    }
  ],
  "outbounds": [
    {
      "protocol": "vless",
      "settings": {
        "vnext": [
          {
            "address": "81.200.154.49",
            "port": 443,
            "users": [
              {
                "id": "ffb23eb7-669a-43c2-95fc-902b6c6b9c95",
                "encryption": "none",
                "flow": "xtls-rprx-vision"
              }
            ]
          }
        ]
      },
      "streamSettings": {
        "network": "tcp",
        "security": "reality",
        "realitySettings": {
          "fingerprint": "chrome",
          "serverName": "www.google.com",
          "publicKey": "t1mvlx-GfAiYPNoDbNzsBH0nA-EtUyDJKTGM-eavS3k",
          "shortId": "12345678",
          "spiderX": "/"
        }
      },
      "tag": "proxy"
    },
    {
      "protocol": "freedom",
      "tag": "direct"
    },
    {
      "protocol": "dns",
      "tag": "dns-out"
    }
  ],
  "routing": {
    "domainStrategy": "IPIfNonMatch",
    "rules": [
      {
        "type": "field",
        "inboundTag": ["transparent", "socks"],
        "port": 53,
        "outboundTag": "dns-out"
      },
      {
        "type": "field",
        "inboundTag": ["transparent", "socks"],
        "outboundTag": "proxy",
        "network": "tcp,udp"
      }
    ]
  }
}
                    """.trimIndent()
                    onConnect(config)
                } else if (connectionState == ConnectionState.CONNECTED) {
                    onDisconnect()
                }
            },
            modifier = Modifier.size(200.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = when (connectionState) {
                    ConnectionState.CONNECTED -> DarkGold
                    ConnectionState.CONNECTING -> DarkGold
                    else -> GoldPrimary
                }
            ),
            enabled = connectionState != ConnectionState.CONNECTING
        ) {
            if (connectionState == ConnectionState.CONNECTING) {
                CircularProgressIndicator(color = DarkBackground, modifier = Modifier.size(48.dp))
            } else {
                Text(
                    if (connectionState == ConnectionState.CONNECTED) "СТОП" else "СТАРТ",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkBackground
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Trial Button
        val context = androidx.compose.ui.platform.LocalContext.current
        TextButton(onClick = { 
            Toast.makeText(context, "Пробный период активирован!", Toast.LENGTH_SHORT).show()
        }) {
           Text("Пробный период (24 ч)", color = GoldPrimary) 
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Split Tunneling Toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurface)
                .padding(16.dp)
        ) {
            Text("Только соцсети", color = TextWhite, modifier = Modifier.weight(1f))
            Switch(checked = false, onCheckedChange = {})
        }
    }
}

@Composable
fun SubscriptionScreen() {
    val repository = remember { com.example.stealthlink.data.repository.VpnRepository() }
    var tariffs by remember { mutableStateOf<List<com.example.stealthlink.data.model.Tariff>>(emptyList()) }

    LaunchedEffect(Unit) {
        tariffs = repository.getTariffs()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally, 
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("ПРЕМИУМ", fontSize = 24.sp, color = GoldPrimary, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        
        if (tariffs.isEmpty()) {
            CircularProgressIndicator(color = GoldPrimary)
        } else {
            tariffs.forEach { tariff ->
                TariffCard(tariff.name, "${tariff.price} ₽", tariff.isHit)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = {},
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
        ) {
            Text("Продолжить", color = DarkBackground, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun TariffCard(duration: String, price: String, isHit: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
        border = if (isHit) androidx.compose.foundation.BorderStroke(2.dp, GoldPrimary) else null
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(duration, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (isHit) {
                    Text("ХИТ ПРОДАЖ", color = GoldPrimary, fontSize = 12.sp)
                }
            }
            Text(price, color = TextWhite, fontSize = 18.sp)
        }
    }
}
