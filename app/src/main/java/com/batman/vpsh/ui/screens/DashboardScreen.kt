package com.batman.vpsh.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.batman.vpsh.R
import com.batman.vpsh.core.ClientInfo
import com.batman.vpsh.core.HotspotType
import com.batman.vpsh.core.UsbTetherUtils
import com.batman.vpsh.data.ClientEntry
import com.batman.vpsh.data.RunState
import com.batman.vpsh.data.VpshMode
import com.batman.vpsh.data.VpshSettings
import com.batman.vpsh.data.VpshUiState
import androidx.compose.ui.platform.LocalContext
import com.batman.vpsh.ui.components.QrCodeImage
import com.batman.vpsh.ui.components.SectionCard
import com.batman.vpsh.ui.components.StatChip

@Composable
fun DashboardScreen(
    state: VpshUiState,
    settings: VpshSettings,
    registryEntries: Map<String, ClientEntry>,
    onModeChange: (VpshMode) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleBlock: (String) -> Unit,
    onSetNickname: (String, String) -> Unit,
    onSetLimit: (String, Int) -> Unit,
    onEnableUsbTether: ((Boolean) -> Unit) -> Unit
) {
    var showQr by remember { mutableStateOf(false) }
    var editingClient by remember { mutableStateOf<String?>(null) }
    var limitingClient by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val rootRequiredMsg = stringResource(R.string.err_root_required_toast)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            stringResource(R.string.dashboard_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        StatusHero(state)

        ModeSelector(
            selected = settings.mode,
            
            enabled = !isActiveState(state.runState),
            rooted = state.isRooted,
            onSelect = onModeChange,
            onFullModeBlocked = {
                android.widget.Toast.makeText(context, rootRequiredMsg, android.widget.Toast.LENGTH_SHORT).show()
            }
        )

        TetherLinkCard(
            state = state,
            rooted = state.isRooted,
            onEnableUsbTether = onEnableUsbTether,
            onOpenTetherSettings = { UsbTetherUtils.openTetherSettings(context) }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            val running = isActiveState(state.runState)
            Button(
                onClick = if (running) onStop else onStart,
                modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    stringResource(if (running) R.string.btn_stop else R.string.btn_start),
                    fontWeight = FontWeight.Bold
                )
            }
            if (running && state.mode == VpshMode.PROXY) {
                OutlinedButton(
                    onClick = { showQr = true },
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Filled.QrCode, contentDescription = null)
                }
            }
        }

        if (isActiveState(state.runState)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatChip(Icons.Filled.Devices, stringResource(R.string.stat_clients), state.clients.size.toString())
                StatChip(Icons.Filled.Speed, stringResource(R.string.stat_traffic), formatBytes(state.totalBytes), accent = MaterialTheme.colorScheme.secondary)
                if (state.mode == VpshMode.PROXY) {
                    StatChip(Icons.Filled.Router, stringResource(R.string.stat_port), state.proxyPort.toString(), accent = MaterialTheme.colorScheme.tertiary)
                }
            }
        }

        if (state.statusMessage.isNotBlank()) {
            SectionCard {
                Text(state.statusMessage, style = MaterialTheme.typography.bodyMedium)
            }
        }

        state.errorMessage?.let {
            SectionCard {
                Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text(stringResource(R.string.connected_devices), style = MaterialTheme.typography.titleMedium)
        if (state.clients.isEmpty()) {
            Text(stringResource(R.string.no_devices_yet), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        } else {
            
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.clients.forEach { client ->
                    val entry = registryEntries[client.ip]
                    ClientRow(
                        client = client,
                        entry = entry,
                        onToggleBlock = { onToggleBlock(client.ip) },
                        onEditNickname = { editingClient = client.ip },
                        onEditLimit = { limitingClient = client.ip }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    if (showQr) {
        QrDialog(state = state, settings = settings, onDismiss = { showQr = false })
    }

    editingClient?.let { ip ->
        NicknameDialog(
            ip = ip,
            initial = registryEntries[ip]?.nickname.orEmpty(),
            onDismiss = { editingClient = null },
            onSave = { name -> onSetNickname(ip, name); editingClient = null }
        )
    }

    limitingClient?.let { ip ->
        LimitDialog(
            ip = ip,
            initialKbps = registryEntries[ip]?.limitKbps ?: 0,
            onDismiss = { limitingClient = null },
            onSave = { kbps -> onSetLimit(ip, kbps); limitingClient = null }
        )
    }
}

private fun isActiveState(state: RunState) =
    state == RunState.RUNNING || state == RunState.STARTING || state == RunState.PAUSED

@Composable
private fun StatusHero(state: VpshUiState) {
    val running = state.runState == RunState.RUNNING
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "pulseAlpha"
    )
    val color = when (state.runState) {
        RunState.RUNNING -> MaterialTheme.colorScheme.primary
        RunState.STARTING -> MaterialTheme.colorScheme.secondary
        RunState.PAUSED -> MaterialTheme.colorScheme.tertiary
        RunState.ERROR -> MaterialTheme.colorScheme.error
        RunState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (state.runState) {
        RunState.RUNNING -> stringResource(R.string.status_running)
        RunState.STARTING -> stringResource(R.string.status_starting)
        RunState.PAUSED -> stringResource(R.string.status_paused_killswitch)
        RunState.ERROR -> stringResource(R.string.status_error)
        RunState.STOPPED -> stringResource(R.string.status_stopped)
    }

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .alpha(if (running) pulse else 1f)
                    .background(color, CircleShape)
            )
            Column {
                Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                val modeLabel = stringResource(if (state.mode == VpshMode.FULL) R.string.mode_label_full else R.string.mode_label_proxy)
                Text(modeLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.hotspotIface.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            val summary = if (state.mode == VpshMode.FULL && state.vpnIface.isNotEmpty()) {
                stringResource(R.string.iface_summary_with_vpn, state.hotspotIface, state.hotspotIp, state.vpnIface)
            } else {
                stringResource(R.string.iface_summary, state.hotspotIface, state.hotspotIp)
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TetherLinkCard(
    state: VpshUiState,
    rooted: Boolean,
    onEnableUsbTether: ((Boolean) -> Unit) -> Unit,
    onOpenTetherSettings: () -> Unit
) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var cableConnected by remember { mutableStateOf(UsbTetherUtils.isUsbCableConnected(context)) }
    val usbFailedMsg = stringResource(R.string.usb_tether_enable_failed_toast)

    LaunchedEffect(Unit) {
        while (true) {
            cableConnected = UsbTetherUtils.isUsbCableConnected(context)
            kotlinx.coroutines.delay(3000)
        }
    }

    val detected = isActiveState(state.runState) && state.hotspotIface.isNotEmpty()

    SectionCard {
        Text(stringResource(R.string.tether_link_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))

        if (detected) {
            val (icon, label) = when (state.hotspotType) {
                HotspotType.WIFI -> Icons.Filled.Wifi to stringResource(R.string.tether_link_wifi)
                HotspotType.USB -> Icons.Filled.Usb to stringResource(R.string.tether_link_usb)
                HotspotType.BLUETOOTH -> Icons.Filled.Bluetooth to stringResource(R.string.tether_link_bluetooth)
                HotspotType.UNKNOWN -> Icons.Filled.HelpOutline to stringResource(R.string.tether_link_unknown)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Text(
                stringResource(R.string.tether_link_none_detected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onOpenTetherSettings,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.tether_wifi_settings_btn))
                }
                Button(
                    onClick = {
                        if (rooted) {
                            busy = true
                            onEnableUsbTether { ok ->
                                busy = false
                                if (!ok) {
                                    android.widget.Toast.makeText(context, usbFailedMsg, android.widget.Toast.LENGTH_SHORT).show()
                                    onOpenTetherSettings()
                                }
                            }
                        } else {
                            onOpenTetherSettings()
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Usb, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (rooted) R.string.tether_usb_enable_btn else R.string.tether_usb_settings_btn))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (cableConnected) R.string.tether_usb_cable_connected else R.string.tether_usb_cable_missing),
                style = MaterialTheme.typography.labelSmall,
                color = if (cableConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ModeSelector(
    selected: VpshMode, enabled: Boolean, rooted: Boolean,
    onSelect: (VpshMode) -> Unit, onFullModeBlocked: () -> Unit
) {
    SectionCard {
        Text(stringResource(R.string.share_mode_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ModeOption(
                title = stringResource(R.string.mode_proxy),
                subtitle = stringResource(R.string.mode_proxy_sub),
                icon = Icons.Filled.Router,
                selected = selected == VpshMode.PROXY,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            ) { onSelect(VpshMode.PROXY) }
            ModeOption(
                title = stringResource(R.string.mode_full),
                subtitle = stringResource(if (rooted) R.string.mode_full_sub_rooted else R.string.mode_full_sub_needed),
                icon = Icons.Filled.Shield,
                
                selected = selected == VpshMode.FULL,
                enabled = enabled,
                dimmed = !rooted,
                modifier = Modifier.weight(1f)
            ) {
                if (rooted) onSelect(VpshMode.FULL) else onFullModeBlocked()
            }
        }
    }
}

@Composable
private fun ModeOption(
    title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean, enabled: Boolean, dimmed: Boolean = !enabled, modifier: Modifier = Modifier, onClick: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = modifier
            .background(bg, RoundedCornerShape(16.dp))
            .then(if (dimmed) Modifier.alpha(0.5f) else Modifier)
            .padding(14.dp)
            .then(Modifier.clickableIfEnabled(enabled, onClick)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ClientRow(
    client: ClientInfo,
    entry: ClientEntry?,
    onToggleBlock: () -> Unit,
    onEditNickname: () -> Unit,
    onEditLimit: () -> Unit
) {
    val blocked = entry?.blocked == true
    val limitKbps = entry?.limitKbps ?: 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (!entry?.nickname.isNullOrBlank()) "${entry?.nickname} · ${client.ip}" else client.ip,
                fontWeight = FontWeight.SemiBold,
                color = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            if (client.mac.isNotEmpty()) {
                Text(client.mac, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (limitKbps > 0) {
                Text(
                    stringResource(R.string.limit_active_label, limitKbps),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            val renameLabel = stringResource(R.string.rename_device_action)
            val blockLabel = stringResource(R.string.block_device_action)
            val unblockLabel = stringResource(R.string.unblock_device_action)
            val limitLabel = stringResource(R.string.limit_device_action)
            IconButton(onClick = onEditNickname) {
                Icon(Icons.Filled.Edit, contentDescription = renameLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEditLimit) {
                Icon(
                    Icons.Filled.Timer,
                    contentDescription = limitLabel,
                    tint = if (limitKbps > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onToggleBlock) {
                Icon(
                    if (blocked) Icons.Filled.Block else Icons.Filled.CheckCircle,
                    contentDescription = if (blocked) unblockLabel else blockLabel,
                    tint = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun QrDialog(state: VpshUiState, settings: VpshSettings, onDismiss: () -> Unit) {
    val hasAuth = settings.enableAuth && settings.authUser.isNotBlank()
    val payload = buildString {
        append("PROXY http://")
        if (hasAuth) append("${settings.authUser}:${settings.authPass}@")
        append("${state.hotspotIp}:${state.proxyPort}")
    }
    val userSuffix = if (hasAuth) "  " + stringResource(R.string.quick_connect_user, settings.authUser) else ""
    val humanReadable = "${state.hotspotIp}:${state.proxyPort}$userSuffix"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_connect_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                QrCodeImage(content = payload, modifier = Modifier.size(220.dp))
                Spacer(Modifier.height(12.dp))
                Text(humanReadable, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.quick_connect_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}

@Composable
private fun NicknameDialog(ip: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_dialog_title, ip)) },
        text = {
            TextField(value = text, onValueChange = { text = it }, label = { Text(stringResource(R.string.rename_dialog_label)) }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun LimitDialog(ip: String, initialKbps: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(if (initialKbps > 0) initialKbps.toString() else "") }
    val presets = listOf(0, 256, 512, 1024, 2048, 5120)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.limit_dialog_title, ip)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = text,
                    onValueChange = { new -> if (new.all { it.isDigit() }) text = new },
                    label = { Text(stringResource(R.string.limit_dialog_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.limit_dialog_presets_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    presets.forEach { kbps ->
                        val label = if (kbps == 0) stringResource(R.string.limit_preset_unlimited) else "$kbps"
                        AssistChip(onClick = { text = if (kbps == 0) "" else kbps.toString() }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.toIntOrNull() ?: 0) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

private fun Modifier.clickableIfEnabled(enabled: Boolean, onClick: () -> Unit): Modifier =
    if (enabled) this.clickable { onClick() } else this
