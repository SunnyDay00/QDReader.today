package today.qdreader.auto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import today.qdreader.auto.accessibility.AccessibilityBridgeImpl
import today.qdreader.auto.automation.AutomationController
import today.qdreader.auto.automation.AutomationRunState
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
    val exactAlarmAllowed: Boolean,
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
                val automationRunning by AutomationRunState.isRunning.collectAsStateWithLifecycle()
                var dashboardState by remember {
                    mutableStateOf(loadDashboardState(activity, scheduleRepository))
                }
                var runOutput by remember {
                    mutableStateOf("尚未运行自动任务。")
                }

                fun refresh() {
                    dashboardState = loadDashboardState(activity, scheduleRepository)
                }

                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    refresh()
                    if (scheduleRepository.load().enabled) {
                        SchedulePlanner.reschedule(activity)
                    }
                }

                LaunchedEffect(Unit) {
                    AppLogStore.add("起点自动签到界面已启动")
                }

                DashboardScreen(
                    state = dashboardState,
                    logs = logs,
                    runOutput = runOutput,
                    automationRunning = automationRunning,
                    onRefresh = { refresh() },
                    onRunAutomation = {
                        scope.launch {
                            runOutput = "自动任务运行中..."
                            try {
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
                            } catch (_: CancellationException) {
                                runOutput = "自动任务已停止。"
                                AppLogStore.add("自动任务已由用户停止，不再继续重启")
                            } finally {
                                refresh()
                            }
                        }
                    },
                    onStopAutomation = {
                        if (AutomationRunState.stop()) {
                            runOutput = "自动任务已停止。"
                        }
                    },
                    onSaveSchedule = { config ->
                        scheduleRepository.save(config)
                        SchedulePlanner.reschedule(activity)
                        AppLogStore.add("保存每日自动任务时间：${config.label()}，启用=${config.enabled}")
                        refresh()
                    },
                    onOpenExactAlarmSettings = {
                        DeviceStatus.openExactAlarmSettings(activity)
                    },
                    onOpenAccessibilitySettings = {
                        DeviceStatus.openAccessibilitySettings(activity)
                    },
                    onOpenNotificationSettings = {
                        DeviceStatus.openNotificationSettings(activity)
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
        exactAlarmAllowed = DeviceStatus.canScheduleExactAlarms(context),
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
    automationRunning: Boolean,
    onRefresh: () -> Unit,
    onRunAutomation: () -> Unit,
    onStopAutomation: () -> Unit,
    onSaveSchedule: (ScheduleConfig) -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onClearLogs: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "起点自动签到",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = AppBackground
                )
            )
        },
        containerColor = AppBackground
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                HeaderPanel(
                    state = state,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings
                )
            }
            item {
                AutomationPanel(
                    config = state.scheduleConfig,
                    exactAlarmAllowed = state.exactAlarmAllowed,
                    runOutput = runOutput,
                    automationRunning = automationRunning,
                    onRunAutomation = onRunAutomation,
                    onStopAutomation = onStopAutomation,
                    onSaveSchedule = onSaveSchedule,
                    onOpenExactAlarmSettings = onOpenExactAlarmSettings
                )
            }
            item { LogsPanel(logs = logs, onClearLogs = onClearLogs) }
            item {
                StatusPanel(
                    state = state,
                    onRefresh = onRefresh,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onOpenExactAlarmSettings = onOpenExactAlarmSettings
                )
            }
            item { VersionFooter() }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                if (action != null) {
                    action()
                }
            }
            content()
        }
    }
}

@Composable
private fun HeaderPanel(
    state: DashboardState,
    onOpenAccessibilitySettings: () -> Unit
) {
    val ready = state.accessibilityEnabled && state.serviceConnected && state.targetInstalled
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BookLogo(modifier = Modifier.size(44.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "起点自动签到",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (ready) "服务已连接，可以运行自动任务" else "完成权限后再运行自动任务",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ReadinessPill(label = if (ready) "就绪" else "待处理", ok = ready)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusMetric(
                    "无障碍",
                    state.accessibilityEnabled,
                    Modifier.weight(1f),
                    onClick = if (state.accessibilityEnabled) null else onOpenAccessibilitySettings
                )
                StatusMetric(
                    "服务",
                    state.serviceConnected,
                    Modifier.weight(1f),
                    onClick = if (state.serviceConnected) null else onOpenAccessibilitySettings
                )
                StatusMetric("起点", state.targetInstalled, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BookLogo(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PrimaryRed)
            .padding(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White)
        )
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxSize()
                .clip(RoundedCornerShape(2.dp))
                .background(DarkRed)
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
                    .clip(RoundedCornerShape(1.dp))
                    .background(PrimaryRed)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.68f)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(PrimaryRed)
            )
        }
    }
}

@Composable
private fun AutomationPanel(
    config: ScheduleConfig,
    exactAlarmAllowed: Boolean,
    runOutput: String,
    automationRunning: Boolean,
    onRunAutomation: () -> Unit,
    onStopAutomation: () -> Unit,
    onSaveSchedule: (ScheduleConfig) -> Unit,
    onOpenExactAlarmSettings: () -> Unit
) {
    var enabled by remember(config) { mutableStateOf(config.enabled) }
    var hourText by remember(config) { mutableStateOf("%02d".format(config.hour)) }
    var minuteText by remember(config) { mutableStateOf("%02d".format(config.minute)) }
    var maxRestartText by remember(config) { mutableStateOf(config.maxRestartCount.toString()) }

    SectionCard(title = "自动任务") {
        Button(
            onClick = if (automationRunning) onStopAutomation else onRunAutomation,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (automationRunning) ErrorRed else PrimaryRed
            )
        ) {
            Icon(
                if (automationRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (automationRunning) "停止运行" else "运行自动签到",
                fontWeight = FontWeight.SemiBold
            )
        }

        SettingLine(
            icon = { Icon(Icons.Filled.Schedule, contentDescription = null, tint = TextSecondary) },
            title = "每日自动运行",
            value = config.label(),
            trailing = { Switch(checked = enabled, onCheckedChange = { enabled = it }) }
        )

        if (enabled && !exactAlarmAllowed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFFF5F5))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "精确定时未授权，系统可能延迟运行",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = DarkRed
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onOpenExactAlarmSettings,
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("授权")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            CompactNumberField(
                value = hourText,
                onValueChange = { hourText = it.filter(Char::isDigit).take(2) },
                label = "时",
                modifier = Modifier.weight(1f)
            )
            CompactNumberField(
                value = minuteText,
                onValueChange = { minuteText = it.filter(Char::isDigit).take(2) },
                label = "分",
                modifier = Modifier.weight(1f)
            )
            CompactNumberField(
                value = maxRestartText,
                onValueChange = { maxRestartText = it.filter(Char::isDigit).take(2) },
                label = "重启",
                modifier = Modifier.weight(1f)
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
                    .height(44.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("保存")
            }
        }

        Text(
            text = "步骤卡住后最多重启 ${maxRestartText.toIntOrNull()?.coerceIn(0, ScheduleRepository.MAX_RESTART_COUNT) ?: ScheduleRepository.DEFAULT_MAX_RESTART_COUNT} 次",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        SelectionContainer {
            Text(
                text = if (automationRunning) "自动任务运行中..." else runOutput,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFFF5F5))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                style = MaterialTheme.typography.bodySmall,
                color = DarkRed
            )
        }
    }
}

@Composable
private fun LogsPanel(logs: List<AppLogEntry>, onClearLogs: () -> Unit) {
    SectionCard(
        title = "日志",
        action = {
            OutlinedButton(
                onClick = onClearLogs,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
            ) {
                Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("清空")
            }
        }
    ) {
        if (logs.isEmpty()) {
            Text("暂无日志", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        } else {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF8FAFC))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    logs.take(28).forEach { entry ->
                        Text(
                            text = "${entry.timestamp}  ${entry.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(
    state: DashboardState,
    onRefresh: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit
) {
    SectionCard(title = "状态") {
        StatusLine("无障碍授权", state.accessibilityEnabled, onOpenAccessibilitySettings)
        StatusLine("服务连接", state.serviceConnected, onOpenAccessibilitySettings)
        StatusLine("通知权限", state.notificationGranted, onOpenNotificationSettings)
        StatusLine("精确定时", state.exactAlarmAllowed, onOpenExactAlarmSettings)
        StatusLine("起点读书", state.targetInstalled)
        Text(
            text = "当前窗口：${state.currentPackageName ?: "未知"}",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        OutlinedButton(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("刷新状态")
        }
    }
}

@Composable
private fun SettingLine(
    icon: @Composable () -> Unit,
    title: String,
    value: String,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF8FAFC))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        icon()
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Text(value, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        trailing()
    }
}

@Composable
private fun CompactNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = TextPrimary,
                textAlign = TextAlign.Center
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun VersionFooter() {
    Text(
        text = "版本 v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun StatusMetric(
    label: String,
    ok: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (ok) Color(0xFFF0FDF4) else Color(0xFFFEF2F2))
            .clickable(enabled = !ok && onClick != null) { onClick?.invoke() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Dot(ok)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (ok) Green else ErrorRed,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !ok && onClick != null) { onClick?.invoke() }
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        ReadinessPill(
            label = if (ok) "已就绪" else if (onClick != null) "去授权" else "未就绪",
            ok = ok
        )
    }
}

@Composable
private fun ReadinessPill(label: String, ok: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (ok) Color(0xFFEFFDF5) else Color(0xFFFFF1F2))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Dot(ok)
        Text(
            text = label,
            color = if (ok) Green else ErrorRed,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun Dot(ok: Boolean) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(if (ok) Green else ErrorRed)
    )
}

private val AppBackground = Color(0xFFF6F7F9)
private val BorderColor = Color(0xFFE5E7EB)
private val PrimaryRed = Color(0xFFE92F2A)
private val DarkRed = Color(0xFFB91C1C)
private val ErrorRed = Color(0xFFBE123C)
private val Green = Color(0xFF16A34A)
private val TextPrimary = Color(0xFF111827)
private val TextSecondary = Color(0xFF6B7280)
