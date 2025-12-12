package com.example.multitimer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.content.Context
import android.content.Context.VIBRATOR_SERVICE
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.os.VibrationEffect
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlin.Int
import android.app.PendingIntent
import android.content.Intent
import androidx.compose.runtime.DisposableEffect
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.res.stringResource
import kotlin.math.abs

private const val TIMER_ONGOING_CHANNEL_ID = "com.example.multitimer.ONGOING"
private const val TIMER_CHANNEL_ID = "com.example.multitimer.TIMER_CHANNEL"

class MainActivity : ComponentActivity() {

    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
        }

        createNotificationChannels()
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                TimerApp(notificationPermissionLauncher)
            }
        }

    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ongoingChannel = NotificationChannel(
                TIMER_ONGOING_CHANNEL_ID,
                "Ongoing Timers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows currently running timers"
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ongoingChannel)

            val finishChannel = NotificationChannel(
                TIMER_CHANNEL_ID,
                "Timer Finished",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when timer finishes"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 1000)
                // 全屏 intent 依赖通道支持？某些设备需要设置
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setShowBadge(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            }
            nm.createNotificationChannel(finishChannel)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("from_notification", false) == true) {
            AppNotificationManager.stopNotification()
            intent.putExtra("from_notification", false)
        }
    }
}

fun Context.getSharedPreferences() =
    this.getSharedPreferences("multitimer_prefs", Context.MODE_PRIVATE)

fun Context.setHasShownNotificationGuide(shown: Boolean) {
    getSharedPreferences().edit().putBoolean("has_shown_notification_guide", shown).apply()
}

fun Context.hasShownNotificationGuide(): Boolean {
    return getSharedPreferences().getBoolean("has_shown_notification_guide", false)
}

// 单例管理音频和震动
@SuppressLint("StaticFieldLeak")
object AppNotificationManager {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var isInitialized = false
    private var context: Context? = null

    fun initialize(context: Context) {
        if (!isInitialized) {
            this.context = context
            initializeMediaPlayer()
            vibrator = context.getSystemService(VIBRATOR_SERVICE) as Vibrator
            isInitialized = true
        }
    }

    private fun initializeMediaPlayer() {
        val ctx = context ?: return

        try {
            // 释放旧的播放器
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                // 首先尝试获取系统默认闹钟铃声
                val alarmRingtoneUri = RingtoneManager.getActualDefaultRingtoneUri(
                    ctx,
                    RingtoneManager.TYPE_ALARM
                )

                if (alarmRingtoneUri != null) {
                    // 如果找到了系统闹钟铃声，就使用它
                    setDataSource(ctx, alarmRingtoneUri)
                } else {
                    // 如果没有设置系统闹钟铃声，则回退到应用内置的音频
                    val afd = ctx.resources.openRawResourceFd(R.raw.alarm)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                }

                // 设置音频属性
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                        .build()
                    setAudioAttributes(audioAttributes)
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(AudioManager.STREAM_ALARM)
                }

                // 准备MediaPlayer
                prepare()

                // 设置循环播放
                isLooping = true
            }

        } catch (e: Exception) {
            e.printStackTrace()
            // 如果出错，尝试使用应用内置音频作为最后的备用方案
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer.create(ctx, R.raw.alarm)?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                            .build()
                        setAudioAttributes(audioAttributes)
                    } else {
                        @Suppress("DEPRECATION")
                        setAudioStreamType(AudioManager.STREAM_ALARM)
                    }
                    isLooping = true
                }
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    fun playNotification() {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.seekTo(0) // 回到开头
                } else {
                    // 确保播放器已准备
                    if (!player.isPlaying) {
                        player.start()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 尝试重新初始化并播放
            context?.let { ctx ->
                initializeMediaPlayer()
                mediaPlayer?.start()
            }
        }

        // 震动
        vibrator?.let { vib ->
            try {
                val vibrationEffect = VibrationEffect.createWaveform(
                    longArrayOf(0, 1000, 1000),
                    1
                )
                vib.vibrate(vibrationEffect)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stopNotification() {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                    player.seekTo(0)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun release() {
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaPlayer = null
        vibrator = null
        isInitialized = false
        context = null
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerApp(
    notificationPermissionLauncher: ActivityResultLauncher<String>? = null
) {
    val timerViewModel: TimerViewModel = viewModel()
    val context = LocalContext.current

    // 初始化通知管理器
    LaunchedEffect(Unit) {
        AppNotificationManager.initialize(context)
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher?.launch(permission)
            }
        }
    }

    // 状态：是否显示引导弹窗
    var showGuideDialog by remember { mutableStateOf(false) }

    // 首次启动自动弹出（仅一次）
    LaunchedEffect(Unit) {
        if (!context.hasShownNotificationGuide()) {
            showGuideDialog = true
            context.setHasShownNotificationGuide(true)
        }
    }

    // 确保在组件销毁时释放资源
    LaunchedEffect(Unit) {
        return@LaunchedEffect
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(
                        onClick = { showGuideDialog = true },
                        enabled = true // 始终可点击
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Notification Settings Guide",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Spacer(modifier = Modifier.size(8.dp))

                    IconButton(
                        onClick = {
                            AppNotificationManager.stopNotification()
                            timerViewModel.clearAllTimers()
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear All Timers")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { timerViewModel.showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Timer")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (timerViewModel.timers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_timers),
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(timerViewModel.timers) { timer ->
                        TimerItem(
                            timer = timer,
                            onTimerUpdate = { updatedTimer ->
                                timerViewModel.updateTimer(updatedTimer)
                            },
                            onTimerDelete = { timerToDelete ->
                                timerViewModel.deleteTimer(timerToDelete)
                            },
                            context = context
                        )
                    }
                }
            }
        }

        if (timerViewModel.showAddDialog) {
            AddTimerDialog(
                onDismiss = { timerViewModel.showAddDialog = false },
                onAdd = { title, hours, minutes, seconds ->
                    timerViewModel.addTimer(title, hours, minutes, seconds)
                    timerViewModel.showAddDialog = false
                }
            )
        }

        // 引导弹窗
        if (showGuideDialog) {
            NotificationGuideDialog(
                onDismiss = { showGuideDialog = false },
                context = context
            )
        }

        if (timerViewModel.showAddDialog) {
            AddTimerDialog(
                onDismiss = { timerViewModel.showAddDialog = false },
                onAdd = { title, hours, minutes, seconds ->
                    timerViewModel.addTimer(title, hours, minutes, seconds)
                    timerViewModel.showAddDialog = false
                }
            )
        }
    }
}



@Composable
fun NotificationGuideDialog(
    onDismiss: () -> Unit,
    context: Context
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notification_guide_title)) },
        text = {
            Text(stringResource(R.string.notification_guide_message),
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    // 跳转到 App 通知设置
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    context.startActivity(intent)
                }
            ) {
                Text(stringResource(R.string.go_to_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.later))
            }
        }
    )
}

@Composable
fun TimerItem(
    timer: Timer,
    onTimerUpdate: (Timer) -> Unit,
    onTimerDelete: (Timer) -> Unit,
    context: Context
) {
    val finishTitle = stringResource(R.string.finish)
    val timerText = stringResource(R.string.timer)
    val hasFinishedText = stringResource(R.string.has_finished)
    val remainingText = stringResource(R.string.remaining)

    val coroutineScope = rememberCoroutineScope()
    var currentTime by remember { mutableStateOf(timer.currTime) }
    var isActive by remember { mutableStateOf(timer.active) }
    var hasTriggered by remember { mutableStateOf(false) }
    var isCompleted by remember { mutableStateOf(false) }

    // 启动协程定期更新计时器
    LaunchedEffect(timer) {
        while (true) {
            delay(100) // 每100毫秒更新一次
            if (timer.active) {
                val stillActive = timer.run()
                currentTime = timer.currTime
                isActive = timer.active

                updateOngoingNotification(context, timer, timerText, remainingText)

                // 检查计时器是否结束
                if (currentTime <= 0 && !hasTriggered) {
                    cancelOngoingNotification(context, timer.id)
                    sendNotification(context, timer, finishTitle, timerText, hasFinishedText)
                    AppNotificationManager.playNotification()
                    hasTriggered = true
                    isCompleted = true
                }
            } else {
                // 如果计时器不活动，只更新当前时间
                currentTime = timer.currTime
                isActive = timer.active
                if (currentTime <= 0) {
                    isCompleted = true
                } else if (hasTriggered) {
                    isCompleted = false
                }
            }
        }
    }

    // 删除时也取消
    DisposableEffect(timer) {
        onDispose {
            cancelOngoingNotification(context, timer.id)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isCompleted -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                isActive -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timer.title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        isCompleted -> MaterialTheme.colorScheme.onSurfaceVariant
                        isActive -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )

                IconButton(
                    onClick = {
                        if (isCompleted) {
                            AppNotificationManager.stopNotification()
                        }
                        onTimerDelete(timer)
                    }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete_timer),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = formatTime(currentTime),
                fontSize = 48.sp,
                fontWeight = FontWeight.Light,
                color = when {
                    isCompleted -> MaterialTheme.colorScheme.onSurfaceVariant
                    isActive -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FloatingActionButton(
                    onClick = {
                        if (isActive) {
                            timer.pause()
                            isActive = false
                        } else {
                            timer.start()
                            isActive = true
                            hasTriggered = false
                            isCompleted = false
                        }
                    },
                    containerColor = if (isActive)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        if (isActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isActive) "Pause" else "Start"
                    )
                }

                FloatingActionButton(
                    onClick = {
                        timer.reset()
                        currentTime = timer.currTime
                        isActive = false
                        hasTriggered = false
                        if (isCompleted) {
                            AppNotificationManager.stopNotification()
                        }
                        isCompleted = false
                    },
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset")
                }
            }
        }
    }
}

fun sendNotification(context: Context,
                     timer: Timer,
                     finishTitle: String,
                     timerText: String,
                     hasFinishedText: String) {
    // === 全屏 Intent ===
    val fullScreenIntent = Intent(context, TimerAlertActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("timer_title", timer.title)
    }

    val fullScreenPendingIntent = PendingIntent.getActivity(
        context,
        timer.id,
        fullScreenIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // === 普通 PendingIntent（用于非锁屏时点击）===
    val normalIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra("from_notification", true)
    }
    val normalPendingIntent = PendingIntent.getActivity(
        context,
        timer.id,
        normalIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // === 构建通知 ===
    val notification = NotificationCompat.Builder(context, TIMER_CHANNEL_ID)
        .setContentTitle(finishTitle)
        .setContentText("$timerText '${timer.title}' $hasFinishedText")
        .setSmallIcon(R.drawable.ic_timer)
        .setContentIntent(normalPendingIntent)       // 点击通知（非全屏时）
        .setFullScreenIntent(fullScreenPendingIntent, true)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setCategory(NotificationCompat.CATEGORY_ALARM) // 提示系统这是闹钟类通知
        .setAutoCancel(true)
        .setDefaults(NotificationCompat.DEFAULT_ALL)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 锁屏显示完整内容
        .build()

    val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
    nm?.notify(timer.id, notification)
}

@SuppressLint("DefaultLocale")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTimerDialog(
    onDismiss: () -> Unit,
    onAdd: (String, Int, Int, Int) -> Unit
) {
    val untitledText = stringResource(R.string.untitled)
    val timeErrorMsg = stringResource(R.string.time_error_msg)

    var title by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("0")}
    var minutes by remember { mutableStateOf("0") }
    var seconds by remember { mutableStateOf("0") }
    var errorMessage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_timer)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.timer_name)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = hours,
                        onValueChange = {
                            if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                                hours = it
                            }
                        },
                        label = { Text(stringResource(R.string.hours)) },
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = minutes,
                        onValueChange = {
                            if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                                minutes = it
                            }
                        },
                        label = { Text(stringResource(R.string.minutes)) },
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = seconds,
                        onValueChange = {
                            if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                                seconds = it
                            }
                        },
                        label = { Text(stringResource(R.string.seconds)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (errorMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val hurs = hours.toIntOrNull() ?: 0
                    val mins = minutes.toIntOrNull() ?: 0
                    val secs = seconds.toIntOrNull() ?: 0

                    if (title.isBlank()) {
                        var calcTime = hurs * 3600 + mins * 60 + secs
                        var hourText = (calcTime / 3600).toInt()
                        var minText = (calcTime % 3600 / 60).toInt()
                        var secText = (calcTime % 60)
                        title = "$untitledText ${if (hourText > 0) String.format("%d:%02d:%02d", hourText, minText, secText) else String.format("%d:%02d", minText, secText)}"
//                        errorMessage = "Please enter a timer name"
//                        return@Button
                    }

                    if (hurs <= 0 && mins <= 0 && secs <= 0) {
                        errorMessage = timeErrorMsg
                        return@Button
                    }

                    onAdd(title, hurs, mins, secs)
                }
            ) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

class TimerViewModel : ViewModel() {
    val timers = mutableStateListOf<Timer>()
    var showAddDialog by mutableStateOf(false)

    fun addTimer(title: String, hours: Int, minutes: Int, seconds: Int) {
        val totalSeconds = hours * 3600L + minutes * 60L + seconds
        timers.add(Timer(title, totalSeconds))
    }

    fun updateTimer(timer: Timer) {
        val index = timers.indexOfFirst { it.title == timer.title }
        if (index != -1) {
            timers[index] = timer
        }
    }

    fun deleteTimer(timer: Timer) {
        timers.remove(timer)
    }

    fun clearAllTimers() {
        timers.clear()
    }

    override fun onCleared() {
        super.onCleared()
        // ViewModel销毁时释放资源
        AppNotificationManager.release()
    }
}

fun updateOngoingNotification(context: Context, timer: Timer, timerText: String, remainingText: String) {
    val notification = NotificationCompat.Builder(context, TIMER_ONGOING_CHANNEL_ID)
        .setContentTitle("${timerText}: ${timer.title}")
        .setContentText("${remainingText}: ${formatTime(timer.currTime)}")
        .setSmallIcon(R.drawable.ic_timer)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setShowWhen(false)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()

    val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
    nm?.notify(-timer.id, notification)
}

fun cancelOngoingNotification(context: Context, timerId: Int) {
    val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
    nm?.cancel(-timerId)
}

@SuppressLint("DefaultLocale")
fun formatTime(seconds: Long): String {
    val absSeconds = abs(seconds)
    val hours = TimeUnit.SECONDS.toHours(absSeconds)
    val minutes = TimeUnit.SECONDS.toMinutes(absSeconds % 3600)
    val secs = absSeconds % 60
    return "${if (seconds < 0) "-" else ""}${if (hours <= 0) String.format("%02d:%02d", minutes, secs) else String.format("%02d:%02d:%02d", hours, minutes, secs)}"
}
