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
                    icon = { Icon(Icons.Default.ShoppingCart, contentDescription = "Pay") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = GoldPrimary)
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = GoldPrimary)
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
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
                2 -> Text("Settings", color = Color.White)
            }
        }
    }
}

@Composable
fun HomeScreen() {
    var isConnected by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("StealthLink VPN", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextWhite)
        Spacer(modifier = Modifier.height(8.dp))
        Text(if (isConnected) "CONNECTED" else "DISCONNECTED", color = TextGray)
        
        Spacer(modifier = Modifier.height(60.dp))
        
        // Power Button
        Button(
            onClick = { isConnected = !isConnected },
            modifier = Modifier.size(200.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = if (isConnected) GoldPrimary else RedStop)
        ) {
            Text(
                if (isConnected) "STOP" else "START",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = if (isConnected) DarkBackground else TextWhite
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        // Split Tunneling Toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurface)
                .padding(16.dp)
        ) {
            Text("Only for Social Networks", color = TextWhite, modifier = Modifier.weight(1f))
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
        Text("PREMIUM", fontSize = 24.sp, color = GoldPrimary, fontWeight = FontWeight.Bold)
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
            Text("Continue", color = DarkBackground, fontWeight = FontWeight.Bold)
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
                    Text("HIT SALES", color = GoldPrimary, fontSize = 12.sp)
                }
            }
            Text(price, color = TextWhite, fontSize = 18.sp)
        }
    }
}
