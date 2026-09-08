package com.dsh.mobile.ui.screens

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dsh.mobile.R
import androidx.core.content.ContextCompat
import com.dsh.mobile.data.*
import com.dsh.mobile.service.DshConnectionService
import com.dsh.mobile.ui.theme.DshBrand
import com.dsh.mobile.ui.theme.DshSuccess
import com.dsh.mobile.ui.theme.DshShape
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 设备记录标题：自定义名 > 机型 > 占位（列表不展示公网 IP） */
private fun deviceTitle(p: HostProfile): String =
    p.remark.ifBlank { p.deviceModel.ifBlank { "未命名设备" } }

/** M1：发送文件到 PC（系统选文件 → fs/write 上传到插件 uploads 目录） */
@Composable
private fun UploadToPcCard(profile: HostProfile, connection: DshConnection) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uploading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true
            status = "读取文件…"
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("无法读取所选文件")
                val name = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "upload.bin"
                status = "上传中… (${bytes.size / 1024} KB)"
                val rpc = HttpPairingRpc(connection, OkHttpClientFactory.build(profile).first)
                val res = rpc.uploadFile(name, bytes)
                status = if (res.ok) "已上传 ✓ ${res.path ?: ""}"
                else "上传失败：" + (res.error ?: "未知错误")
            } catch (e: Exception) {
                status = "上传失败：" + (e.message ?: e.javaClass.simpleName)
            } finally {
                uploading = false
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = DshShape.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("发送文件到 PC（M1）", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Button(onClick = { picker.launch("*/*") }, enabled = !uploading) {
                Text(if (uploading) "上传中…" else "选择文件")
            }
            status?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ConnectScreen(
    connection: DshConnection,
    onEditHost: (String?) -> Unit = {},
) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    val profiles by settingsStore.profiles.collectAsState(initial = emptyList())
    val activeId by settingsStore.activeProfileId.collectAsState(initial = null)
    val connState by connection.state.collectAsState()
    var pendingDelete by remember { mutableStateOf<HostProfile?>(null) }

    val sortedProfiles = profiles.sortedByDescending { it.lastUsedAt }
    val activeProfile = profiles.firstOrNull { it.id == activeId }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    fun onConnectedActions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, DshConnectionService::class.java))
        }
    }

    fun connectTo(profile: HostProfile) {
        scope.launch {
            settingsStore.setActiveProfile(profile.id)
            // 多设备记录共存：仅当前主机保留「启动自动连接」（记住上次使用），其余只取消标记、不删记录
            settingsStore.upsertProfile(profile.copy(autoConnect = true))
            settingsStore.profiles.first()
                .filter { it.id != profile.id && it.autoConnect }
                .forEach { settingsStore.upsertProfile(it.copy(autoConnect = false)) }
        }
        connection.connect(profile) { info ->
            scope.launch { settingsStore.markAttempt(info.profileId, info.errorCode, info.hostVersion) }
        }
        onConnectedActions()
    }

    // —— 自动连接 ——
    LaunchedEffect(Unit) {
        settingsStore.ensureMigrated()
        val auto = settingsStore.profiles.first().firstOrNull { it.autoConnect }
        if (auto != null) {
            scope.launch { settingsStore.setActiveProfile(auto.id) }
            connectTo(auto)
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // 错误横幅（spec §6：错误码 → 原因 → 建议；不可恢复错误标注已停止重连）
        val st = connState
        if (st is DshConnection.State.Error && st.code != null) {
            val code = st.code
            val stopped = code == ConnectionErrorCode.AUTH_FAILED ||
                code == ConnectionErrorCode.VERSION_MISMATCH
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        ErrorMessages.reason(code) + if (stopped) "（已停止自动重连）" else "",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        ErrorMessages.advice(code),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("DSH Remote", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "遥控你电脑上的 DeepSeek Harness 智能体",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { onEditHost(null) }) {
                        Icon(Icons.Default.Add, contentDescription = "添加主机", tint = DshBrand)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // 活跃主机卡片
            activeProfile?.let { p ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = DshShape.card,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val dotColor = when (connState) {
                                    is DshConnection.State.Connected -> DshSuccess
                                    is DshConnection.State.Error -> MaterialTheme.colorScheme.error
                                    is DshConnection.State.Connecting -> DshBrand
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Box(
                                    Modifier.size(10.dp)
                                        .background(dotColor, DshShape.pill),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    deviceTitle(p),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.weight(1f))
                                if (connState !is DshConnection.State.Connected &&
                                    connState !is DshConnection.State.Connecting
                                ) {
                                    TextButton(onClick = { connectTo(p) }) { Text("连接") }
                                }
                                TextButton(onClick = { onEditHost(p.id) }) { Text("编辑") }
                            }
                            Text(
                                p.deviceModel.ifBlank { "连接成功后自动记录设备机型" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            if (connState is DshConnection.State.Connected) {
                                val connected = connState as DshConnection.State.Connected
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "已连接" + (connected.hostVersion?.let { " · 主机版本 $it" } ?: " · 版本未知"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DshSuccess,
                                )
                            }
                        }
                    }
                }
            }

            // M1：连接后可在首页直接上传文件到 PC
            if (activeProfile != null && connState is DshConnection.State.Connected) {
                item {
                    UploadToPcCard(activeProfile!!, connection)
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "设备记录",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (sortedProfiles.isEmpty()) {
                item {
                    Text(
                        "还没有设备记录：点「添加主机」，填写服务器地址与访问口令即可连接",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(sortedProfiles, key = { it.id }) { p ->
                    val isActive = p.id == activeId
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = DshShape.card,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        onClick = {
                            // 活跃主机断开/失败态（如退出重进后自动连接未成）时，点卡片即可重连
                            if (!isActive || (
                                    connState !is DshConnection.State.Connected &&
                                        connState !is DshConnection.State.Connecting
                                    )
                            ) connectTo(p)
                        },
                    ) {
                        Row(
                            Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(deviceTitle(p), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                    if (isActive) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "使用中",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = DshBrand,
                                        )
                                    }
                                    if (p.channelToken.isNotBlank()) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "已设口令",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = DshSuccess,
                                        )
                                    }
                                }
                                Text(
                                    if (p.deviceModel.isNotBlank()) p.deviceModel
                                    else "连接一次后自动记录机型",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                p.lastErrorCode?.let { code ->
                                    runCatching { ConnectionErrorCode.valueOf(code) }.getOrNull()?.let { c ->
                                        Text(
                                            "上次错误：${ErrorMessages.reason(c)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { onEditHost(p.id) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Edit, "编辑设备", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { pendingDelete = p }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Delete, "删除记录", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onEditHost(null) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = DshShape.pill,
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加主机")
                }
                Spacer(Modifier.height(20.dp))
                val versionName = remember {
                    runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: "?"
                }
                Text(
                    "DSH Remote v$versionName · 非官方客户端 · 数据只存你的手机与你的服务器",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(16.dp))
            }
        }

        // 删除设备记录（二次确认）：删活跃主机先断开前台连接
        pendingDelete?.let { target ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("删除设备记录") },
                text = { Text("将删除「${deviceTitle(target)}」的连接记录与访问口令，不影响电脑端数据。") },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            if (activeId == target.id) connection.disconnect()
                            settingsStore.deleteProfile(target.id)
                            pendingDelete = null
                        }
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("取消") }
                },
            )
        }

    }
}
