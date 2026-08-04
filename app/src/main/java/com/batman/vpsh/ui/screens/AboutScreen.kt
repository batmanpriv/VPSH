package com.batman.vpsh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.batman.vpsh.R
import com.batman.vpsh.ui.components.SectionCard

private const val GITHUB_URL = "https://github.com/BatmanPriv"
private const val TELEGRAM_URL = "https://t.me/BatmanPriv"
private const val APP_VERSION = "3.1.0"

private const val CLIENT_WINDOWS_URL =
    "https://github.com/batmanpriv/VPSH/releases/download/3.1.0/client-windows.bat"
private const val CLIENT_LINUX_URL =
    "https://github.com/batmanpriv/VPSH/releases/download/3.1.0/client-linux.sh"

@Composable
fun AboutScreen() {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.about_app_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Text(
            stringResource(R.string.about_version, APP_VERSION),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionCard {
            Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyMedium)
        }

        Text(stringResource(R.string.about_features_title), style = MaterialTheme.typography.titleMedium)
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureLine(Icons.Filled.Router, stringResource(R.string.about_feature_proxy))
                FeatureLine(Icons.Filled.Security, stringResource(R.string.about_feature_full))
                FeatureLine(Icons.Filled.Shield, stringResource(R.string.about_feature_batproxy))
                FeatureLine(Icons.Filled.Language, stringResource(R.string.about_feature_bilingual))
                FeatureLine(Icons.Filled.Bolt, stringResource(R.string.about_feature_killswitch))
            }
        }

        Text(stringResource(R.string.about_guide_title), style = MaterialTheme.typography.titleMedium)
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GuideBlock(
                    Icons.Filled.Dashboard,
                    stringResource(R.string.about_guide_dashboard_title),
                    stringResource(R.string.about_guide_dashboard_desc)
                )
                GuideBlock(
                    Icons.Filled.Shield,
                    stringResource(R.string.about_guide_batproxy_title),
                    stringResource(R.string.about_guide_batproxy_desc)
                )
                GuideBlock(
                    Icons.Filled.Settings,
                    stringResource(R.string.about_guide_settings_title),
                    stringResource(R.string.about_guide_settings_desc)
                )
                GuideBlock(
                    Icons.Filled.Description,
                    stringResource(R.string.about_guide_logs_title),
                    stringResource(R.string.about_guide_logs_desc)
                )
            }
        }

        Text(stringResource(R.string.about_mode_title), style = MaterialTheme.typography.titleMedium)
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GuideBlock(
                    Icons.Filled.Router,
                    stringResource(R.string.mode_proxy),
                    stringResource(R.string.about_mode_proxy_desc)
                )
                GuideBlock(
                    Icons.Filled.Security,
                    stringResource(R.string.mode_full),
                    stringResource(R.string.about_mode_full_desc)
                )
            }
        }

        Text(stringResource(R.string.about_clients_title), style = MaterialTheme.typography.titleMedium)
        SectionCard {
            Text(
                stringResource(R.string.about_clients_desc),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            ClientLinkRow(
                icon = Icons.Filled.Terminal,
                label = stringResource(R.string.about_client_windows)
            ) { uriHandler.openUri(CLIENT_WINDOWS_URL) }
            Spacer(Modifier.height(8.dp))
            ClientLinkRow(
                icon = Icons.Filled.Terminal,
                label = stringResource(R.string.about_client_linux)
            ) { uriHandler.openUri(CLIENT_LINUX_URL) }
        }

        Text(stringResource(R.string.about_author_title), style = MaterialTheme.typography.titleMedium)
        SectionCard {
            Text(
                stringResource(R.string.about_author_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Text("BatmanPriv", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LinkChip(stringResource(R.string.about_github), Modifier.weight(1f)) { uriHandler.openUri(GITHUB_URL) }
                LinkChip(stringResource(R.string.about_telegram), Modifier.weight(1f)) { uriHandler.openUri(TELEGRAM_URL) }
            }
        }

        Text(
            stringResource(R.string.about_disclaimer),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FeatureLine(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun GuideBlock(icon: ImageVector, title: String, desc: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ClientLinkRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
            )
        }
    }
}

@Composable
private fun LinkChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(label)
    }
}