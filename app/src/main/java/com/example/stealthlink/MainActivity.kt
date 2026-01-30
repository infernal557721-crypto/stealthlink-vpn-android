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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale // Keep this import
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.* // Import animations
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stealthlink.services.WireGuardVpnService
import com.example.stealthlink.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            Toast.makeText(this, "Отказано в доступе к VPN", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("stealthlink_prefs", Context.MODE_PRIVATE)
        
        // Initialize trial on first launch
        initializeTrial()
        
        // Start proactive trial monitor
        startTrialTimer()
        
        // Check for updates on launch
        checkForUpdatesOnLaunch()
        
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
    
    private fun checkForUpdatesOnLaunch() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // ... update check code ...
            } catch (e: Exception) {
            }
        }
    }

    // Timer to update trial status and auto-disconnect
    private fun startTrialTimer() {
        GlobalScope.launch(Dispatchers.Main) {
            while (true) {
                kotlinx.coroutines.delay(10000) // Check every 10 seconds
                trialInfoState.value = getTrialInfo()
                
                // Auto-disconnect if expired
                if (trialInfoState.value.hasExpired && connectionState.value == ConnectionState.CONNECTED) {
                    stopVpnService()
                    Toast.makeText(this@MainActivity, "Пробный период завершен", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun showUpdateNotification(newVersion: String) {
        val channelId = "update_channel"
        
        // Create notification channel for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Обновления",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о новых версиях приложения"
            }
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
        
        // Create intent to open app
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_updates", true)
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Доступно обновление!")
            .setContentText("Версия $newVersion готова к установке")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(1001, notification)
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
            Toast.makeText(this, "Тестовый период (7 дней) активирован!", Toast.LENGTH_SHORT).show()
        }
    }

    
    private fun getTrialInfo(): TrialInfo {
        val trialStarted = prefs.getBoolean("trial_started", false)
        if (!trialStarted) {
            return TrialInfo(isActive = false, hoursRemaining = 24, hasExpired = false, neverStarted = true)
        }
        
        val trialStartTime = prefs.getLong("trial_start_time", 0)
        val trialDuration = 7L * 24 * 60 * 60 * 1000L // 7 days
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
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            try {
                // Get Device ID (using ANDROID_ID for simplicity in this demo)
                // In production, consider a more robust ID or Advertising ID
                val deviceId = android.provider.Settings.Secure.getString(
                    contentResolver, 
                    android.provider.Settings.Secure.ANDROID_ID
                )

                // Show loading 
                connectionState.value = ConnectionState.CONNECTING

                val config = withContext(Dispatchers.IO) {
                    val request = com.example.stealthlink.data.api.ConfigRequest(deviceId)
                    com.example.stealthlink.data.api.RetrofitClient.api.getVpnConfig(request)
                }

                val intent = Intent(this@MainActivity, WireGuardVpnService::class.java)
                
                intent.putExtra(WireGuardVpnService.EXTRA_PUBLIC_KEY, config.public_key)
                intent.putExtra(WireGuardVpnService.EXTRA_ENDPOINT, config.endpoint)
                intent.putExtra(WireGuardVpnService.EXTRA_PRIVATE_KEY, config.private_key)
                intent.putExtra(WireGuardVpnService.EXTRA_ADDRESS, config.address)
                intent.putExtra(WireGuardVpnService.EXTRA_DNS, config.dns)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                
                connectionState.value = ConnectionState.CONNECTED
                
                // Update local trial info to match successful connection
                // This is just for UI, the real check is on backend
                trialInfoState.value = trialInfoState.value.copy(isActive = true, hasExpired = false)

            } catch (e: Exception) {
                e.printStackTrace()
                connectionState.value = ConnectionState.DISCONNECTED
                
                if (e is retrofit2.HttpException && e.code() == 403) {
                     Toast.makeText(this@MainActivity, "Пробный период истек. Пожалуйста, оформите подписку.", Toast.LENGTH_LONG).show()
                     trialInfoState.value = trialInfoState.value.copy(isActive = false, hasExpired = true)
                } else {
                     Toast.makeText(this@MainActivity, "Ошибка подключения: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
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
        containerColor = Color.Transparent, // Transparent to show gradient
        bottomBar = {
            NavigationBar(containerColor = DarkSurface.copy(alpha = 0.95f)) {
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(BackgroundGradientStart, BackgroundGradientEnd)
                    )
                )
                .padding(innerPadding)
                .padding(16.dp)
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
    // Animation for pulsing effect
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition()
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (connectionState == ConnectionState.DISCONNECTED) 1.05f else 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1500),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        )
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        // Premium Title
        Text(
            "VpnCode", 
            fontSize = 32.sp, 
            fontWeight = FontWeight.Black, 
            color = GoldPrimary,
            letterSpacing = 2.sp
        )
        Text(
            "БЕЗОПАСНЫЙ VPN", 
            fontSize = 12.sp, 
            color = TextGray, 
            letterSpacing = 4.sp
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        // Connection Button with Ring
        Box(contentAlignment = Alignment.Center) {
            // Outer Ring (Pulsing)
            if (connectionState == ConnectionState.CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(240.dp),
                    color = GoldPrimary,
                    strokeWidth = 2.dp
                )
            }
            
            // Main Button
            Button(
                onClick = { 
                    if (connectionState == ConnectionState.DISCONNECTED) onConnect() 
                    else if (connectionState == ConnectionState.CONNECTED) onDisconnect()
                },
                modifier = Modifier
                    .size(200.dp)
                    .size(200.dp)
                    .scale(scale), // Use imported extension directly
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (connectionState) {
                        ConnectionState.CONNECTED -> GreenSuccess
                        ConnectionState.CONNECTING -> DarkGold
                        else -> GoldPrimary
                    }
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 12.dp,
                    pressedElevation = 6.dp
                ),
                enabled = connectionState != ConnectionState.CONNECTING && 
                          (trialInfo.isActive || !trialInfo.hasExpired && !trialInfo.neverStarted)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (connectionState == ConnectionState.CONNECTED) 
                            androidx.compose.material.icons.Icons.Default.Stop 
                        else 
                            androidx.compose.material.icons.Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = DarkBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        when (connectionState) {
                            ConnectionState.CONNECTED -> "СТОП"
                            ConnectionState.CONNECTING -> "..."
                            else -> "СТАРТ"
                        },
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkBackground
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Status Text
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        when (connectionState) {
                            ConnectionState.CONNECTED -> GreenSuccess
                            ConnectionState.CONNECTING -> GoldPrimary
                            else -> TextGray
                        }, 
                        CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                when (connectionState) {
                    ConnectionState.DISCONNECTED -> "ОТКЛЮЧЕНО"
                    ConnectionState.CONNECTING -> "ПОДКЛЮЧЕНИЕ..."
                    ConnectionState.CONNECTED -> "ПОДКЛЮЧЕНО"
                },
                color = TextWhite,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Trial / Status Card
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    trialInfo.neverStarted -> {
                         Text("Пробный период", color = TextWhite, fontWeight = FontWeight.Bold)
                         Spacer(modifier = Modifier.height(8.dp))
                         Button(
                            onClick = onStartTrial,
                            colors = ButtonDefaults.buttonColors(containerColor = PremiumGoldStart)
                        ) {
                            Text("Активировать (7 дней)", color = DarkBackground)
                        }
                    }
                    trialInfo.isActive -> {
                        Text("Пробный период активен", color = GreenSuccess, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = trialInfo.hoursRemaining / 24f,
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = GoldPrimary,
                            trackColor = DarkBackground
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("${trialInfo.hoursRemaining} часов осталось", color = TextGray, fontSize = 12.sp)
                    }
                    trialInfo.hasExpired -> {
                        Text("Период истек", color = RedStop, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Оформите подписку", color = TextGray, fontSize = 12.sp)
                    }
                }
            }
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
    
    // Tariffs
    val tariffs = remember {
        listOf(
            Tariff("1 месяц", "100.00", "100 ₽", false),
            Tariff("3 месяца", "250.00", "250 ₽", true),
            Tariff("1 год", "800.00", "800 ₽", false)
        )
    }
    var selectedTariff by remember { mutableStateOf(tariffs[1]) } // Default to 3 months
    var isPaymentProcessing by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally, 
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("ПРЕМИУМ", fontSize = 24.sp, color = GoldPrimary, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        
        tariffs.forEach { tariff ->
            TariffCard(
                tariff = tariff, 
                isSelected = selectedTariff == tariff,
                onClick = { selectedTariff = tariff }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = {
                scope.launch {
                    isPaymentProcessing = true
                    try {
                        val request = com.example.stealthlink.data.model.PaymentRequest(
                            amount = selectedTariff.value,
                            description = "Подписка VpnCode: ${selectedTariff.name}"
                        )
                        val response = com.example.stealthlink.data.api.RetrofitClient.api.createPayment(request)
                        
                        // Open confirmation URL
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(response.confirmation_url))
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Ошибка создания платежа: ${e.message}", Toast.LENGTH_LONG).show()
                        e.printStackTrace()
                    } finally {
                        isPaymentProcessing = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
            enabled = !isPaymentProcessing
        ) {
            if (isPaymentProcessing) {
                CircularProgressIndicator(color = DarkBackground, modifier = Modifier.size(24.dp))
            } else {
                Text("Оформить подписку", color = DarkBackground, fontWeight = FontWeight.Bold)
            }
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
        
        Text("Версия: ${BuildConfig.VERSION_NAME}", color = TextGray, fontSize = 12.sp)
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

data class Tariff(
    val name: String,
    val value: String,
    val displayPrice: String,
    val isHit: Boolean
)

@Composable
fun TariffCard(tariff: Tariff, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isSelected) DarkSurface.copy(alpha=0.7f) else DarkSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, GoldPrimary) else if (tariff.isHit) androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha=0.5f)) else null
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(tariff.name, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (tariff.isHit) {
                    Text("ЛУЧШЕЕ ПРЕДЛОЖЕНИЕ", color = GoldPrimary, fontSize = 12.sp)
                }
            }
            if (isSelected) {
                Icon(Icons.Default.Check, contentDescription = "Selected", tint = GoldPrimary, modifier = Modifier.padding(end = 8.dp))
            }
            Text(tariff.displayPrice, color = TextWhite, fontSize = 18.sp)
        }
    }
}
