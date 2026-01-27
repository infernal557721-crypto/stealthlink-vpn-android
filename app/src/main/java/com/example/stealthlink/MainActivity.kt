package com.example.stealthlink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stealthlink.services.WireGuardVpnService
import com.example.stealthlink.ui.theme.*
import kotlinx.coroutines.launch

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED
}

data class TrialInfo(
    val isActive: Boolean,
    val hoursRemaining: Int,
    val hasExpired: Boolean,
    val neverStarted: Boolean
)

class MainActivity : ComponentActivity() {
    
    private var connectionState = mutableStateOf(ConnectionState.DISCONNECTED)
    private var trialInfoState = mutableStateOf(TrialInfo(false, 24, false, true))
    private lateinit var prefs: SharedPreferences
    
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            connectionState.value = ConnectionState.DISCONNECTED
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("stealthlink_prefs", Context.MODE_PRIVATE)
        
        // Initialize trial on first launch
        initializeTrial()
        
        setContent {
            StealthLinkTheme {
                MainScreen(
                    connectionState = connectionState.value,
                    trialInfo = trialInfoState.value,
                    onConnect = { requestVpnPermissionAndConnect() },
                    onDisconnect = { stopVpnService() },
                    onStartTrial = { startTrial() }
                )
            }
        }
    }
    
    private fun initializeTrial() {
        if (!prefs.contains("first_launch_time")) {
            prefs.edit().putLong("first_launch_time", System.currentTimeMillis()).apply()
        }
        // Update trial state
        trialInfoState.value = getTrialInfo()
    }
    
    private fun startTrial() {
        if (!prefs.getBoolean("trial_started", false)) {
            prefs.edit()
                .putBoolean("trial_started", true)
                .putLong("trial_start_time", System.currentTimeMillis())
                .apply()
            // Update trial state immediately
            trialInfoState.value = getTrialInfo()
            Toast.makeText(this, "Пробный период активирован на 24 часа!", Toast.LENGTH_SHORT).show()
        }
    }

    
    private fun getTrialInfo(): TrialInfo {
        val trialStarted = prefs.getBoolean("trial_started", false)
        if (!trialStarted) {
            return TrialInfo(isActive = false, hoursRemaining = 24, hasExpired = false, neverStarted = true)
        }
        
        val trialStartTime = prefs.getLong("trial_start_time", 0)
        val trialDuration = 24 * 60 * 60 * 1000L // 24 hours in milliseconds
        val elapsed = System.currentTimeMillis() - trialStartTime
        val remaining = trialDuration - elapsed
        
        return if (remaining > 0) {
            val hoursRemaining = (remaining / (60 * 60 * 1000)).toInt()
            TrialInfo(isActive = true, hoursRemaining = hoursRemaining, hasExpired = false, neverStarted = false)
        } else {
            TrialInfo(isActive = false, hoursRemaining = 0, hasExpired = true, neverStarted = false)
        }
    }
    
    private fun requestVpnPermissionAndConnect() {
        val trialInfo = getTrialInfo()
        
        // Check if user can connect (trial active or has subscription)
        if (trialInfo.hasExpired) {
            Toast.makeText(this, "Пробный период закончился. Оформите подписку.", Toast.LENGTH_LONG).show()
            return
        }
        if (trialInfo.neverStarted) {
            Toast.makeText(this, "Активируйте пробный период", Toast.LENGTH_SHORT).show()
            return
        }
        
        connectionState.value = ConnectionState.CONNECTING
        
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startVpnService()
        }
    }
    
    private fun startVpnService() {
        try {
            val intent = Intent(this, WireGuardVpnService::class.java)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            connectionState.value = ConnectionState.CONNECTED
        } catch (e: Exception) {
            e.printStackTrace()
            connectionState.value = ConnectionState.DISCONNECTED
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun stopVpnService() {
        val intent = Intent(this, WireGuardVpnService::class.java)
        intent.action = "STOP"
        startService(intent)
        connectionState.value = ConnectionState.DISCONNECTED
    }
}

@Composable
fun MainScreen(
    connectionState: ConnectionState,
    trialInfo: TrialInfo,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onStartTrial: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        containerColor = DarkBackground,
        bottomBar = {
            NavigationBar(containerColor = DarkSurface) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Главная") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = GoldPrimary)
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.ShoppingCart, contentDescription = "Подписка") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
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
                0 -> HomeScreen(connectionState, trialInfo, onConnect, onDisconnect, onStartTrial)
                1 -> SubscriptionScreen()
            }
        }
    }
}

@Composable
fun HomeScreen(
    connectionState: ConnectionState,
    trialInfo: TrialInfo,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onStartTrial: () -> Unit
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
        
        // Trial status
        Spacer(modifier = Modifier.height(8.dp))
        when {
            trialInfo.neverStarted -> {
                Text("Пробный период: не активирован", color = TextGray, fontSize = 12.sp)
            }
            trialInfo.isActive -> {
                Text("Пробный период: ${trialInfo.hoursRemaining} ч. осталось", color = GreenSuccess, fontSize = 12.sp)
            }
            trialInfo.hasExpired -> {
                Text("Пробный период истёк", color = Color.Red, fontSize = 12.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(30.dp))
        
        // Power Button
        Button(
            onClick = { 
                if (connectionState == ConnectionState.DISCONNECTED) {
                    onConnect()
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
            enabled = connectionState != ConnectionState.CONNECTING && 
                      (trialInfo.isActive || !trialInfo.hasExpired && !trialInfo.neverStarted)
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

        // Trial Button (only show if never started)
        if (trialInfo.neverStarted) {
            Button(
                onClick = onStartTrial,
                colors = ButtonDefaults.buttonColors(containerColor = GreenSuccess)
            ) {
                Text("Активировать пробный период (24 ч)", color = Color.White)
            }
        } else if (trialInfo.hasExpired) {
            Text(
                "Оформите подписку для продолжения",
                color = GoldPrimary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun SubscriptionScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<com.example.stealthlink.update.UpdateManager.UpdateInfo?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally, 
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("ПРЕМИУМ", fontSize = 24.sp, color = GoldPrimary, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        
        TariffCard("1 месяц", "100 ₽", false)
        Spacer(modifier = Modifier.height(8.dp))
        TariffCard("3 месяца", "250 ₽", true)
        Spacer(modifier = Modifier.height(8.dp))
        TariffCard("1 год", "800 ₽", false)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = {
                Toast.makeText(context, "Переход к оплате...", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
        ) {
            Text("Оформить подписку", color = DarkBackground, fontWeight = FontWeight.Bold)
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        Text("Оплата через ЮKassa", color = TextGray, fontSize = 12.sp)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Check for updates button
        OutlinedButton(
            onClick = {
                isCheckingUpdate = true
                scope.launch {
                    val updateManager = com.example.stealthlink.update.UpdateManager(context)
                    val info = updateManager.checkForUpdate()
                    updateInfo = info
                    isCheckingUpdate = false
                    if (info.hasUpdate) {
                        showUpdateDialog = true
                    } else {
                        Toast.makeText(context, "У вас последняя версия", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isCheckingUpdate && !isDownloading
        ) {
            if (isCheckingUpdate) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = GoldPrimary, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                if (isDownloading) "Скачивание..." else "Проверить обновления",
                color = GoldPrimary
            )
        }
        
        Text("Версия: 1.0.0", color = TextGray, fontSize = 12.sp)
    }
    
    // Update available dialog
    if (showUpdateDialog && updateInfo != null) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("Доступно обновление", color = TextWhite) },
            text = { 
                Text(
                    "Версия ${updateInfo!!.versionName}\n\nСкачать и установить?",
                    color = TextGray
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUpdateDialog = false
                        isDownloading = true
                        val updateManager = com.example.stealthlink.update.UpdateManager(context)
                        updateManager.downloadAndInstall(
                            updateInfo!!.downloadUrl,
                            onProgress = { },
                            onComplete = { isDownloading = false }
                        )
                        Toast.makeText(context, "Скачивание началось...", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                ) {
                    Text("Обновить", color = DarkBackground)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text("Позже", color = TextGray)
                }
            },
            containerColor = DarkSurface
        )
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
                    Text("ЛУЧШЕЕ ПРЕДЛОЖЕНИЕ", color = GoldPrimary, fontSize = 12.sp)
                }
            }
            Text(price, color = TextWhite, fontSize = 18.sp)
        }
    }
}
