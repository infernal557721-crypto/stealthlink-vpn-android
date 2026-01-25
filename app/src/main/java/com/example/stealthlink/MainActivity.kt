package com.example.stealthlink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stealthlink.ui.theme.*
import android.content.Intent
import android.os.Build
import com.example.stealthlink.services.V2RayVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StealthLinkTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    var selectedTab by remember { mutableStateOf(1) } // 0=Pay, 1=Home, 2=Settings

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
                1 -> HomeScreen()
                2 -> Text("Настройки", color = Color.White)
            }
        }
    }
}

@Composable
fun HomeScreen() {
    var connectionState by remember { mutableStateOf(ConnectionState.DISCONNECTED) } // DISCONNECTED, CONNECTING, CONNECTED
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Enlarge Title and make it GoldPrimary
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
                    connectionState = ConnectionState.CONNECTING
                    
                    try {
                         val config = """
{
  "log": {
    "loglevel": "warning"
  },
  "inbounds": [
    {
      "port": 10808,
      "listen": "127.0.0.1",
      "protocol": "socks",
      "settings": {
        "udp": true
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
                "id": "REPLACE_ME_WITH_REAL_UUID",
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
          "publicKey": "REPLACE_ME_WITH_REAL_PUBKEY",
          "shortId": "12345678",
          "spiderX": "/"
        }
      },
      "tag": "proxy"
    },
    {
      "protocol": "freedom",
      "tag": "direct"
    }
  ],
  "routing": {
    "domainStrategy": "IPIfNonMatch",
    "rules": [
      {
        "type": "field",
        "outboundTag": "direct",
        "ip": [
          "geoip:private",
          "geoip:cn"
        ]
      },
      {
        "type": "field",
        "outboundTag": "proxy",
        "network": "tcp,udp"
      }
    ]
  }
}
                         """.trimIndent()

                        val intent = Intent(context, V2RayVpnService::class.java)
                        intent.putExtra("V2RAY_CONFIG", config)
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                        
                        scope.launch {
                            delay(1000)
                            connectionState = ConnectionState.CONNECTED
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        connectionState = ConnectionState.DISCONNECTED
                        android.widget.Toast.makeText(context, "Ошибка запуска: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }

                } else if (connectionState == ConnectionState.CONNECTED) {
                    connectionState = ConnectionState.DISCONNECTED
                    val intent = Intent(context, V2RayVpnService::class.java)
                    intent.action = "STOP"
                    context.startService(intent)
                }
            },
            modifier = Modifier.size(200.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = when (connectionState) {
                    ConnectionState.CONNECTED -> com.example.stealthlink.ui.theme.DarkGold // Stop button = Dark Gold
                    ConnectionState.CONNECTING -> com.example.stealthlink.ui.theme.DarkGold
                    else -> GoldPrimary // Start button = Bright Gold
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
        TextButton(onClick = { 
            android.widget.Toast.makeText(context, "Пробный период активирован!", android.widget.Toast.LENGTH_SHORT).show()
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
    val scope = rememberCoroutineScope()

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
