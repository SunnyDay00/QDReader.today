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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.DisposableEffect
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
import today.qdreader.auto.vision.MlKitChineseOcrEngine
import today.qdreader.auto.vision.OpenCvTemplateMatcher

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
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val logs by AppLogStore.entries.collectAsStateWithLifecycle()
                var dashboardState by remember {
                    mutableStateOf(loadDashboardState(activity, scheduleRepository))
                }
                var testOutput by remember {
                    mutableStateOf("测试结果会显示在这里。")
                }
                val ocrEngine = remember { MlKitChineseOcrEngine() }
                val templateMatcher = remember { OpenCvTemplateMatcher(context) }
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
                    AppLogStore.add("框架界面已启动")
                }

                DisposableEffect(Unit) {
                    onDispose { ocrEngine.close() }
                }

                DashboardScreen(
                    state = dashboardState,
                    logs = logs,
                    testOutput = testOutput,
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
                    onRunPlaceholder = {
                        scope.launch {
                            val result = AutomationController(activity).run(AutomationTrigger.Manual)
                            AppNotifier.showStatus(
                                activity,
                                if (result.success) "自动化框架已触发" else "自动化框架未执行",
                                result.message
                            )
                            refresh()
                        }
                    },
                    onRunOcr = {
                        scope.launch {
                            testOutput = runOcrTest(activity, ocrEngine)
                            refresh()
                        }
                    },
                    onRunTemplate = {
                        scope.launch {
                            testOutput = runTemplateTest(activity, templateMatcher)
                            refresh()
                        }
                    },
                    onSaveSchedule = { config ->
                        scheduleRepository.save(config)
                        SchedulePlanner.reschedule(activity)
                        AppLogStore.add("保存每日时间：${config.label()}，启用=${config.enabled}")
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

private suspend fun runOcrTest(
    context: android.content.Context,
    ocrEngine: MlKitChineseOcrEngine
): String {
    AppLogStore.add("开始截图 OCR 测试")
    val bridge = AccessibilityBridgeImpl(context)
    val bitmap = bridge.captureScreenshot().getOrElse { error ->
        val message = "OCR 测试失败：${error.message}"
        AppLogStore.add(message)
        return message
    }

    val result = ocrEngine.recognize(bitmap).getOrElse { error ->
        bitmap.recycle()
        val message = "OCR 识别失败：${error.message}"
        AppLogStore.add(message)
        return message
    }
    bitmap.recycle()

    val preview = result.blocks.take(12).joinToString("\n") { block ->
        val bounds = block.bounds?.let { "[${it.left},${it.top},${it.right},${it.bottom}]" } ?: "[]"
        "$bounds ${block.text.replace('\n', ' ')}"
    }.ifBlank { "未识别到文字" }

    val message = "OCR 完成：${result.blocks.size} 个文本块，耗时 ${result.elapsedMillis} ms"
    AppLogStore.add(message)
    return "$message\n\n$preview"
}

private suspend fun runTemplateTest(
    context: android.content.Context,
    templateMatcher: OpenCvTemplateMatcher
): String {
    AppLogStore.add("开始 OpenCV 模板匹配测试")
    val bridge = AccessibilityBridgeImpl(context)
    val bitmap = bridge.captureScreenshot().getOrElse { error ->
        val message = "模板匹配失败：${error.message}"
        AppLogStore.add(message)
        return message
    }

    val result = templateMatcher.matchAny(bitmap).getOrElse { error ->
        bitmap.recycle()
        val message = "模板匹配异常：${error.message}"
        AppLogStore.add(message)
        return message
    }
    bitmap.recycle()

    val bounds = result.bounds?.let { "[${it.left},${it.top},${it.right},${it.bottom}]" } ?: "[]"
    val message = "模板匹配：${result.message}，模板=${result.templateName ?: "无"}，分数=${"%.3f".format(result.score)}，阈值=${result.threshold}，位置=$bounds，耗时 ${result.elapsedMillis} ms"
    AppLogStore.add(message)
    return message
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(
    state: DashboardState,
    logs: List<AppLogEntry>,
    testOutput: String,
    onRefresh: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestNotification: () -> Unit,
    onOpenTargetApp: () -> Unit,
    onRunPlaceholder: () -> Unit,
    onRunOcr: () -> Unit,
    onRunTemplate: () -> Unit,
    onSaveSchedule: (ScheduleConfig) -> Unit,
    onClearLogs: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("QDReader.today", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                StatusPanel(state = state, onRefresh = onRefresh)
            }
            item {
                PermissionPanel(
                    onOpenAccessibility = onOpenAccessibility,
                    onRequestNotification = onRequestNotification,
                    onOpenTargetApp = onOpenTargetApp
                )
            }
            item {
                TestPanel(
                    testOutput = testOutput,
                    onRunPlaceholder = onRunPlaceholder,
                    onRunOcr = onRunOcr,
                    onRunTemplate = onRunTemplate
                )
            }
            item {
                SchedulePanel(
                    config = state.scheduleConfig,
                    onSaveSchedule = onSaveSchedule
                )
            }
            item {
                LogsPanel(logs = logs, onClearLogs = onClearLogs)
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
        OutlinedButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
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
        Box(
            modifier = Modifier
                .background(
                    color = if (ok) Color(0xFFE0F2F1) else Color(0xFFFFF1F2),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = if (ok) "已就绪" else "未就绪",
                color = if (ok) Color(0xFF0F766E) else Color(0xFFBE123C),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun PermissionPanel(
    onOpenAccessibility: () -> Unit,
    onRequestNotification: () -> Unit,
    onOpenTargetApp: () -> Unit
) {
    SectionCard(title = "权限") {
        Button(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("打开无障碍设置")
        }
        OutlinedButton(onClick = onRequestNotification, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Notifications, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("请求通知权限")
        }
        OutlinedButton(onClick = onOpenTargetApp, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("打开起点读书")
        }
    }
}

@Composable
private fun TestPanel(
    testOutput: String,
    onRunPlaceholder: () -> Unit,
    onRunOcr: () -> Unit,
    onRunTemplate: () -> Unit
) {
    SectionCard(title = "测试") {
        Button(onClick = onRunOcr, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Search, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("截图并识别文字")
        }
        OutlinedButton(onClick = onRunTemplate, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Search, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("测试模板匹配")
        }
        OutlinedButton(onClick = onRunPlaceholder, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("触发自动化占位流程")
        }
        SelectionContainer {
            Text(
                text = testOutput,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Color(0xFFF1F5F9),
                        androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                    )
                    .padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun SchedulePanel(
    config: ScheduleConfig,
    onSaveSchedule: (ScheduleConfig) -> Unit
) {
    var enabled by remember(config) { mutableStateOf(config.enabled) }
    var hourText by remember(config) { mutableStateOf("%02d".format(config.hour)) }
    var minuteText by remember(config) { mutableStateOf("%02d".format(config.minute)) }

    SectionCard(title = "调度") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("每日占位任务")
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
        }

        Button(
            onClick = {
                onSaveSchedule(
                    ScheduleConfig(
                        enabled = enabled,
                        hour = hourText.toIntOrNull()?.coerceIn(0, 23) ?: 9,
                        minute = minuteText.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Schedule, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("保存调度")
        }
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
                Icon(Icons.Filled.Clear, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("清空")
            }
        }
        if (logs.isEmpty()) {
            Text("暂无日志", color = MaterialTheme.colorScheme.secondary)
        } else {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    logs.take(30).forEach { entry ->
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
