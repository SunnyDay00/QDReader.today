package today.qdreader.auto

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import today.qdreader.auto.accessibility.AccessibilityBridgeImpl
import today.qdreader.auto.automation.AutomationController
import today.qdreader.auto.core.AutomationTrigger
import today.qdreader.auto.core.DeviceStatus
import today.qdreader.auto.logs.AppLogEntry
import today.qdreader.auto.logs.AppLogStore
import today.qdreader.auto.notifications.AppNotifier
import today.qdreader.auto.schedule.ScheduleConfig
import today.qdreader.auto.schedule.SchedulePlanner
import today.qdreader.auto.schedule.ScheduleRepository
import today.qdreader.auto.ui.theme.QDReaderTheme

data class DashboardState(
    val accessibilityEnabled: Boolean,
    val notificationGranted: Boolean,
    val targetInstalled: Boolean,
    val serviceConnected: Boolean,
    val currentPackageName: String?,
    val scheduleConfig: ScheduleConfig
)

class MainActivity : ComponentActivity() {
    private lateinit var scheduleRepository: ScheduleRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleRepository = ScheduleRepository(this)
        AppNotifier.ensureChannel(this)

        setContent {
            QDReaderTheme {
                val activity = this@MainActivity
                val scope = rememberCoroutineScope()
                val logs by AppLogStore.entries.collectAsStateWithLifecycle()
                var dashboardState by remember {
                    mutableStateOf(loadDashboardState(activity, scheduleRepository))
                }
                var runOutput by remember {
                    mutableStateOf("尚未运行自动任务。")
                }
                val notificationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    AppLogStore.add(if (granted) "通知权限已授予" else "通知权限被拒绝")
                    dashboardState = loadDashboardState(activity, scheduleRepository)
                }

                fun refresh() {
                    dashboardState = loadDashboardState(activity, scheduleRepository)
                }

                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    refresh()
                }

                LaunchedEffect(Unit) {
                    AppLogStore.add("起点自动签到界面已启动")
                }

                DashboardScreen(
                    state = dashboardState,
                    logs = logs,
                    runOutput = runOutput,
                    onRefresh = { refresh() },
                    onOpenAccessibility = {
                        DeviceStatus.openAccessibilitySettings(activity)
                    },
                    onRequestNotification = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            AppLogStore.add("当前系统不需要通知运行时权限")
                        }
                    },
                    onOpenTargetApp = {
                        if (!DeviceStatus.openTargetApp(activity)) {
                            AppLogStore.add("未找到起点读书启动入口")
                        }
                    },
                    onRunAutomation = {
                        scope.launch {
                            runOutput = "自动任务运行中..."
                            val result = AutomationController(activity).run(
                                AutomationTrigger.Manual,
                                maxRestartCount = dashboardState.scheduleConfig.maxRestartCount
                            )
                            runOutput = result.message
                            AppNotifier.showStatus(
                                activity,
                                if (result.success) "起点自动签到已完成" else "起点自动签到未完成",
                                result.message
                            )
                            refresh()
                        }
                    },
                    onSaveSchedule = { config ->
                        scheduleRepository.save(config)
                        SchedulePlanner.reschedule(activity)
                        AppLogStore.add("保存每日自动任务时间：${config.label()}，启用=${config.enabled}")
                        refresh()
                    },
                    onClearLogs = {
                        AppLogStore.clear()
                    }
                )
            }
        }
    }
}

private fun loadDashboardState(
    context: android.content.Context,
    scheduleRepository: ScheduleRepository
): DashboardState {
    val bridge = AccessibilityBridgeImpl(context)
    return DashboardState(
        accessibilityEnabled = DeviceStatus.isAccessibilityEnabled(context),
        notificationGranted = DeviceStatus.hasNotificationPermission(context),
        targetInstalled = DeviceStatus.isTargetAppInstalled(context),
        serviceConnected = bridge.isServiceConnected(),
        currentPackageName = bridge.currentPackageName(),
        scheduleConfig = scheduleRepository.load()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(
    state: DashboardState,
    logs: List<AppLogEntry>,
    runOutput: String,
    onRefresh: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestNotification: () -> Unit,
    onOpenTargetApp: () -> Unit,
    onRunAutomation: () -> Unit,
    onSaveSchedule: (ScheduleConfig) -> Unit,
    onClearLogs: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("起点自动签到", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                HeaderPanel(state = state)
            }
            item {
                AutomationPanel(
                    config = state.scheduleConfig,
                    runOutput = runOutput,
                    onRunAutomation = onRunAutomation,
                    onSaveSchedule = onSaveSchedule
                )
            }
            item {
                PermissionPanel(
                    state = state,
                    onOpenAccessibility = onOpenAccessibility,
                    onRequestNotification = onRequestNotification,
                    onOpenTargetApp = onOpenTargetApp
                )
            }
            item {
                LogsPanel(logs = logs, onClearLogs = onClearLogs)
            }
            item {
                StatusPanel(state = state, onRefresh = onRefresh)
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

@Composable
private fun HeaderPanel(state: DashboardState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBFA)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BookLogo()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "起点自动签到",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (state.accessibilityEnabled && state.serviceConnected) {
                        "自动任务服务已连接"
                    } else {
                        "开启权限后可运行自动任务"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            ReadinessPill(
                label = if (state.accessibilityEnabled && state.serviceConnected) "就绪" else "待授权",
                ok = state.accessibilityEnabled && state.serviceConnected
            )
        }
    }
}

@Composable
private fun BookLogo() {
    Box(
        modifier = Modifier
            .size(46.dp)
            .background(Color(0xFFDC2626), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(9.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.96f), androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
        )
        Box(
            modifier = Modifier
                .width(5.dp)
                .fillMaxSize()
                .background(Color(0xFF991B1B), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
        )
        Column(
            modifier = Modifier
                .padding(start = 10.dp, top = 8.dp, end = 4.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color(0xFFDC2626), androidx.compose.foundation.shape.RoundedCornerShape(1.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .height(2.dp)
                    .background(Color(0xFFDC2626), androidx.compose.foundation.shape.RoundedCornerShape(1.dp))
            )
        }
    }
}

@Composable
private fun AutomationPanel(
    config: ScheduleConfig,
    runOutput: String,
    onRunAutomation: () -> Unit,
    onSaveSchedule: (ScheduleConfig) -> Unit
) {
    var enabled by remember(config) { mutableStateOf(config.enabled) }
    var hourText by remember(config) { mutableStateOf("%02d".format(config.hour)) }
    var minuteText by remember(config) { mutableStateOf("%02d".format(config.minute)) }
    var maxRestartText by remember(config) { mutableStateOf(config.maxRestartCount.toString()) }

    SectionCard(title = "自动任务") {
        Button(
            onClick = onRunAutomation,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("运行自动签到")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("每日自动运行")
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = hourText,
                onValueChange = { hourText = it.filter(Char::isDigit).take(2) },
                label = { Text("时") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = minuteText,
                onValueChange = { minuteText = it.filter(Char::isDigit).take(2) },
                label = { Text("分") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedButton(
                onClick = {
                    onSaveSchedule(
                        ScheduleConfig(
                            enabled = enabled,
                            hour = hourText.toIntOrNull()?.coerceIn(0, 23) ?: 9,
                            minute = minuteText.toIntOrNull()?.coerceIn(0, 59) ?: 0,
                            maxRestartCount = maxRestartText.toIntOrNull()
                                ?.coerceIn(0, ScheduleRepository.MAX_RESTART_COUNT)
                                ?: ScheduleRepository.DEFAULT_MAX_RESTART_COUNT
                        )
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) {
                Text("保存")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = maxRestartText,
                onValueChange = { maxRestartText = it.filter(Char::isDigit).take(2) },
                label = { Text("失败重启") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Text(
                text = "步骤卡住后最多重启 ${maxRestartText.toIntOrNull()?.coerceIn(0, ScheduleRepository.MAX_RESTART_COUNT) ?: ScheduleRepository.DEFAULT_MAX_RESTART_COUNT} 次",
                modifier = Modifier.weight(2f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SelectionContainer {
            Text(
                text = runOutput,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Color(0xFFFFF1F2),
                        androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                    )
                    .padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF7F1D1D)
            )
        }
    }
}

@Composable
private fun PermissionPanel(
    state: DashboardState,
    onOpenAccessibility: () -> Unit,
    onRequestNotification: () -> Unit,
    onOpenTargetApp: () -> Unit
) {
    SectionCard(title = "权限") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CompactStatus("无障碍", state.accessibilityEnabled, Modifier.weight(1f))
            CompactStatus("通知", state.notificationGranted, Modifier.weight(1f))
            CompactStatus("起点", state.targetInstalled, Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onOpenAccessibility, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("无障碍")
            }
            OutlinedButton(onClick = onRequestNotification, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("通知")
            }
            OutlinedButton(onClick = onOpenTargetApp, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("起点")
            }
        }
    }
}

@Composable
private fun CompactStatus(label: String, ok: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(
                color = if (ok) Color(0xFFEFFDF5) else Color(0xFFFFF1F2),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (ok) Color(0xFF16A34A) else Color(0xFFDC2626),
                    shape = androidx.compose.foundation.shape.CircleShape
                )
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LogsPanel(logs: List<AppLogEntry>, onClearLogs: () -> Unit) {
    SectionCard(title = "日志") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(
                onClick = onClearLogs,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
            ) {
                Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("清空")
            }
        }
        if (logs.isEmpty()) {
            Text("暂无日志", color = MaterialTheme.colorScheme.secondary)
        } else {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    logs.take(24).forEach { entry ->
                        Text(
                            text = "${entry.timestamp}  ${entry.message}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(state: DashboardState, onRefresh: () -> Unit) {
    SectionCard(title = "状态") {
        StatusRow("无障碍授权", state.accessibilityEnabled)
        StatusRow("服务连接", state.serviceConnected)
        StatusRow("通知权限", state.notificationGranted)
        StatusRow("起点读书", state.targetInstalled)
        Text(
            text = "当前窗口：${state.currentPackageName ?: "未知"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("刷新状态")
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        ReadinessPill(label = if (ok) "已就绪" else "未就绪", ok = ok)
    }
}

@Composable
private fun ReadinessPill(label: String, ok: Boolean) {
    Box(
        modifier = Modifier
            .background(
                color = if (ok) Color(0xFFEFFDF5) else Color(0xFFFFF1F2),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = if (ok) Color(0xFF15803D) else Color(0xFFBE123C),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
