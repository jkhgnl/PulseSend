package com.example.firstapp.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.firstapp.model.MessageDirection
import com.example.firstapp.model.MessageItem
import com.example.firstapp.model.ServerSnapshot
import com.example.firstapp.model.TransferStatus
import com.example.firstapp.ui.theme.Aurora
import com.example.firstapp.ui.theme.Coral
import com.example.firstapp.ui.theme.Dusk
import com.example.firstapp.ui.theme.Ember
import com.example.firstapp.ui.theme.Glass
import com.example.firstapp.ui.theme.Ice
import com.example.firstapp.ui.theme.Mist
import com.example.firstapp.ui.theme.Night
import com.example.firstapp.ui.theme.Slate
import com.example.firstapp.viewmodel.DeviceUi
import com.example.firstapp.viewmodel.MainViewModel
import com.example.firstapp.viewmodel.MainViewModelFactory
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PulseSendApp() {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel(factory = MainViewModelFactory(context))
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.Devices) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addFiles(uris)
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.updateIncomingFolder(uri)
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    var pairingDevice by remember { mutableStateOf<DeviceUi?>(null) }
    var pairCode by remember { mutableStateOf("") }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = { PulseTopBar() },
        bottomBar = {
            PulseBottomBar(
                selectedTab = selectedTab,
                onSelect = { selectedTab = it }
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Mist)
                .padding(padding)
        ) {
            AnimatedBackdrop()
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                when (selectedTab) {
                    MainTab.Devices -> {
                        item { HeroPanel() }
                        item {
                            PairCodeSection(
                                snapshot = uiState.serverSnapshot,
                                onRefresh = { viewModel.refreshPairCode() }
                            )
                        }
                        item {
                            DeviceSection(
                                devices = uiState.devices,
                                selectedDeviceId = uiState.selectedDeviceId,
                                onSelect = { viewModel.selectDevice(it) },
                                onPair = { pairingDevice = it }
                            )
                        }
                    }
                    MainTab.Files -> {
                        item {
                            IncomingFolderSection(
                                incomingFolderLabel = viewModel.getIncomingFolderLabel(),
                                onChangeIncomingFolder = { folderLauncher.launch(null) }
                            )
                        }
                        item {
                            SendSection(
                                selectedFiles = uiState.selectedFiles,
                                onPickFiles = { launcher.launch(arrayOf("*/*")) },
                                onClear = { viewModel.clearFiles() },
                                onSend = { viewModel.sendSelectedFiles() }
                            )
                        }
                        item {
                            TransferSection(
                                transfers = uiState.transfers,
                                onOpenTransfer = { transfer ->
                                    val error = openTransferContent(context, transfer)
                                    if (error != null) {
                                        viewModel.notifyMessage(error)
                                    }
                                },
                                onDeleteTransfer = { transfer ->
                                    val error = deleteTransferContent(context, transfer)
                                    if (error == null) {
                                        viewModel.removeTransfer(transfer.id)
                                        viewModel.notifyMessage("已删除 ${transfer.fileName}")
                                    } else {
                                        viewModel.notifyMessage(error)
                                    }
                                }
                            )
                        }
                    }
                    MainTab.Messages -> {
                        item {
                            TextMessageSection(
                                text = uiState.textDraft,
                                onTextChange = { viewModel.updateTextDraft(it) },
                                onSend = { viewModel.sendTextMessage() },
                                onClear = { viewModel.clearTextDraft() }
                            )
                        }
                        item { MessageSection(messages = uiState.messages) }
                    }
                }
            }
        }
    }

    if (pairingDevice != null) {
        AlertDialog(
            onDismissRequest = { pairingDevice = null },
            title = { Text("设备配对") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("请输入 ${pairingDevice!!.info.name} 上显示的 8 位配对码。")
                    TextField(
                        value = pairCode,
                        onValueChange = { value ->
                            pairCode = value.filter { it.isDigit() }.take(8)
                        },
                        placeholder = { Text("12345678") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val device = pairingDevice!!.info
                    viewModel.pairDevice(device, pairCode)
                    pairCode = ""
                    pairingDevice = null
                }) {
                    Text("确认配对")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pairCode = ""
                    pairingDevice = null
                }) {
                    Text("取消")
                }
            }
        )
    }

    uiState.alertMessage?.let { alert ->
        AlertDialog(
            onDismissRequest = { viewModel.clearAlertMessage() },
            title = { Text("提示") },
            text = { Text(alert) },
            confirmButton = {
                Button(onClick = { viewModel.clearAlertMessage() }) {
                    Text("确定")
                }
            }
        )
    }
}

private enum class MainTab(val label: String, val iconText: String) {
    Devices("\u8bbe\u5907", "\u8bbe"),
    Files("\u6587\u4ef6", "\u6587"),
    Messages("\u6d88\u606f", "\u4fe1")
}

@Composable
private fun PulseBottomBar(
    selectedTab: MainTab,
    onSelect: (MainTab) -> Unit
) {
    NavigationBar(containerColor = Night.copy(alpha = 0.9f)) {
        MainTab.values().forEach { tab ->
            val isSelected = tab == selectedTab
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(tab) },
                icon = {
                    Text(
                        text = tab.iconText,
                        color = if (isSelected) Ice else Ice.copy(alpha = 0.6f),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                label = {
                    Text(
                        text = tab.label,
                        maxLines = 1,
                        fontSize = 12.sp,
                        color = if (isSelected) Ice else Ice.copy(alpha = 0.6f)
                    )
                },
                alwaysShowLabel = true
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PulseTopBar() {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = "脉冲传输",
                    style = MaterialTheme.typography.displaySmall,
                    color = Ice
                )
                Text(
                    text = "端到端加密 · 即连即传 · 跨设备",
                    style = MaterialTheme.typography.labelLarge,
                    color = Ice.copy(alpha = 0.7f)
                )
            }
        },
        actions = {},
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent
        )
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroPanel() {
    GlassCard {
        Text(
            text = "安全流转",
            style = MaterialTheme.typography.titleLarge,
            color = Ice
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "发现附近设备，完成一次验证后即可高速传输，并全程端到端加密。",
            style = MaterialTheme.typography.bodyLarge,
            color = Ice.copy(alpha = 0.8f)
        )
        Spacer(modifier = Modifier.height(14.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricChip(label = "TLS 1.3", value = "已启用")
            MetricChip(label = "端到端", value = "ChaCha20")
            MetricChip(label = "断点续传", value = "已开启")
        }
    }
}

@Composable
private fun MetricChip(label: String, value: String) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text = label, color = Ice.copy(alpha = 0.7f), fontSize = 11.sp)
        Text(text = value, color = Ice, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PairCodeSection(
    snapshot: ServerSnapshot,
    onRefresh: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = "本机配对码", subtitle = "让对方输入该 8 位码完成配对")
        GlassCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = snapshot.pairCode,
                    style = MaterialTheme.typography.displaySmall,
                    color = Aurora,
                    fontWeight = FontWeight.Bold
                )
                FilledTonalButton(onClick = onRefresh) {
                    Text("刷新配对码")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "状态：${snapshot.statusText}", color = Ice.copy(alpha = 0.8f))
            if (snapshot.fingerprint.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "证书指纹：${shortFingerprint(snapshot.fingerprint)}",
                    color = Ice.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun DeviceSection(
    devices: List<DeviceUi>,
    selectedDeviceId: String?,
    onSelect: (String?) -> Unit,
    onPair: (DeviceUi) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = "附近设备", subtitle = "同一 Wi-Fi 下自动发现")
        if (devices.isEmpty()) {
            GlassCard {
                Text(
                    text = "正在扫描局域网设备...",
                    color = Ice.copy(alpha = 0.75f)
                )
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(devices) { device ->
                    DeviceCard(
                        device = device,
                        isSelected = device.info.id == selectedDeviceId,
                        onClick = { onSelect(device.info.id) },
                        onPair = { onPair(device) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceUi,
    isSelected: Boolean,
    onClick: () -> Unit,
    onPair: () -> Unit
) {
    val highlight by animateColorAsState(
        targetValue = if (isSelected) Aurora else Color.White.copy(alpha = 0.12f),
        label = "deviceHighlight"
    )
    val pairLabel = when {
        device.canSend && device.canReceive -> "双向已配对"
        device.canSend -> "可发送"
        device.canReceive -> "已授权对方"
        else -> "未配对"
    }
    val onlineLabel = if (device.isOnline) "在线" else "离线"
    val statusColor = if (!device.isOnline) {
        Coral
    } else when {
        device.canSend && device.canReceive -> Aurora
        device.canSend -> Aurora
        device.canReceive -> Ember
        else -> Coral
    }
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .width(220.dp)
            .clip(shape)
            .background(highlight.copy(alpha = 0.18f))
            .border(1.dp, highlight.copy(alpha = 0.5f), shape)
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Text(text = device.info.name, color = Ice, fontWeight = FontWeight.SemiBold)
        Text(
            text = device.info.platform.uppercase(),
            color = Ice.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = statusColor)
            Spacer(modifier = Modifier.width(6.dp))
            StatusBadge(
                text = onlineLabel,
                background = if (device.isOnline) Color(0xFF1F9D6A) else Color(0xFFD64045)
            )
            Spacer(modifier = Modifier.width(6.dp))
            StatusBadge(
                text = pairLabel,
                background = Color.White.copy(alpha = 0.12f)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onPair,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    device.canSend && device.canReceive -> "重新配对"
                    device.canSend -> "重新配对"
                    device.canReceive -> "补充配对"
                    else -> "配对"
                }
            )
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .width(8.dp)
            .height(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun StatusBadge(text: String, background: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text = text, color = Color.White, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SendSection(
    selectedFiles: List<com.example.firstapp.model.FileDescriptor>,
    onPickFiles: () -> Unit,
    onClear: () -> Unit,
    onSend: () -> Unit
) {
    GlassCard {
        SectionHeader(title = "发送文件", subtitle = "从系统文件选择器挑选")
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilledTonalButton(onClick = onPickFiles) { Text("选择文件") }
            TextButton(onClick = onClear) { Text("清空") }
            Button(onClick = onSend) { Text("立即发送", maxLines = 1) }
        }
        AnimatedVisibility(visible = selectedFiles.isNotEmpty()) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                selectedFiles.forEach { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = file.name, color = Ice)
                        Text(text = formatBytes(file.size), color = Ice.copy(alpha = 0.7f))
                    }
                    HorizontalDivider(color = Ice.copy(alpha = 0.1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextMessageSection(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onClear: () -> Unit
) {
    GlassCard {
        SectionHeader(
            title = "发送文本",
            subtitle = "端到端加密的短消息"
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("请输入要发送的内容") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilledTonalButton(onClick = onClear) { Text("清空") }
            Button(onClick = onSend) { Text("发送") }
        }
    }
}

@Composable
private fun MessageSection(messages: List<MessageItem>) {
    var fullMessageContent by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = "文本消息", subtitle = "端到端加密的收发记录")
        if (messages.isEmpty()) {
            GlassCard {
                Text(
                    text = "暂无文本消息。",
                    color = Ice.copy(alpha = 0.7f)
                )
            }
        } else {
            messages.forEach { message ->
                MessageCard(
                    message = message,
                    onExpandRequest = { fullMessageContent = it }
                )
            }
        }
    }

    if (fullMessageContent != null) {
        AlertDialog(
            onDismissRequest = { fullMessageContent = null },
            title = { Text("消息全文") },
            text = { Text(text = fullMessageContent.orEmpty()) },
            confirmButton = {
                Button(onClick = { fullMessageContent = null }) {
                    Text("关闭")
                }
            }
        )
    }

}

@Composable
private fun MessageCard(
    message: MessageItem,
    onExpandRequest: (String) -> Unit
) {
    val tone = if (message.direction == MessageDirection.Incoming) Aurora else Ember
    val clipboard = LocalClipboardManager.current
    var hasVisualOverflow by remember(message.id, message.content) { mutableStateOf(false) }
    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = if (message.direction == MessageDirection.Incoming) "收自" else "发送至",
                    color = tone,
                    fontWeight = FontWeight.SemiBold
                )
                Text(text = formatTime(message.timestamp), color = Slate)
            }
            Text(
                text = "可复制",
                color = Ice.copy(alpha = 0.75f),
                fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = message.peerName, color = Ice.copy(alpha = 0.8f))
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message.content,
            color = Ice,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                hasVisualOverflow = result.hasVisualOverflow
            }
        )
        if (hasVisualOverflow) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onExpandRequest(message.content) }) {
                    Text("...")
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        FilledTonalButton(
            onClick = { clipboard.setText(AnnotatedString(message.content)) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("复制消息内容")
        }
    }
}

@Composable
private fun IncomingFolderSection(
    incomingFolderLabel: String,
    onChangeIncomingFolder: () -> Unit
) {
    GlassCard {
        SectionHeader(title = "保存位置设置", subtitle = "接收文件默认保存到这里")
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onChangeIncomingFolder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("点此选择保存位置")
        }
        Text(text = incomingFolderLabel, color = Ice.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

@Composable
private fun TransferSection(
    transfers: List<com.example.firstapp.model.TransferItem>,
    onOpenTransfer: (com.example.firstapp.model.TransferItem) -> Unit,
    onDeleteTransfer: (com.example.firstapp.model.TransferItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = "传输进度", subtitle = "实时进度与速度")
        if (transfers.isEmpty()) {
            GlassCard {
                Text(
                    text = "暂无传输任务。请先配对设备并选择文件。",
                    color = Ice.copy(alpha = 0.7f)
                )
            }
        } else {
            transfers.forEach { transfer ->
                TransferCard(
                    transfer = transfer,
                    onOpen = { onOpenTransfer(transfer) },
                    onDelete = { onDeleteTransfer(transfer) }
                )
            }
        }
    }
}

@Composable
private fun TransferCard(
    transfer: com.example.firstapp.model.TransferItem,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val progress = if (transfer.totalBytes <= 0L) 0f
    else (transfer.sentBytes.toFloat() / transfer.totalBytes.toFloat()).coerceIn(0f, 1f)
    val progressColor = when (transfer.status) {
        TransferStatus.Completed -> Aurora
        TransferStatus.Failed -> Coral
        else -> Ember
    }
    val canOpen = transfer.status == TransferStatus.Completed &&
        (!transfer.localPath.isNullOrBlank() || !transfer.localUri.isNullOrBlank())
    val canDelete = !transfer.localPath.isNullOrBlank() || !transfer.localUri.isNullOrBlank()

    GlassCard(
        modifier = if (canOpen) Modifier.clickable(onClick = onOpen) else Modifier
    ) {
        Text(text = transfer.fileName, color = Ice, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            val directionText = if (transfer.direction == com.example.firstapp.model.TransferDirection.Upload) {
                "发送文件"
            } else {
                "接收文件"
            }
            Text(text = "$directionText · ${statusText(transfer.status)}", color = Slate)
            Text(text = "${formatBytes(transfer.speedBytesPerSec)}/s", color = Slate)
        }
        Text(text = "时间：${formatDateTime(transfer.updatedAt)}", color = Slate.copy(alpha = 0.9f), fontSize = 12.sp)
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            color = progressColor,
            trackColor = Color.White.copy(alpha = 0.1f),
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "${formatBytes(transfer.sentBytes)} / ${formatBytes(transfer.totalBytes)}",
            color = Ice.copy(alpha = 0.7f)
        )
        if (canOpen) {
            Text(
                text = "点击卡片可预览/打开",
                color = Ice.copy(alpha = 0.65f),
                fontSize = 12.sp
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDelete, enabled = canDelete) {
                Text("🗑")
            }
            Button(onClick = onOpen, enabled = canOpen) {
                Text("打开/预览")
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, color = Ice, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text(text = subtitle, color = Ice.copy(alpha = 0.65f))
    }
}

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val transition = rememberInfiniteTransition(label = "glassFlow")
    val glow by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glassGlow"
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Glass.copy(alpha = glow))
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

@Composable
private fun AnimatedBackdrop() {
    val transition = rememberInfiniteTransition(label = "bgShift")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bgShiftValue"
    )
    val gradient = Brush.linearGradient(
        0f to Night,
        0.5f to Dusk,
        1f to Night
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = size.minDimension * 0.6f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Aurora.copy(alpha = 0.35f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(size.width * (0.2f + 0.3f * shift), size.height * 0.3f),
                radius = radius
            ),
            radius = radius
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Coral.copy(alpha = 0.3f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(size.width * (0.8f - 0.2f * shift), size.height * 0.7f),
                radius = radius * 0.8f
            ),
            radius = radius * 0.8f
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024f
    if (kb < 1024) return String.format("%.1fKB", kb)
    val mb = kb / 1024f
    if (mb < 1024) return String.format("%.1fMB", mb)
    val gb = mb / 1024f
    return String.format("%.1fGB", gb)
}

private fun formatTime(timestamp: Long): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.CHINA)
    return formatter.format(Date(timestamp))
}

private fun formatDateTime(timestamp: Long): String {
    val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
    return formatter.format(Date(timestamp))
}

private fun shortFingerprint(value: String): String {
    if (value.length <= 18) return value
    return value.take(18) + "..."
}

private fun openTransferContent(
    context: android.content.Context,
    transfer: com.example.firstapp.model.TransferItem
): String? {
    if (isApkFile(transfer.fileName)) {
        val installUri = prepareApkInstallUri(context, transfer) ?: return "APK 文件不可用"
        return openApkInstaller(context, installUri)
    }
    val targetUri = buildTransferUri(context, transfer) ?: return "文件不存在或路径不可用"
    val mime = guessMimeType(transfer.fileName)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(targetUri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(intent, "选择打开方式").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(chooser)
        null
    } catch (_: ActivityNotFoundException) {
        val fallback = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(targetUri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallbackChooser = Intent.createChooser(fallback, "选择打开方式").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(fallbackChooser)
            null
        } catch (_: Exception) {
            "没有可用应用打开该文件"
        }
    } catch (e: Exception) {
        "打开失败：${e.message ?: "未知错误"}"
    }
}

private fun prepareApkInstallUri(
    context: android.content.Context,
    transfer: com.example.firstapp.model.TransferItem
): Uri? {
    val sourceUri = buildTransferUri(context, transfer) ?: return null
    val cacheFile = File(context.cacheDir, "install_${transfer.id}.apk")
    return runCatching {
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        } ?: return null
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cacheFile)
    }.getOrNull()
}

private fun openApkInstaller(context: android.content.Context, apkUri: Uri): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !context.packageManager.canRequestPackageInstalls()
    ) {
        openUnknownSourcesSettings(context)
        return "请先允许本应用安装未知应用，然后返回重试安装"
    }

    val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        clipData = ClipData.newUri(context.contentResolver, "apk", apkUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        clipData = ClipData.newUri(context.contentResolver, "apk", apkUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        if (viewIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(viewIntent)
        } else if (installIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(installIntent)
        } else {
            return "系统未找到可用的安装器"
        }
        null
    } catch (_: ActivityNotFoundException) {
        return try {
            context.startActivity(installIntent)
            null
        } catch (_: Exception) {
            "系统未找到可用的安装器"
        }
    } catch (e: SecurityException) {
        openUnknownSourcesSettings(context)
        "系统拦截安装，请先允许本应用安装未知应用后重试"
    } catch (e: Exception) {
        "安装启动失败：${e.message ?: "未知错误"}"
    }
}

private fun openUnknownSourcesSettings(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

private fun buildTransferUri(
    context: android.content.Context,
    transfer: com.example.firstapp.model.TransferItem
): Uri? {
    transfer.localUri?.takeIf { it.isNotBlank() }?.let { return Uri.parse(it) }
    val path = transfer.localPath?.takeIf { it.isNotBlank() } ?: return null
    val file = File(path)
    if (!file.exists()) return null
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
}

private fun deleteTransferContent(
    context: android.content.Context,
    transfer: com.example.firstapp.model.TransferItem
): String? {
    transfer.localUri?.takeIf { it.isNotBlank() }?.let { raw ->
        val uri = Uri.parse(raw)
        val deleted = runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }.getOrDefault(false)
        return if (deleted) null else "删除失败：文件不存在或无权限"
    }
    val path = transfer.localPath?.takeIf { it.isNotBlank() } ?: return "删除失败：路径不可用"
    val file = File(path)
    if (!file.exists()) return "删除失败：文件不存在"
    return if (file.delete()) null else "删除失败：文件被占用或无权限"
}

private fun guessMimeType(fileName: String): String {
    val lower = fileName.lowercase(Locale.ROOT)
    return when {
        lower.endsWith(".apk") -> "application/vnd.android.package-archive"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") -> "image/*"
        lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".mov") || lower.endsWith(".avi") -> "video/*"
        else -> java.net.URLConnection.guessContentTypeFromName(fileName) ?: "*/*"
    }
}

private fun isApkFile(fileName: String): Boolean = fileName.lowercase(Locale.ROOT).endsWith(".apk")

private fun statusText(status: TransferStatus): String =
    when (status) {
        TransferStatus.Pending -> "等待中"
        TransferStatus.Preparing -> "准备中"
        TransferStatus.Transferring -> "传输中"
        TransferStatus.Completed -> "已完成"
        TransferStatus.Failed -> "已失败"
    }


