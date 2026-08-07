package com.batman.vpsh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.batman.vpsh.R
import com.batman.vpsh.data.UpstreamType
import com.batman.vpsh.data.VpshSettings
import com.batman.vpsh.ui.components.SectionCard
import com.batman.vpsh.util.LocaleHelper

@Composable
fun SettingsScreen(
    settings: VpshSettings,
    onChange: ((VpshSettings) -> VpshSettings) -> Unit,
    onLanguageChange: (String) -> Unit,
    upstreamProfiles: List<com.batman.vpsh.data.UpstreamProfile> = emptyList(),
    onSaveUpstreamProfile: (String) -> Unit = {},
    onApplyUpstreamProfile: (com.batman.vpsh.data.UpstreamProfile) -> Unit = {},
    onDeleteUpstreamProfile: (String) -> Unit = {},
    isRooted: Boolean = false,
    apActive: Boolean = false,
    onTestAccessPoint: (ssid: String, password: String, onResult: (Boolean, String) -> Unit) -> Unit = { _, _, _ -> },
    onStopAccessPoint: () -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            var currentLang by remember { mutableStateOf(LocaleHelper.getLanguage(context)) }
            SectionCard {
                Text(stringResource(R.string.language_section), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LangChip(stringResource(R.string.language_system), currentLang == LocaleHelper.SYSTEM, Modifier.weight(1f)) {
                        currentLang = LocaleHelper.SYSTEM; onLanguageChange(LocaleHelper.SYSTEM)
                    }
                    LangChip(stringResource(R.string.language_english), currentLang == LocaleHelper.ENGLISH, Modifier.weight(1f)) {
                        currentLang = LocaleHelper.ENGLISH; onLanguageChange(LocaleHelper.ENGLISH)
                    }
                    LangChip(stringResource(R.string.language_persian), currentLang == LocaleHelper.PERSIAN, Modifier.weight(1f)) {
                        currentLang = LocaleHelper.PERSIAN; onLanguageChange(LocaleHelper.PERSIAN)
                    }
                }
            }
        }

        item {
            SectionCard {
                Text(stringResource(R.string.ports_section), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                NumberField(stringResource(R.string.http_proxy_port), settings.proxyPort.toString()) { v ->
                    onChange { it.copy(proxyPort = v.toIntOrNull() ?: it.proxyPort) }
                }
                Spacer(Modifier.height(10.dp))
                SwitchRow(stringResource(R.string.enable_socks5), settings.enableSocks5) { v -> onChange { it.copy(enableSocks5 = v) } }
                if (settings.enableSocks5) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.socks5_udp_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    NumberField(stringResource(R.string.socks5_port), settings.socks5Port.toString()) { v ->
                        onChange { it.copy(socks5Port = v.toIntOrNull() ?: it.socks5Port) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                SwitchRow(stringResource(R.string.enable_pac), settings.enablePac) { v -> onChange { it.copy(enablePac = v) } }
                if (settings.enablePac) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.enable_pac_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    NumberField(stringResource(R.string.pac_port), settings.pacPort.toString()) { v ->
                        onChange { it.copy(pacPort = v.toIntOrNull() ?: it.pacPort) }
                    }
                }
            }
        }

        item {
            SectionCard {
                Text(stringResource(R.string.ss_section), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.ss_section_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                SwitchRow(stringResource(R.string.enable_shadowsocks), settings.enableShadowsocks) { v ->
                    onChange { it.copy(enableShadowsocks = v) }
                }
                if (settings.enableShadowsocks) {
                    Spacer(Modifier.height(10.dp))
                    NumberField(stringResource(R.string.ss_port), settings.shadowsocksPort.toString()) { v ->
                        onChange { it.copy(shadowsocksPort = v.toIntOrNull() ?: it.shadowsocksPort) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextField(
                            value = settings.shadowsocksPassword,
                            onValueChange = { v -> onChange { it.copy(shadowsocksPassword = v) } },
                            label = { Text(stringResource(R.string.ss_password_label)) },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onChange { it.copy(shadowsocksPassword = randomPassword()) } }) {
                            Text(stringResource(R.string.ss_generate_password))
                        }
                    }
                    if (settings.shadowsocksPassword.isBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.ss_password_required),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        item {
            SectionCard {
                Text(stringResource(R.string.auth_section), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                SwitchRow(stringResource(R.string.require_auth), settings.enableAuth) { v -> onChange { it.copy(enableAuth = v) } }
                if (settings.enableAuth) {
                    Spacer(Modifier.height(10.dp))
                    TextField(
                        value = settings.authUser,
                        onValueChange = { v -> onChange { it.copy(authUser = v) } },
                        label = { Text(stringResource(R.string.username)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    TextField(
                        value = settings.authPass,
                        onValueChange = { v -> onChange { it.copy(authPass = v) } },
                        label = { Text(stringResource(R.string.password)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            SectionCard {
                Text(stringResource(R.string.chain_section), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.chain_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    UpstreamChip(stringResource(R.string.chain_off), settings.upstreamType == UpstreamType.NONE, Modifier.weight(1f)) {
                        onChange { it.copy(upstreamType = UpstreamType.NONE) }
                    }
                    UpstreamChip(stringResource(R.string.chain_socks5), settings.upstreamType == UpstreamType.SOCKS5, Modifier.weight(1f)) {
                        onChange { it.copy(upstreamType = UpstreamType.SOCKS5) }
                    }
                    UpstreamChip(stringResource(R.string.chain_http), settings.upstreamType == UpstreamType.HTTP, Modifier.weight(1f)) {
                        onChange { it.copy(upstreamType = UpstreamType.HTTP) }
                    }
                }
                if (settings.upstreamType != UpstreamType.NONE) {
                    Spacer(Modifier.height(10.dp))
                    TextField(
                        value = settings.upstreamHost,
                        onValueChange = { v -> onChange { it.copy(upstreamHost = v) } },
                        label = { Text(stringResource(R.string.upstream_address)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    NumberField(stringResource(R.string.upstream_port), settings.upstreamPort.toString()) { v ->
                        onChange { it.copy(upstreamPort = v.toIntOrNull() ?: it.upstreamPort) }
                    }
                    Spacer(Modifier.height(10.dp))
                    TextField(
                        value = settings.upstreamUser,
                        onValueChange = { v -> onChange { it.copy(upstreamUser = v) } },
                        label = { Text(stringResource(R.string.upstream_username_optional)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    TextField(
                        value = settings.upstreamPass,
                        onValueChange = { v -> onChange { it.copy(upstreamPass = v) } },
                        label = { Text(stringResource(R.string.upstream_password_optional)) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    UpstreamProfilesSection(
                        profiles = upstreamProfiles,
                        onSave = onSaveUpstreamProfile,
                        onApply = onApplyUpstreamProfile,
                        onDelete = onDeleteUpstreamProfile
                    )
                }
            }
        }

        item {
            SectionCard {
                Text(stringResource(R.string.health_section), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                SwitchRow(stringResource(R.string.auto_restart), settings.autoRestart) { v -> onChange { it.copy(autoRestart = v) } }
                Spacer(Modifier.height(10.dp))
                NumberField(stringResource(R.string.health_interval_seconds), settings.healthIntervalSec.toString()) { v ->
                    onChange { it.copy(healthIntervalSec = v.toIntOrNull() ?: it.healthIntervalSec) }
                }
                Spacer(Modifier.height(10.dp))
                SwitchRow(stringResource(R.string.kill_switch_desc), settings.killSwitchProxyMode) { v -> onChange { it.copy(killSwitchProxyMode = v) } }
                Spacer(Modifier.height(10.dp))
                SwitchRow(stringResource(R.string.auto_start_boot), settings.autoStartOnBoot) { v -> onChange { it.copy(autoStartOnBoot = v) } }
                Text(
                    stringResource(R.string.auto_start_boot_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                SwitchRow(stringResource(R.string.game_mode), settings.gameModeEnabled) { v -> onChange { it.copy(gameModeEnabled = v) } }
                Text(
                    stringResource(R.string.game_mode_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SectionCard {
                Text(stringResource(R.string.full_mode_section), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                SwitchRow(stringResource(R.string.disconnect_clients_on_vpn_drop), settings.forceVpnOnly) { v -> onChange { it.copy(forceVpnOnly = v) } }
                Spacer(Modifier.height(10.dp))
                SwitchRow(stringResource(R.string.block_ipv6_leak), settings.blockIpv6Leak) { v -> onChange { it.copy(blockIpv6Leak = v) } }
                Spacer(Modifier.height(10.dp))
                SwitchRow(stringResource(R.string.disable_ipa_offload), settings.disableIpaOffload) { v -> onChange { it.copy(disableIpaOffload = v) } }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.disable_ipa_offload_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SectionCard {
                Text(stringResource(R.string.ap_section), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                val apSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                Text(
                    stringResource(
                        when {
                            !apSupported -> R.string.ap_section_hint_unsupported_os
                            isRooted -> R.string.ap_section_hint
                            else -> R.string.ap_section_hint_no_root
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (!apSupported) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                SwitchRow(stringResource(R.string.ap_auto_create), settings.apAutoCreate, enabled = apSupported) { v ->
                    onChange { it.copy(apAutoCreate = v) }
                }
                if (settings.apAutoCreate && apSupported) {
                    Spacer(Modifier.height(10.dp))
                    TextField(
                        value = settings.apSsid,
                        onValueChange = { v -> onChange { it.copy(apSsid = v) } },
                        label = { Text(stringResource(R.string.ap_ssid_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    TextField(
                        value = settings.apPassword,
                        onValueChange = { v -> onChange { it.copy(apPassword = v) } },
                        label = { Text(stringResource(R.string.ap_password_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.ap_password_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isRooted && apSupported) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(14.dp))
                    ApTestSection(
                        active = apActive,
                        ssid = settings.apSsid,
                        password = settings.apPassword,
                        onTest = onTestAccessPoint,
                        onStop = onStopAccessPoint
                    )
                }
            }
        }

        item {
            SectionCard {
                Text(stringResource(R.string.iface_override_section), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = settings.hotspotIfaceOverride,
                    onValueChange = { v -> onChange { it.copy(hotspotIfaceOverride = v) } },
                    label = { Text(stringResource(R.string.hotspot_iface_override)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = settings.vpnIfaceOverride,
                    onValueChange = { v -> onChange { it.copy(vpnIfaceOverride = v) } },
                    label = { Text(stringResource(R.string.vpn_iface_override)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

private val PASSWORD_CHARS = ('a'..'z') + ('A'..'Z') + ('0'..'9')

private fun randomPassword(length: Int = 16): String {
    val random = java.security.SecureRandom()
    return (1..length).map { PASSWORD_CHARS[random.nextInt(PASSWORD_CHARS.size)] }.joinToString("")
}

@Composable
private fun NumberField(label: String, value: String, onValue: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onCheck: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Switch(checked = checked, onCheckedChange = onCheck, enabled = enabled)
    }
}

@Composable
private fun UpstreamChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier
    )
}

@Composable
private fun LangChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier
    )
}

@Composable
private fun UpstreamProfilesSection(
    profiles: List<com.batman.vpsh.data.UpstreamProfile>,
    onSave: (String) -> Unit,
    onApply: (com.batman.vpsh.data.UpstreamProfile) -> Unit,
    onDelete: (String) -> Unit
) {
    var newName by remember { mutableStateOf("") }

    Text(stringResource(R.string.upstream_profiles_title), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.upstream_profiles_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(10.dp))

    if (profiles.isEmpty()) {
        Text(
            stringResource(R.string.upstream_profiles_none),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        profiles.forEach { pr ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(pr.name, fontWeight = FontWeight.Bold)
                    Text(
                        "${pr.type} · ${pr.host}:${pr.port}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { onApply(pr) }) { Text(stringResource(R.string.upstream_profile_apply)) }
                TextButton(onClick = { onDelete(pr.name) }) { Text(stringResource(R.string.upstream_profile_delete)) }
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TextField(
            value = newName,
            onValueChange = { newName = it },
            label = { Text(stringResource(R.string.upstream_profile_name_hint)) },
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            if (newName.isNotBlank()) { onSave(newName.trim()); newName = "" }
        }) { Text(stringResource(R.string.upstream_profile_save)) }
    }
}

@Composable
private fun ApTestSection(
    active: Boolean,
    ssid: String,
    password: String,
    onTest: (ssid: String, password: String, onResult: (Boolean, String) -> Unit) -> Unit,
    onStop: () -> Unit
) {
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Text(stringResource(R.string.ap_test_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.ap_test_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(10.dp))

    if (active) {
        Text(
            stringResource(R.string.ap_test_active, ssid.ifBlank { "VPSH" }),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { result = null; onStop() }) {
            Text(stringResource(R.string.ap_test_stop_button))
        }
    } else {
        Button(
            onClick = {
                testing = true
                result = null
                onTest(ssid, password) { success, message ->
                    testing = false
                    result = success to message
                }
            },
            enabled = !testing
        ) {
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ap_test_creating))
            } else {
                Text(stringResource(R.string.ap_test_button))
            }
        }
    }

    result?.let { (success, message) ->
        Spacer(Modifier.height(10.dp))
        Text(
            if (success) stringResource(R.string.ap_test_success)
            else stringResource(R.string.ap_test_failed, message.ifBlank { stringResource(R.string.ap_test_failed_generic) }),
            style = MaterialTheme.typography.bodyMedium,
            color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}
