package com.batman.vpsh.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.batman.vpsh.R
import com.batman.vpsh.ui.components.SectionCard
import com.batman.vpsh.viewmodel.UpdateUiState

@Composable
fun UpdateScreen(
    state: UpdateUiState,
    currentVersion: String,
    onCheck: () -> Unit,
    onOpenReleasePage: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.update_current_version, currentVersion),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))

        SectionCard(modifier = Modifier.fillMaxWidth()) {
            AnimatedContent(targetState = state, label = "update-state") { s ->
                when (s) {
                    is UpdateUiState.Idle -> IdleContent(onCheck)
                    is UpdateUiState.Checking -> CheckingContent()
                    is UpdateUiState.UpToDate -> UpToDateContent(onCheck)
                    is UpdateUiState.Available -> AvailableContent(s.remoteVersion) { onOpenReleasePage(s.releasePageUrl) }
                    is UpdateUiState.Error -> ErrorContent(s.message, onCheck)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.update_source_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun IdleContent(onCheck: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
        Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.update_idle_desc), style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Button(onClick = onCheck) { Text(stringResource(R.string.update_check_button)) }
    }
}

@Composable
private fun CheckingContent() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.update_checking), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun UpToDateContent(onRecheck: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.update_up_to_date), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(18.dp))
        OutlinedButton(onClick = onRecheck) { Text(stringResource(R.string.update_check_again)) }
    }
}

@Composable
private fun AvailableContent(remoteVersion: String, onOpenReleasePage: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
        Icon(Icons.Filled.OpenInBrowser, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.update_available, remoteVersion),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.update_available_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = onOpenReleasePage) { Text(stringResource(R.string.update_open_page_button)) }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.update_failed), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.update_retry)) }
    }
}
