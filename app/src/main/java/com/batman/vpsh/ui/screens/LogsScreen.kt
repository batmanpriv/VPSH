package com.batman.vpsh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.batman.vpsh.R
import com.batman.vpsh.ui.components.SectionCard

@Composable
fun LogsScreen(logs: List<String>, onHealthCheck: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(onClick = onHealthCheck) { Text(stringResource(R.string.health_check_button)) }
        }
        Spacer(Modifier.height(14.dp))
        if (logs.isEmpty()) {
            Text(stringResource(R.string.no_logs_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(logs.reversed()) { line ->
                    SectionCard {
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
