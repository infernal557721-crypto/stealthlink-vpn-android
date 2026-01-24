package com.stealthlink.vpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stealthlink.vpn.ui.theme.StealthLinkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StealthLinkTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VpnScreen()
                }
            }
        }
    }
}

@Composable
fun VpnScreen() {
    var isConnected by remember { mutableStateOf(false) }
    var configKey by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("DISCONNECTED") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // HEADER
        Text(
            text = "StealthLink VPN",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 40.dp)
        )

        // CENTER BUTTON
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(200.dp)
        ) {
            Button(
                onClick = { 
                    isConnected = !isConnected 
                    statusText = if (isConnected) "CONNECTED" else "DISCONNECTED"
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) Color(0xFF4CAF50) else Color(0xFFE53935)
                ),
                modifier = Modifier.size(160.dp)
            ) {
                Text(
                    text = if (isConnected) "STOP" else "START",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // STATUS
        Text(
            text = statusText,
            fontSize = 18.sp,
            color = if (isConnected) Color.Green else Color.Gray,
            fontWeight = FontWeight.Medium
        )

        // CONFIG INPUT
        OutlinedTextField(
            value = configKey,
            onValueChange = { configKey = it },
            label = { Text("Paste VLESS/VMESS Key") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3
        )
        
        // SETTINGS / SPLIT TUNNEL
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { /* TODO: Open App List */ }) {
                Text("Split Tunneling")
            }
            Button(onClick = { /* TODO: Open Server List */ }) {
                Text("Servers")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun VpnPreview() {
    StealthLinkTheme {
        VpnScreen()
    }
}
