package com.example.firstapp.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.firstapp.core.device.DeviceIdentityProvider
import com.example.firstapp.data.TrustedDeviceStore
import com.example.firstapp.model.DeviceInfo
import com.example.firstapp.model.FileDescriptor
import com.example.firstapp.model.MessageDirection
import com.example.firstapp.model.MessageItem
import com.example.firstapp.model.ServerSnapshot
import com.example.firstapp.model.TransferDirection
import com.example.firstapp.model.TransferItem
import com.example.firstapp.model.TransferStatus
import com.example.firstapp.network.DiscoveryManager
import com.example.firstapp.network.ServerHost
import com.example.firstapp.network.SecureTransport
import com.example.firstapp.network.TransferClient
import com.example.firstapp.util.FileResolver
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

data class DeviceUi(
    val info: DeviceInfo,
    val canSend: Boolean,
    val canReceive: Boolean,
    val isOnline: Boolean
)

data class MainUiState(
    val devices: List<DeviceUi> = emptyList(),
    val selectedDeviceId: String? = null,
    val selectedFiles: List<FileDescriptor> = emptyList(),
    val transfers: List<TransferItem> = emptyList(),
    val isDiscovering: Boolean = true,
    val message: String? = null,
    val alertMessage: String? = null,
    val textDraft: String = "",
    val serverSnapshot: ServerSnapshot = ServerSnapshot(),
    val messages: List<MessageItem> = emptyList()
)

class MainViewModel(private val appContext: Context) : ViewModel() {
    private val identity = DeviceIdentityProvider.from(appContext)
    private val store = TrustedDeviceStore(appContext)
    private val discoveryManager = DiscoveryManager(appContext, identity, store)
    private val secureTransport = SecureTransport(store)
    private val transferClient = TransferClient(appContext, secureTransport, identity)
    private val serverHost = ServerHost(appContext, identity, store)

    private val discoveredDevices = MutableStateFlow<Map<String, DeviceInfo>>(emptyMap())
    private val selectedFiles = MutableStateFlow<List<FileDescriptor>>(emptyList())
    private val transfers = MutableStateFlow<List<TransferItem>>(emptyList())
    private val selectedDeviceId = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)
    private val alertMessage = MutableStateFlow<String?>(null)
    private val textDraft = MutableStateFlow("")
    private val serverSnapshot = MutableStateFlow(ServerSnapshot())
    private val messages = MutableStateFlow<List<MessageItem>>(emptyList())
    private val heartbeatJobs = mutableMapOf<String, Job>()
    private val heartbeatFailures = ConcurrentHashMap<String, Int>()

    companion object {
        private const val ONLINE_WINDOW_MS = 8_000L
        private const val OFFLINE_GRACE_WINDOW_MS = 8_000L
        private const val OFFLINE_FAILURE_THRESHOLD = 3
    }

    private val deviceGroup = combine(
        discoveredDevices,
        store.trustedDevices,
        selectedFiles
    ) { discovered, trusted, files ->
        Triple(discovered, trusted, files)
    }

    private data class TransferGroup(
        val transferItems: List<TransferItem>,
        val selectedId: String?,
        val notice: String?,
        val draft: String,
        val snapshot: ServerSnapshot,
        val messages: List<MessageItem>
    )

    private data class TransferBase(
        val transferItems: List<TransferItem>,
        val selectedId: String?,
        val notice: String?,
        val draft: String,
        val snapshot: ServerSnapshot
    )

    private val transferBase = combine(
        transfers,
        selectedDeviceId,
        message,
        textDraft,
        serverSnapshot
    ) { transferItems, selectedId, notice, draft, snapshot ->
        TransferBase(transferItems, selectedId, notice, draft, snapshot)
    }

    private val transferGroup = combine(
        transferBase,
        messages
    ) { base, messageItems ->
        TransferGroup(
            transferItems = base.transferItems,
            selectedId = base.selectedId,
            notice = base.notice,
            draft = base.draft,
            snapshot = base.snapshot,
            messages = messageItems
        )
    }

    val uiState: StateFlow<MainUiState> = combine(
        deviceGroup,
        transferGroup,
        alertMessage
    ) { (discovered, trusted, files), group, alert ->
        val now = System.currentTimeMillis()
        val devices = discovered.values.map { device ->
            val trustedRecord = trusted[device.id]
            val outgoingToken = trustedRecord?.outgoingToken
            val incomingToken = trustedRecord?.incomingToken
            val isOnline = device.lastSeen > 0 && now - device.lastSeen <= ONLINE_WINDOW_MS
            DeviceUi(
                info = device.copy(fingerprint = trustedRecord?.fingerprint),
                canSend = !outgoingToken.isNullOrBlank(),
                canReceive = !incomingToken.isNullOrBlank(),
                isOnline = isOnline
            )
        }.sortedBy { it.info.name }
        MainUiState(
            devices = devices,
            selectedDeviceId = group.selectedId,
            selectedFiles = files,
            transfers = group.transferItems,
            isDiscovering = true,
            message = group.notice,
            alertMessage = alert,
            textDraft = group.draft,
            serverSnapshot = group.snapshot,
            messages = group.messages
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            serverHost.start()
        }
        viewModelScope.launch {
            discoveryManager.discover(this).collect { device ->
                val updated = discoveredDevices.value.toMutableMap()
                updated[device.id] = device
                discoveredDevices.value = updated
                heartbeatFailures[device.id] = 0
                ensureHeartbeat(device.id)
            }
        }
        viewModelScope.launch {
            serverHost.snapshot.collect { snapshot ->
                serverSnapshot.value = snapshot
            }
        }
        viewModelScope.launch {
            serverHost.transferUpdates.collect { item ->
                upsertTransfer(item)
            }
        }
        viewModelScope.launch {
            serverHost.messageEvents.collect { messageItem ->
                addMessage(messageItem)
            }
        }
        viewModelScope.launch {
            serverHost.events.collect { notice ->
                message.value = notice
            }
        }
    }

    private fun ensureHeartbeat(deviceId: String) {
        if (heartbeatJobs.containsKey(deviceId)) return
        heartbeatJobs[deviceId] = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val current = discoveredDevices.value[deviceId]
                if (current != null && current.address.isNotBlank()) {
                    val trusted = runCatching { store.get(deviceId) }.getOrNull()
                    val hasTrust = !trusted?.fingerprint.isNullOrBlank() &&
                        !trusted?.outgoingToken.isNullOrBlank()
                    if (hasTrust) {
                        val ok = runCatching { secureTransport.ping(current) }.getOrDefault(false)
                        if (ok) {
                            heartbeatFailures[deviceId] = 0
                            val map = discoveredDevices.value.toMutableMap()
                            map[deviceId] = current.copy(lastSeen = System.currentTimeMillis())
                            discoveredDevices.value = map
                        } else {
                            val failures = (heartbeatFailures[deviceId] ?: 0) + 1
                            heartbeatFailures[deviceId] = failures
                            val recentlySeen = current.lastSeen > 0L &&
                                (System.currentTimeMillis() - current.lastSeen) <= OFFLINE_GRACE_WINDOW_MS
                            if (!recentlySeen && failures >= OFFLINE_FAILURE_THRESHOLD) {
                                val map = discoveredDevices.value.toMutableMap()
                                map[deviceId] = current.copy(lastSeen = 0L)
                                discoveredDevices.value = map
                            }
                        }
                    }
                }
                delay(2_000)
            }
        }
    }

    fun selectDevice(deviceId: String?) {
        selectedDeviceId.value = deviceId
    }

    fun addFiles(uris: List<Uri>) {
        val current = selectedFiles.value.toMutableList()
        uris.map { FileResolver.resolve(appContext, it) }.forEach { file ->
            current.add(file)
        }
        selectedFiles.value = current
    }

    fun clearFiles() {
        selectedFiles.value = emptyList()
    }

    fun updateTextDraft(value: String) {
        textDraft.value = value
    }

    fun clearTextDraft() {
        textDraft.value = ""
    }

    fun updateIncomingFolder(uri: Uri?) {
        val ok = serverHost.setIncomingFolder(uri)
        message.value = if (ok) {
            "接收目录已更新"
        } else {
            "目录不可写，请重新选择"
        }
    }

    fun getIncomingFolderLabel(): String = serverHost.getIncomingFolderLabel()

    fun notifyMessage(value: String) {
        message.value = value
    }

    fun pairDevice(device: DeviceInfo, code: String) {
        val handler = CoroutineExceptionHandler { _, throwable ->
            Log.e("PulseSend", "Pair failed", throwable)
            val detail = throwable.message?.take(80)?.trim()
            val name = throwable::class.java.simpleName
            message.value = if (!detail.isNullOrEmpty()) {
                "配对失败：$detail"
            } else {
                "配对失败：$name"
            }
        }
        viewModelScope.launch(handler) {
            secureTransport.pair(device, identity, code)
            message.value = "已与 ${device.name} 配对"
        }
    }

    fun refreshPairCode() {
        serverHost.regeneratePairCode()
    }

    fun sendSelectedFiles() {
        val deviceId = selectedDeviceId.value ?: run {
            message.value = "请先选择设备"
            return
        }
        val device = discoveredDevices.value[deviceId] ?: run {
            message.value = "设备不可用"
            return
        }
        val files = selectedFiles.value
        if (files.isEmpty()) {
            message.value = "请至少选择一个文件"
            return
        }
        viewModelScope.launch {
            val trusted = store.get(deviceId)
            if (trusted?.outgoingToken.isNullOrBlank()) {
                message.value = "请先输入对方配对码以开启发送"
                return@launch
            }
            val reachable = ensureReachableForSend(device)
            if (!reachable) {
                alertMessage.value = "对方设备已下线，请等待其上线后再发送。"
                return@launch
            }
            val deviceWithFingerprint = device.copy(fingerprint = trusted.fingerprint)
            files.forEach { file ->
                val transfer = TransferItem(
                    fileName = file.name,
                    totalBytes = max(file.size, 1),
                    status = TransferStatus.Preparing,
                    direction = TransferDirection.Upload
                )
                upsertTransfer(transfer)
                startTransfer(deviceId, deviceWithFingerprint, file, transfer)
            }
            selectedFiles.value = emptyList()
        }
    }

    fun sendTextMessage() {
        val deviceId = selectedDeviceId.value ?: run {
            message.value = "请先选择设备"
            return
        }
        val device = discoveredDevices.value[deviceId] ?: run {
            message.value = "设备不可用"
            return
        }
        val text = textDraft.value.trim()
        if (text.isEmpty()) {
            message.value = "请输入文本内容"
            return
        }
        val handler = CoroutineExceptionHandler { _, throwable ->
            handleSendFailure(deviceId, throwable)
        }
        viewModelScope.launch(handler) {
            val trusted = store.get(deviceId)
            if (trusted?.outgoingToken.isNullOrBlank()) {
                message.value = "请先输入对方配对码以开启发送"
                return@launch
            }
            val reachable = ensureReachableForSend(device)
            if (!reachable) {
                alertMessage.value = "对方设备已下线，请等待其上线后再发送。"
                return@launch
            }
            val deviceWithFingerprint = device.copy(fingerprint = trusted.fingerprint)
            transferClient.sendMessage(deviceWithFingerprint, text)
            addMessage(
                MessageItem(
                    peerName = device.name,
                    content = text,
                    direction = MessageDirection.Outgoing
                )
            )
            textDraft.value = ""
            message.value = "文本已发送"
        }
    }

    fun clearMessage() {
        message.value = null
    }

    fun clearAlertMessage() {
        alertMessage.value = null
    }

    private fun isDeviceOnline(device: DeviceInfo): Boolean {
        if (device.lastSeen <= 0L) return false
        return System.currentTimeMillis() - device.lastSeen <= ONLINE_WINDOW_MS
    }

    private fun ensureReachableForSend(device: DeviceInfo): Boolean =
        isDeviceOnline(device)

    private fun startTransfer(deviceId: String, device: DeviceInfo, file: FileDescriptor, item: TransferItem) {
        val handler = CoroutineExceptionHandler { _, throwable ->
            updateTransfer(item.id) { current ->
                current.copy(status = TransferStatus.Failed)
            }
            handleSendFailure(deviceId, throwable)
        }
        viewModelScope.launch(handler) {
            updateTransfer(item.id) { it.copy(status = TransferStatus.Transferring) }
            var lastSent = 0L
            var lastTime = System.currentTimeMillis()
            transferClient.sendFile(device, file) { sent, total ->
                val now = System.currentTimeMillis()
                val elapsed = (now - lastTime).coerceAtLeast(1)
                val speed = ((sent - lastSent) * 1000L) / elapsed
                lastSent = sent
                lastTime = now
                updateTransfer(item.id) {
                    it.copy(
                        sentBytes = sent,
                        totalBytes = total,
                        speedBytesPerSec = speed,
                        status = TransferStatus.Transferring,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }
            updateTransfer(item.id) {
                it.copy(
                    status = TransferStatus.Completed,
                    sentBytes = it.totalBytes,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
    }

    private fun handleSendFailure(deviceId: String, throwable: Throwable) {
        Log.e("PulseSend", "Send failed", throwable)
        val detail = throwable.message?.take(80)?.trim()
        val reason = normalizeSendError(detail, throwable)
        message.value = if (!reason.isNullOrBlank()) {
            "发送失败：$reason"
        } else {
            "发送失败"
        }
    }

    private fun normalizeSendError(detail: String?, throwable: Throwable): String {
        if (!detail.isNullOrEmpty()) {
            val lower = detail.lowercase()
            if (lower.contains("hostname") && lower.contains("not verified")) {
                return "证书校验失败，请重新配对"
            }
            if (lower.contains("certificate") && (lower.contains("pin") || lower.contains("sha256/"))) {
                return "证书指纹不匹配，请重新配对"
            }
            if (detail.contains("401") || detail.contains("未授权") || detail.contains("未配对")) {
                return "对方未授权当前设备，请重新配对"
            }
            return detail
        }
        return throwable::class.java.simpleName
    }

    private fun updateTransfer(id: String, transform: (TransferItem) -> TransferItem) {
        transfers.value = transfers.value.map { transfer ->
            if (transfer.id == id) transform(transfer) else transfer
        }
    }

    private fun upsertTransfer(item: TransferItem) {
        val current = transfers.value.toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            current[index] = item
        } else {
            current.add(0, item)
        }
        transfers.value = current
    }

    fun removeTransfer(transferId: String) {
        transfers.value = transfers.value.filterNot { it.id == transferId }
    }

    private fun addMessage(item: MessageItem) {
        val updated = listOf(item) + messages.value
        messages.value = updated.take(50)
    }

    override fun onCleared() {
        super.onCleared()
        serverHost.stop()
    }
}

class MainViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(context.applicationContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}


