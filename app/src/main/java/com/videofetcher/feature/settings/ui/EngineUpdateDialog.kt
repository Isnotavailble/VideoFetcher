package com.videofetcher.feature.settings.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.videofetcher.feature.settings.viewmodel.EngineUpdateState
import com.videofetcher.feature.settings.viewmodel.SettingsViewModel

@Composable
fun EngineUpdateDialog(
    state: EngineUpdateState,
    viewModel: SettingsViewModel,
    context: Context
) {
    when (state) {
        is EngineUpdateState.Checking -> {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Checking for updates...") },
                text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
                confirmButton = { }
            )
        }
        is EngineUpdateState.UpToDate -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetEngineUpdateState() },
                title = { Text("Up to Date") },
                text = { Text("You are already using the latest engine version.") },
                confirmButton = {
                    TextButton(onClick = { viewModel.resetEngineUpdateState() }) {
                        Text("OK")
                    }
                }
            )
        }
        is EngineUpdateState.UpdateAvailable -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetEngineUpdateState() },
                title = { Text("Update Available") },
                text = { Text("Engine version ${state.version} is available. Update now?") },
                confirmButton = {
                    TextButton(onClick = { viewModel.updateEngine(context) }) {
                        Text("Update")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.resetEngineUpdateState() }) {
                        Text("Later")
                    }
                }
            )
        }
        is EngineUpdateState.Updating -> {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Updating Engine") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Please wait...", style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = { }
            )
        }
        is EngineUpdateState.Success -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetEngineUpdateState() },
                title = { Text("Update Successful", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                text = { Text("The download engine has been updated successfully.") },
                confirmButton = {
                    TextButton(onClick = { viewModel.resetEngineUpdateState() }) {
                        Text("OK")
                    }
                }
            )
        }
        is EngineUpdateState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetEngineUpdateState() },
                title = { Text("Update Failed", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) },
                text = { Text(state.message) },
                confirmButton = {
                    TextButton(onClick = { viewModel.resetEngineUpdateState() }) {
                        Text("OK")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.checkForEngineUpdate(context) }) {
                        Text("Retry")
                    }
                }
            )
        }
        is EngineUpdateState.Idle -> { }
    }
}
