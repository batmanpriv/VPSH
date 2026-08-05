package com.batman.vpsh

import android.Manifest
import android.content.Context
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.batman.vpsh.ui.screens.AboutScreen
import com.batman.vpsh.ui.screens.BatProxyScreen
import com.batman.vpsh.ui.screens.DashboardScreen
import com.batman.vpsh.ui.screens.HotspotScreen
import com.batman.vpsh.ui.screens.LogsScreen
import com.batman.vpsh.ui.screens.SettingsScreen
import com.batman.vpsh.ui.theme.VpshTheme
import com.batman.vpsh.util.LocaleHelper
import com.batman.vpsh.viewmodel.BatProxyViewModel
import com.batman.vpsh.viewmodel.HotspotViewModel
import com.batman.vpsh.viewmodel.VpshViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: VpshViewModel by viewModels()
    private val batProxyViewModel: BatProxyViewModel by viewModels()
    private val hotspotViewModel: HotspotViewModel by viewModels()

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            batProxyViewModel.start()
        } else {
            android.widget.Toast.makeText(this, getString(R.string.batproxy_vpn_permission_denied), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.bind()
        batProxyViewModel.bindIfRunning()

        setContent {
            VpshTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    VpshApp(
                        viewModel = viewModel,
                        batProxyViewModel = batProxyViewModel,
                        hotspotViewModel = hotspotViewModel,
                        onRequestStartBatProxy = ::requestStartBatProxy,
                        onLanguageChange = ::changeLanguage
                    )
                }
            }
        }
    }

    private fun requestStartBatProxy() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else batProxyViewModel.start()
    }

    private fun changeLanguage(lang: String) {
        LocaleHelper.setLanguage(this, lang)
        recreate()
    }

    override fun onDestroy() {
        viewModel.unbind()
        batProxyViewModel.unbind()
        super.onDestroy()
    }
}

private data class NavDestination(val index: Int, val icon: ImageVector, val labelRes: Int)

private val NAV_DESTINATIONS = listOf(
    NavDestination(0, Icons.Filled.Dashboard, R.string.nav_dashboard),
    NavDestination(1, Icons.Filled.WifiTethering, R.string.nav_hotspot),
    NavDestination(2, Icons.Filled.Shield, R.string.batproxy_tab_title),
    NavDestination(3, Icons.Filled.Settings, R.string.nav_settings),
    NavDestination(4, Icons.Filled.List, R.string.nav_logs),
    NavDestination(5, Icons.Filled.Info, R.string.nav_about)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpshApp(
    viewModel: VpshViewModel,
    batProxyViewModel: BatProxyViewModel,
    hotspotViewModel: HotspotViewModel,
    onRequestStartBatProxy: () -> Unit,
    onLanguageChange: (String) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    val state by viewModel.uiState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val registryEntries by viewModel.registryEntries.collectAsState()
    val upstreamProfiles by viewModel.upstreamProfileList.collectAsState()
    val batState by batProxyViewModel.uiState.collectAsState()
    val batConfig by batProxyViewModel.config.collectAsState()
    val hotspotIsRooted by hotspotViewModel.isRooted.collectAsState()
    val hotspotIface by hotspotViewModel.hotspotIface.collectAsState()
    val hotspotIp by hotspotViewModel.hotspotIp.collectAsState()
    val hotspotType by hotspotViewModel.hotspotType.collectAsState()
    val hotspotDevices by hotspotViewModel.devices.collectAsState()
    val hotspotBanned by hotspotViewModel.bannedEntries.collectAsState()
    val hotspotBusy by hotspotViewModel.busy.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val currentTitle = stringResource(NAV_DESTINATIONS.first { it.index == tab }.labelRes)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "VPSH",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                NAV_DESTINATIONS.forEach { dest ->
                    NavigationDrawerItem(
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(stringResource(dest.labelRes)) },
                        selected = tab == dest.index,
                        onClick = {
                            tab = dest.index
                            coroutineScope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    ) {
        Scaffold(
            
            topBar = {
                TopAppBar(
                    title = { Text(currentTitle) },
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = null)
                        }
                    }
                )
            }
        ) { padding ->
            Surface(modifier = Modifier.padding(padding)) {
                when (tab) {
                    0 -> DashboardScreen(
                        state = state,
                        settings = settings,
                        registryEntries = registryEntries,
                        onModeChange = { mode -> viewModel.updateSettings { it.copy(mode = mode) } },
                        onStart = { viewModel.start() },
                        onStop = { viewModel.stop() },
                        onToggleBlock = { key -> viewModel.toggleBlock(key) },
                        onSetNickname = { key, name -> viewModel.setNickname(key, name) },
                        onSetLimit = { key, kbps -> viewModel.setLimit(key, kbps) },
                        onEnableUsbTether = { onResult -> viewModel.requestEnableUsbTether(onResult) },
                        onCreateGuest = { ttl, quota -> viewModel.createGuestLink(ttl, quota) },
                        onRevokeGuest = { user -> viewModel.revokeGuestLink(user) }
                    )
                    1 -> HotspotScreen(
                        isRooted = hotspotIsRooted,
                        hotspotIface = hotspotIface,
                        hotspotIp = hotspotIp,
                        hotspotType = hotspotType,
                        devices = hotspotDevices,
                        bannedEntries = hotspotBanned,
                        busy = hotspotBusy,
                        onStartPolling = { hotspotViewModel.startPolling() },
                        onStopPolling = { hotspotViewModel.stopPolling() },
                        onBan = { mac, ip -> hotspotViewModel.ban(mac, lastIp = ip) },
                        onUnban = { mac -> hotspotViewModel.unban(mac) }
                    )
                    2 -> BatProxyScreen(
                        state = batState,
                        config = batConfig,
                        onConnect = onRequestStartBatProxy,
                        onDisconnect = { batProxyViewModel.stop() },
                        onAddWorker = { worker -> batProxyViewModel.addWorker(worker) },
                        onRemoveWorker = { url -> batProxyViewModel.removeWorker(url) },
                        onSetDns = { host, port -> batProxyViewModel.setDns(host, port) },
                        onSetSplitTunnel = { mode, apps -> batProxyViewModel.setSplitTunnel(mode, apps) }
                    )
                    3 -> SettingsScreen(
                        settings = settings,
                        onChange = viewModel::updateSettings,
                        onLanguageChange = onLanguageChange,
                        upstreamProfiles = upstreamProfiles,
                        onSaveUpstreamProfile = { name -> viewModel.saveUpstreamProfile(name) },
                        onApplyUpstreamProfile = { p -> viewModel.applyUpstreamProfile(p) },
                        onDeleteUpstreamProfile = { name -> viewModel.deleteUpstreamProfile(name) }
                    )
                    4 -> LogsScreen(logs = state.logs, onHealthCheck = { viewModel.runHealthCheck() })
                    else -> AboutScreen()
                }
            }
        }
    }
}
