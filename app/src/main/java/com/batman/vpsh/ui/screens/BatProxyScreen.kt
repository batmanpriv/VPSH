package com.batman.vpsh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.batman.vpsh.R
import com.batman.vpsh.core.AppListProvider
import com.batman.vpsh.core.InstalledAppInfo
import com.batman.vpsh.core.batproxy.BatWorkerSnapshot
import com.batman.vpsh.data.BatProxyConfig
import com.batman.vpsh.data.BatWorker
import com.batman.vpsh.data.SplitTunnelMode
import com.batman.vpsh.service.BatProxyUiState
import com.batman.vpsh.ui.components.SectionCard
import com.batman.vpsh.ui.components.StatChip
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.foundation.Image
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

@Composable
fun BatProxyScreen(
    state: BatProxyUiState,
    config: BatProxyConfig,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onAddWorker: (BatWorker) -> Unit,
    onRemoveWorker: (String) -> Unit,
    onSetDns: (String, Int) -> Unit,
    onSetSplitTunnel: (SplitTunnelMode, Set<String>) -> Unit
) {
    var showAddWorker by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    LaunchedEffect(state.running, state.error) {
        if (state.running || state.error != null) connecting = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            stringResource(R.string.batproxy_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        BatStatusHero(state, connecting)

        val onConnectClick: () -> Unit = { connecting = true; onConnect() }
        Button(
            onClick = if (state.running) onDisconnect else onConnectClick,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(if (state.running) R.string.batproxy_stop else R.string.batproxy_start), fontWeight = FontWeight.Bold)
        }

        if (state.running) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatChip(Icons.Filled.CheckCircle, stringResource(R.string.batproxy_stat_active), state.stats.active.toString())
                StatChip(Icons.Filled.Sync, stringResource(R.string.batproxy_stat_total), state.stats.total.toString(), accent = MaterialTheme.colorScheme.secondary)
                StatChip(Icons.Filled.Speed, stringResource(R.string.batproxy_stat_ok), state.stats.ok.toString(), accent = MaterialTheme.colorScheme.tertiary)
                StatChip(Icons.Filled.Delete, stringResource(R.string.batproxy_stat_fail), state.stats.fail.toString(), accent = MaterialTheme.colorScheme.error)
            }
        }

        state.error?.let {
            SectionCard {
                Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionCard {
            Text(
                stringResource(R.string.batproxy_limitations_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.batproxy_workers_title), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { showAddWorker = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.batproxy_add_worker))
            }
        }

        if (config.workers.isEmpty()) {
            Text(stringResource(R.string.batproxy_no_workers), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        } else {
            val snapshotByUrl = state.stats.workers.associateBy { it.url }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                config.workers.forEach { worker ->
                    WorkerRow(worker = worker, snapshot = snapshotByUrl[worker.url], onRemove = { onRemoveWorker(worker.url) })
                }
            }
        }

        SectionCard {
            Text(stringResource(R.string.batproxy_dns_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            var host by remember(config.dnsHost) { mutableStateOf(config.dnsHost) }
            var port by remember(config.dnsPort) { mutableStateOf(config.dnsPort.toString()) }
            TextField(
                value = host,
                onValueChange = { host = it; onSetDns(it, port.toIntOrNull() ?: config.dnsPort) },
                label = { Text(stringResource(R.string.batproxy_dns_host)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = port,
                onValueChange = { port = it; onSetDns(host, it.toIntOrNull() ?: config.dnsPort) },
                label = { Text(stringResource(R.string.batproxy_dns_port)) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        SplitTunnelSection(config = config, onSetSplitTunnel = onSetSplitTunnel)

        Spacer(Modifier.height(8.dp))
    }

    if (showAddWorker) {
        AddWorkerDialog(onDismiss = { showAddWorker = false }, onAdd = { w -> onAddWorker(w); showAddWorker = false })
    }
}

@Composable
private fun SplitTunnelSection(config: BatProxyConfig, onSetSplitTunnel: (SplitTunnelMode, Set<String>) -> Unit) {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }

    SectionCard {
        Text(stringResource(R.string.split_tunnel_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.split_tunnel_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        val options = listOf(
            SplitTunnelMode.OFF to stringResource(R.string.split_tunnel_off),
            SplitTunnelMode.EXCLUDE to stringResource(R.string.split_tunnel_exclude),
            SplitTunnelMode.INCLUDE_ONLY to stringResource(R.string.split_tunnel_include_only)
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { (mode, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RadioButton(
                        selected = config.splitTunnelMode == mode,
                        onClick = { onSetSplitTunnel(mode, config.splitTunnelApps) }
                    )
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (config.splitTunnelMode != SplitTunnelMode.OFF) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.split_tunnel_pick_apps, config.splitTunnelApps.size))
            }
        }
    }

    if (showPicker) {
        AppPickerDialog(
            context = context,
            initialSelection = config.splitTunnelApps,
            onDismiss = { showPicker = false },
            onConfirm = { picked -> onSetSplitTunnel(config.splitTunnelMode, picked); showPicker = false }
        )
    }
}

@Composable
private fun AppPickerDialog(
    context: android.content.Context,
    initialSelection: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val apps = remember { AppListProvider.listLaunchableApps(context) }
    var selected by remember { mutableStateOf(initialSelection) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.split_tunnel_pick_apps_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                apps.forEach { app -> AppPickerRow(app, app.packageName in selected) { checked ->
                    selected = if (checked) selected + app.packageName else selected - app.packageName
                } }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun AppPickerRow(app: InstalledAppInfo, checked: Boolean, onCheck: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheck(!checked) }
            .padding(vertical = 6.dp)
    ) {
        val bitmap = remember(app.packageName) { app.icon?.let { drawableToBitmap(it) } }
        if (bitmap != null) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(app.label, modifier = Modifier.weight(1f), maxLines = 1)
        Checkbox(checked = checked, onCheckedChange = onCheck)
    }
}

@Composable
private fun BatStatusHero(state: BatProxyUiState, connecting: Boolean) {
    val color = when {
        state.error != null -> MaterialTheme.colorScheme.error
        state.running -> MaterialTheme.colorScheme.primary
        connecting -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when {
        state.error != null -> stringResource(R.string.batproxy_status_disconnected)
        state.running -> stringResource(R.string.batproxy_status_connected)
        connecting -> stringResource(R.string.batproxy_status_connecting)
        else -> stringResource(R.string.batproxy_status_disconnected)
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(modifier = Modifier.size(16.dp).background(color, CircleShape))
            Column {
                Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (state.running && state.region != null) {
                    Text(
                        "${state.region.flagEmoji}  ${state.region.countryName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (state.running) {
                    Text(
                        stringResource(R.string.batproxy_region_detecting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkerRow(worker: BatWorker, snapshot: BatWorkerSnapshot?, onRemove: () -> Unit) {
    val statusColor = when (snapshot?.status) {
        "closed" -> MaterialTheme.colorScheme.primary
        "half_open" -> MaterialTheme.colorScheme.tertiary
        "open" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusLabel = when (snapshot?.status) {
        "closed" -> stringResource(R.string.batproxy_worker_status_closed)
        "half_open" -> stringResource(R.string.batproxy_worker_status_half_open)
        "open" -> stringResource(R.string.batproxy_worker_status_open)
        else -> "—"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(worker.url.substringAfter("//"), fontWeight = FontWeight.SemiBold, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = statusColor)
                if (snapshot != null) {
                    if (snapshot.status == "open" && snapshot.cooldownSeconds > 0) {
                        Text(
                            stringResource(R.string.batproxy_cooldown_label, snapshot.cooldownSeconds.toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    snapshot.rttMs?.let {
                        Text(
                            stringResource(R.string.batproxy_rtt_label, it.toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "${snapshot.ok}/${snapshot.ok + snapshot.fail}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.batproxy_remove_worker), tint = MaterialTheme.colorScheme.error)
        }
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap? {
    return try {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bmp
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun AddWorkerDialog(onDismiss: () -> Unit, onAdd: (BatWorker) -> Unit) {
    var url by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batproxy_add_worker)) },
        text = {
            Column {
                TextField(value = url, onValueChange = { url = it }, label = { Text(stringResource(R.string.batproxy_worker_url)) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                TextField(value = password, onValueChange = { password = it }, label = { Text(stringResource(R.string.batproxy_worker_password)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { if (url.isNotBlank()) onAdd(BatWorker(url.trim(), password)) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
