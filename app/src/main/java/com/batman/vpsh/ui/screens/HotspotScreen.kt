package com.batman.vpsh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.batman.vpsh.R
import com.batman.vpsh.core.ClientInfo
import com.batman.vpsh.core.HotspotType
import com.batman.vpsh.data.HotspotBanEntry
import com.batman.vpsh.ui.components.SectionCard

@Composable
fun HotspotScreen(
    isRooted: Boolean?,
    hotspotIface: String?,
    hotspotIp: String,
    hotspotType: HotspotType,
    devices: List<ClientInfo>,
    bannedEntries: Map<String, HotspotBanEntry>,
    busy: Boolean,
    onStartPolling: () -> Unit,
    onStopPolling: () -> Unit,
    onBan: (mac: String, ip: String) -> Unit,
    onUnban: (mac: String) -> Unit
) {
    DisposableEffect(Unit) {
        onStartPolling()
        onDispose { onStopPolling() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            stringResource(R.string.hotspot_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Filled.WifiTethering, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(
                        if (hotspotIface != null) stringResource(R.string.hotspot_active_label) else stringResource(R.string.hotspot_inactive_label),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (hotspotIface != null) {
                        val typeLabel = when (hotspotType) {
                            HotspotType.WIFI -> stringResource(R.string.tether_link_wifi)
                            HotspotType.USB -> stringResource(R.string.tether_link_usb)
                            HotspotType.BLUETOOTH -> stringResource(R.string.tether_link_bluetooth)
                            HotspotType.UNKNOWN -> stringResource(R.string.tether_link_unknown)
                        }
                        Text(
                            stringResource(R.string.iface_summary, hotspotIface, hotspotIp) + "  ·  " + typeLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (isRooted == false) {
            SectionCard {
                Text(
                    stringResource(R.string.hotspot_root_required),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Text(stringResource(R.string.hotspot_devices_title), style = MaterialTheme.typography.titleMedium)
        if (devices.isEmpty()) {
            Text(
                stringResource(if (hotspotIface == null) R.string.hotspot_no_hotspot_detected else R.string.hotspot_no_devices),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                devices.forEach { device ->
                    val banned = bannedEntries.containsKey(device.mac.lowercase())
                    HotspotDeviceRow(
                        device = device,
                        banned = banned,
                        busy = busy,
                        canBan = isRooted == true,
                        onBan = { onBan(device.mac, device.ip) },
                        onUnban = { onUnban(device.mac) }
                    )
                }
            }
        }

        Text(stringResource(R.string.hotspot_banned_title), style = MaterialTheme.typography.titleMedium)
        if (bannedEntries.isEmpty()) {
            Text(
                stringResource(R.string.hotspot_no_banned),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bannedEntries.values.forEach { entry ->
                    HotspotBannedRow(entry = entry, busy = busy, onUnban = { onUnban(entry.mac) })
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HotspotDeviceRow(
    device: ClientInfo,
    banned: Boolean,
    busy: Boolean,
    canBan: Boolean,
    onBan: () -> Unit,
    onUnban: () -> Unit
) {
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
                device.ip,
                fontWeight = FontWeight.SemiBold,
                color = if (banned) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(device.mac, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (banned) {
            TextButton(onClick = onUnban, enabled = !busy) {
                Text(stringResource(R.string.hotspot_unban_action))
            }
        } else {
            IconButton(onClick = onBan, enabled = canBan && !busy) {
                Icon(
                    Icons.Filled.Block,
                    contentDescription = stringResource(R.string.hotspot_ban_action),
                    tint = if (canBan) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HotspotBannedRow(entry: HotspotBanEntry, busy: Boolean, onUnban: () -> Unit) {
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
                if (entry.nickname.isNotBlank()) "${entry.nickname} · ${entry.mac}" else entry.mac,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )
            if (entry.lastIp.isNotBlank()) {
                Text(entry.lastIp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onUnban, enabled = !busy) {
            Text(stringResource(R.string.hotspot_unban_action))
        }
    }
}
