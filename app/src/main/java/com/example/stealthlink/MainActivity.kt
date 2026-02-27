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
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stealthlink.services.WireGuardVpnService
import com.example.stealthlink.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

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
    private var trialInfoState = mutableStateOf(TrialInfo(false, 24, false, false))
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
        
        initializeTrial()
        startTrialTimer()
        checkForUpdatesOnLaunch()
        fetchUserStatus()
        
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
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // silent update check
            } catch (e: Exception) {
            }
        }
    }

    private fun startTrialTimer() {
        lifecycleScope.launch(Dispatchers.Main) {
            while (true) {
                kotlinx.coroutines.delay(10000)
                trialInfoState.value = getTrialInfo()
                if (trialInfoState.value.hasExpired && connectionState.value == ConnectionState.CONNECTED) {
                    stopVpnService()
                    Toast.makeText(this@MainActivity, "Пробный период завершен", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun showUpdateNotification(newVersion: String) {
        val channelId = "update_channel"
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
    
    private fun fetchUserStatus() {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                val deviceId = android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                )
                val request = com.example.stealthlink.data.api.ConfigRequest(deviceId)
                val config = com.example.stealthlink.data.api.RetrofitClient.api.getVpnConfig(request)
                withContext(Dispatchers.Main) {
                    val hours = (config.subscription.remaining_seconds / 3600).toInt()
                    trialInfoState.value = TrialInfo(
                        isActive = config.subscription.is_active,
                        hoursRemaining = hours,
                        hasExpired = !config.subscription.is_active,
                        neverStarted = false
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (e is retrofit2.HttpException && e.code() == 403) {
                    withContext(Dispatchers.Main) {
                        trialInfoState.value = TrialInfo(isActive = false, hoursRemaining = 0, hasExpired = true, neverStarted = false)
                    }
                }
            }
        }
    }
    
    private fun initializeTrial() {
        // Rely on fetchUserStatus
    }
    
    private fun startTrial() {
        Toast.makeText(this, "Загрузка...", Toast.LENGTH_SHORT).show()
        fetchUserStatus()
    }

    private fun getTrialInfo(): TrialInfo {
        return trialInfoState.value
    }
    
    private fun requestVpnPermissionAndConnect() {
        val trialInfo = getTrialInfo()
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
                val deviceId = android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                )
                connectionState.value = ConnectionState.CONNECTING
                val config = withContext(Dispatchers.IO) {
                    val request = com.example.stealthlink.data.api.ConfigRequest(deviceId)
                    com.example.stealthlink.data.api.RetrofitClient.api.getVpnConfig(request)
                }
                val intent = Intent(this@MainActivity, WireGuardVpnService::class.java)
                intent.putExtra(WireGuardVpnService.EXTRA_PUBLIC_KEY, config.wireguard.server_public_key)
                intent.putExtra(WireGuardVpnService.EXTRA_ENDPOINT, config.wireguard.endpoint)
                intent.putExtra(WireGuardVpnService.EXTRA_PRIVATE_KEY, config.wireguard.private_key)
                intent.putExtra(WireGuardVpnService.EXTRA_ADDRESS, config.wireguard.address)
                intent.putExtra(WireGuardVpnService.EXTRA_DNS, config.wireguard.dns)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                connectionState.value = ConnectionState.CONNECTED
                val hours = (config.subscription.remaining_seconds / 3600).toInt()
                trialInfoState.value = TrialInfo(
                    isActive = config.subscription.is_active,
                    hoursRemaining = hours,
                    hasExpired = !config.subscription.is_active,
                    neverStarted = false
                )
            } catch (e: Exception) {
                e.printStackTrace()
                android.util.Log.e("VPN", "Connection error", e)
                connectionState.value = ConnectionState.DISCONNECTED
                val errorMsg = when {
                    e is retrofit2.HttpException && e.code() == 403 -> {
                        trialInfoState.value = trialInfoState.value.copy(isActive = false, hasExpired = true)
                        "Пробный период истек. Пожалуйста, оформите подписку."
                    }
                    e is java.net.UnknownHostException -> "Нет подключения к интернету"
                    e is java.net.SocketTimeoutException -> "Сервер не отвечает"
                    else -> "Ошибка: ${e.javaClass.simpleName} - ${e.message}"
                }
                Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
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
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0A0A0A),
                contentColor = TextWhite
            ) {
                NavigationBarItem(
                    icon = {
                        Icon(
                            Icons.Default.Home,
                            contentDescription = "Главная",
                            tint = if (selectedTab == 0) GoldPrimary else TextGray
                        )
                    },
                    label = {
                        Text(
                            "Главная",
                            color = if (selectedTab == 0) GoldPrimary else TextGray,
                            fontSize = 11.sp
                        )
                    },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = GoldPrimary.copy(alpha = 0.2f),
                        selectedIconColor = GoldPrimary,
                        unselectedIconColor = TextGray
                    )
                )
                NavigationBarItem(
                    icon = {
                        Icon(
                            Icons.Default.ShoppingCart,
                            contentDescription = "Подписка",
                            tint = if (selectedTab == 1) GoldPrimary else TextGray
                        )
                    },
                    label = {
                        Text(
                            "Подписка",
                            color = if (selectedTab == 1) GoldPrimary else TextGray,
                            fontSize = 11.sp
                        )
                    },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = GoldPrimary.copy(alpha = 0.2f),
                        selectedIconColor = GoldPrimary,
                        unselectedIconColor = TextGray
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF050505))
                .padding(innerPadding)
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
    val infiniteTransition = rememberInfiniteTransition()
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A1A1A),
                        Color(0xFF0A0A0A),
                        Color(0xFF050505)
                    )
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val lineColor = Color(0x10D4A84B)
            for (i in 0..20) {
                drawLine(
                    color = lineColor,
                    start = androidx.compose.ui.geometry.Offset(size.width * i / 10f, 0f),
                    end = androidx.compose.ui.geometry.Offset(0f, size.height * i / 10f),
                    strokeWidth = 1f
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Vpn",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Light,
                    color = TextWhite,
                    letterSpacing = 1.sp
                )
                Text(
                    "Code",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = GoldPrimary,
                    letterSpacing = 1.sp
                )
            }
            Text(
                "БЕЗОПАСНЫЙ VPN",
                fontSize = 11.sp,
                color = TextGray,
                letterSpacing = 4.sp
            )

            Spacer(modifier = Modifier.height(48.dp))

            Box(contentAlignment = Alignment.Center) {
                if (connectionState == ConnectionState.CONNECTED) {
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                    colors = listOf(
                                        GreenSuccess.copy(alpha = glowAlpha * 0.3f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )
                } else if (connectionState == ConnectionState.DISCONNECTED) {
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                    colors = listOf(
                                        GoldPrimary.copy(alpha = glowAlpha * 0.2f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )
                }

                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .border(
                            width = 4.dp,
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = if (connectionState == ConnectionState.CONNECTED)
                                    listOf(GreenSuccess, Color(0xFF388E3C))
                                else
                                    listOf(GoldPrimary, GoldLight, GoldPrimary)
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = {
                            if (connectionState == ConnectionState.DISCONNECTED) onConnect()
                            else if (connectionState == ConnectionState.CONNECTED) onDisconnect()
                        },
                        modifier = Modifier.size(170.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when (connectionState) {
                                ConnectionState.CONNECTED -> GreenSuccess
                                ConnectionState.CONNECTING -> GoldPrimary.copy(alpha = 0.7f)
                                else -> GoldPrimary
                            }
                        ),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 8.dp,
                            pressedElevation = 4.dp
                        ),
                        enabled = connectionState != ConnectionState.CONNECTING &&
                                  (trialInfo.isActive || !trialInfo.hasExpired && !trialInfo.neverStarted)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = if (connectionState == ConnectionState.CONNECTED)
                                    Icons.Default.Stop
                                else
                                    Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = DarkBackground
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                when (connectionState) {
                                    ConnectionState.CONNECTED -> "СТОП"
                                    ConnectionState.CONNECTING -> "..."
                                    else -> "СТАРТ"
                                },
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = DarkBackground
                            )
                        }
                    }
                }

                if (connectionState == ConnectionState.CONNECTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(210.dp),
                        color = GoldPrimary,
                        strokeWidth = 2.dp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            when (connectionState) {
                                ConnectionState.CONNECTED -> GreenSuccess
                                ConnectionState.CONNECTING -> GoldPrimary
                                else -> TextGray
                            },
                            CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    when (connectionState) {
                        ConnectionState.DISCONNECTED -> "ОТКЛЮЧЕНО"
                        ConnectionState.CONNECTING -> "ПОДКЛЮЧЕНИЕ..."
                        ConnectionState.CONNECTED -> "ПОДКЛЮЧЕНО"
                    },
                    color = TextWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp
                )
            }

            Text(
                when (connectionState) {
                    ConnectionState.CONNECTED -> "Ваше соединение защищено"
                    else -> "Ваше соединение не защищено"
                },
                color = TextGray,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (trialInfo.neverStarted) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.8f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("\uD83C\uDF81", fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Пробный период", color = TextWhite, fontWeight = FontWeight.Medium)
                                Text("24 часа бесплатно", color = TextGray, fontSize = 12.sp)
                            }
                            Button(
                                onClick = onStartTrial,
                                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Старт", color = DarkBackground, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else if (trialInfo.isActive) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.8f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("\u23F0", fontSize = 20.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Осталось: ${trialInfo.hoursRemaining}ч", color = GreenSuccess, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = trialInfo.hoursRemaining.coerceIn(0, 24) / 24f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = GoldPrimary,
                                trackColor = DarkBackground
                            )
                        }
                    }
                } else if (trialInfo.hasExpired) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = RedStop.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("\u26A0\uFE0F", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Период истёк. Оформите подписку", color = TextWhite, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InfoCard(icon: String, title: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.8f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 20.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(title, color = TextGray, fontSize = 12.sp)
                Text(value, color = TextWhite, fontWeight = FontWeight.Medium, fontSize = 14.sp)
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

    val tariffs = remember {
        listOf(
            Tariff("1 месяц", "100.00", "100 \u20BD", false),
            Tariff("3 месяца", "250.00", "250 \u20BD", true),
            Tariff("1 год", "800.00", "800 \u20BD", false)
        )
    }
    var selectedTariff by remember { mutableStateOf(tariffs[1]) }
    var isPaymentProcessing by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A1A1A),
                        Color(0xFF0A0A0A),
                        Color(0xFF050505)
                    )
                )
            )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                "Оформить подписку",
                fontSize = 24.sp,
                color = TextWhite,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(32.dp))

            tariffs.forEach { tariff ->
                PremiumTariffCard(
                    tariff = tariff,
                    isSelected = selectedTariff == tariff,
                    onClick = { selectedTariff = tariff }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    scope.launch {
                        isPaymentProcessing = true
                        try {
                            val deviceId = android.provider.Settings.Secure.getString(
                                context.contentResolver,
                                android.provider.Settings.Secure.ANDROID_ID
                            )
                            val request = com.example.stealthlink.data.model.PaymentRequest(
                                amount = selectedTariff.value,
                                description = "Подписка VpnCode: ${selectedTariff.name}",
                                user_id = deviceId
                            )
                            val response = com.example.stealthlink.data.api.RetrofitClient.api.createPayment(request)
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(response.confirmation_url))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            isPaymentProcessing = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                enabled = !isPaymentProcessing
            ) {
                if (isPaymentProcessing) {
                    CircularProgressIndicator(color = DarkBackground, modifier = Modifier.size(24.dp))
                } else {
                    Text(
                        "Оплатить",
                        color = DarkBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            TextButton(
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
                enabled = !isCheckingUpdate && !isDownloading
            ) {
                if (isCheckingUpdate) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = GoldPrimary, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    if (isDownloading) "Скачивание..." else "Проверить обновления",
                    color = TextGray,
                    fontSize = 14.sp
                )
            }

            Text(
                "Версия: ${BuildConfig.VERSION_NAME}",
                color = TextGray.copy(alpha = 0.5f),
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showUpdateDialog && updateInfo != null) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("Доступно обновление", color = TextWhite, fontWeight = FontWeight.Bold) },
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
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Обновить", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text("Позже", color = TextGray)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp)
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
fun PremiumTariffCard(tariff: Tariff, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = DarkSurface.copy(alpha = 0.9f)
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            border = when {
                isSelected -> androidx.compose.foundation.BorderStroke(2.dp, GoldPrimary)
                tariff.isHit -> androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.5f))
                else -> androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333))
            }
        ) {
            Row(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    tariff.name,
                    color = if (isSelected) GoldPrimary else TextWhite,
                    fontWeight = FontWeight.Medium,
                    fontSize = 18.sp
                )
                Text(
                    tariff.displayPrice,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }

        if (tariff.isHit) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-8).dp, y = (-8).dp)
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                            colors = listOf(GoldPrimary, Color(0xFFB8860B))
                        ),
                        shape = CircleShape
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    "\u0425\u0418\u0422",
                    color = DarkBackground,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun TariffCard(tariff: Tariff, isSelected: Boolean, onClick: () -> Unit) {
    PremiumTariffCard(tariff, isSelected, onClick)
}
