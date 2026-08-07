package com.batman.vpsh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.batman.vpsh.R
import com.batman.vpsh.core.IfaceDetail
import com.batman.vpsh.core.IptablesChain
import com.batman.vpsh.core.NetworkAdvancedManager
import com.batman.vpsh.ui.components.SectionCard

private val PROTOCOLS = listOf("all", "tcp", "udp", "icmp")
private val ACTIONS = listOf("ACCEPT", "DROP", "REJECT", "LOG")
private val CHAINS = listOf("INPUT", "OUTPUT", "FORWARD", "PREROUTING", "POSTROUTING")

@Composable
fun NetworkScreen(
    isRooted: Boolean?,
    interfaces: List<IfaceDetail>,
    gateway: String?,
    dns: List<String>,
    table: String,
    chains: List<IptablesChain>,
    ipRules: List<String>,
    ipRoutes: List<String>,
    busy: Boolean,
    message: String?,
    onRefresh: () -> Unit,
    onSetTable: (String) -> Unit,
    onAppendRule: (chain: String, args: String) -> Unit,
    onRunRawIptables: (String) -> Unit,
    onDeleteRule: (chain: String, lineNumber: Int) -> Unit,
    onRunRawIp: (String) -> Unit,
    onDismissMessage: () -> Unit
) {
    DisposableEffect(Unit) {
        onRefresh()
        onDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.network_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh, enabled = !busy) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.network_refresh))
            }
        }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Filled.Router, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(
                        stringResource(if (isRooted == true) R.string.network_root_yes else R.string.network_root_no),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.network_root_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (message != null) {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(message, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    TextButton(onClick = onDismissMessage) { Text(stringResource(R.string.network_dismiss)) }
                }
            }
        }

        InterfacesSection(interfaces)
        GatewayDnsSection(gateway, dns)
        FirewallSection(
            isRooted = isRooted == true,
            table = table,
            chains = chains,
            busy = busy,
            onSetTable = onSetTable,
            onAppendRule = onAppendRule,
            onRunRaw = onRunRawIptables,
            onDeleteRule = onDeleteRule
        )
        RoutingSection(
            isRooted = isRooted == true,
            ipRules = ipRules,
            ipRoutes = ipRoutes,
            busy = busy,
            onRunRaw = onRunRawIp
        )

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun InterfacesSection(interfaces: List<IfaceDetail>) {
    Text(stringResource(R.string.network_section_interfaces), style = MaterialTheme.typography.titleMedium)
    if (interfaces.isEmpty()) {
        Text(
            stringResource(R.string.network_no_interfaces),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        interfaces.forEach { iface ->
            SectionCard {
                Text(iface.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                val details = buildList {
                    add(stringResource(R.string.network_iface_ip, iface.ip ?: "—", iface.prefix?.toString() ?: "?"))
                    iface.state?.let { add(stringResource(R.string.network_iface_state, it)) }
                    iface.mtu?.let { add(stringResource(R.string.network_iface_mtu, it.toString())) }
                    iface.mac?.let { add(stringResource(R.string.network_iface_mac, it)) }
                    if (iface.rxBytes != null && iface.txBytes != null) {
                        add(stringResource(R.string.network_iface_traffic, formatBytes(iface.rxBytes), formatBytes(iface.txBytes)))
                    }
                }
                details.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return "%.1f %s".format(value, units[unit])
}

@Composable
private fun GatewayDnsSection(gateway: String?, dns: List<String>) {
    Text(stringResource(R.string.network_section_gateway_dns), style = MaterialTheme.typography.titleMedium)
    SectionCard {
        Text(
            stringResource(R.string.network_gateway_label, gateway ?: "—"),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.network_dns_label, if (dns.isEmpty()) "—" else dns.joinToString(", ")),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FirewallSection(
    isRooted: Boolean,
    table: String,
    chains: List<IptablesChain>,
    busy: Boolean,
    onSetTable: (String) -> Unit,
    onAppendRule: (String, String) -> Unit,
    onRunRaw: (String) -> Unit,
    onDeleteRule: (String, Int) -> Unit
) {
    Text(stringResource(R.string.network_section_firewall), style = MaterialTheme.typography.titleMedium)

    if (!isRooted) {
        SectionCard {
            Text(
                stringResource(R.string.network_firewall_root_required),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        return
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NetworkAdvancedManager.IPTABLES_TABLES.forEach { t ->
            FilterChip(selected = table == t, onClick = { onSetTable(t) }, label = { Text(t) })
        }
    }

    if (chains.isEmpty()) {
        Text(
            stringResource(R.string.network_no_rules),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chains.forEach { chain ->
                SectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(chain.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        if (chain.policy != null) {
                            Text(
                                stringResource(R.string.network_chain_policy, chain.policy),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (chain.rules.isEmpty()) {
                        Text(
                            stringResource(R.string.network_chain_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        chain.rules.forEach { rule ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${rule.lineNumber}. ${rule.raw}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onDeleteRule(chain.name, rule.lineNumber) }, enabled = !busy) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.network_delete_rule), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    AddRuleForm(busy = busy, onAppendRule = onAppendRule)
    RawCommandBox(
        title = stringResource(R.string.network_raw_iptables_title),
        prefix = "iptables",
        placeholder = stringResource(R.string.network_raw_iptables_placeholder),
        busy = busy,
        onRun = onRunRaw
    )
}

@Composable
private fun AddRuleForm(busy: Boolean, onAppendRule: (String, String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var chain by remember { mutableStateOf(CHAINS.first()) }
    var action by remember { mutableStateOf(ACTIONS.first()) }
    var protocol by remember { mutableStateOf(PROTOCOLS.first()) }
    var source by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var inIface by remember { mutableStateOf("") }
    var outIface by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.network_add_rule_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.network_collapse else R.string.network_expand))
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            LabeledDropdown(stringResource(R.string.network_field_chain), CHAINS, chain) { chain = it }
            Spacer(Modifier.height(8.dp))
            LabeledDropdown(stringResource(R.string.network_field_action), ACTIONS, action) { action = it }
            Spacer(Modifier.height(8.dp))
            LabeledDropdown(stringResource(R.string.network_field_protocol), PROTOCOLS, protocol) { protocol = it }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = source, onValueChange = { source = it }, label = { Text(stringResource(R.string.network_field_source)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = destination, onValueChange = { destination = it }, label = { Text(stringResource(R.string.network_field_destination)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = inIface, onValueChange = { inIface = it }, label = { Text(stringResource(R.string.network_field_in_iface)) }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(value = outIface, onValueChange = { outIface = it }, label = { Text(stringResource(R.string.network_field_out_iface)) }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = port, onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.network_field_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(10.dp))
            val builtArgs = buildRuleArgs(protocol, source, destination, inIface, outIface, port, action)
            Text(
                "-A $chain $builtArgs",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onAppendRule(chain, builtArgs) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.network_btn_apply))
            }
        }
    }
}

private fun buildRuleArgs(
    protocol: String,
    source: String,
    destination: String,
    inIface: String,
    outIface: String,
    port: String,
    action: String
): String {
    val sb = StringBuilder()
    if (protocol != "all") sb.append(" -p $protocol")
    if (source.isNotBlank()) sb.append(" -s $source")
    if (destination.isNotBlank()) sb.append(" -d $destination")
    if (inIface.isNotBlank()) sb.append(" -i $inIface")
    if (outIface.isNotBlank()) sb.append(" -o $outIface")
    if (port.isNotBlank() && protocol in listOf("tcp", "udp")) sb.append(" --dport $port")
    sb.append(" -j $action")
    return sb.toString().trim()
}

@Composable
private fun LabeledDropdown(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth().clickable { open = true }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(opt); open = false })
            }
        }
    }
}

@Composable
private fun RoutingSection(
    isRooted: Boolean,
    ipRules: List<String>,
    ipRoutes: List<String>,
    busy: Boolean,
    onRunRaw: (String) -> Unit
) {
    Text(stringResource(R.string.network_section_routing), style = MaterialTheme.typography.titleMedium)

    Text(stringResource(R.string.network_ip_rules_title), style = MaterialTheme.typography.titleSmall)
    SectionCard {
        if (ipRules.isEmpty()) {
            Text(stringResource(R.string.network_no_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ipRules.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }

    Text(stringResource(R.string.network_ip_routes_title), style = MaterialTheme.typography.titleSmall)
    SectionCard {
        if (ipRoutes.isEmpty()) {
            Text(stringResource(R.string.network_no_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ipRoutes.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }

    if (isRooted) {
        RawCommandBox(
            title = stringResource(R.string.network_raw_ip_title),
            prefix = "ip",
            placeholder = stringResource(R.string.network_raw_ip_placeholder),
            busy = busy,
            onRun = onRunRaw
        )
    } else {
        Text(
            stringResource(R.string.network_routing_root_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RawCommandBox(title: String, prefix: String, placeholder: String, busy: Boolean, onRun: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    SectionCard {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("$prefix ...") },
            placeholder = { Text(placeholder) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { onRun(text) },
            enabled = !busy && text.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.network_btn_run))
        }
    }
}
