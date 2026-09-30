package com.sainadh.livenotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupSettingsCard(
    busy: Boolean,
    progress: String?,
    onCreate: () -> Unit,
    onRestore: () -> Unit,
    message: String? = null,
    enabled: Boolean = true
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Backup & restore", style = MaterialTheme.typography.titleLarge)
            Text("Keep your notes, recordings and settings when moving from Preview to the Play Store app.",
                style = MaterialTheme.typography.bodyMedium)
            Text("Save the backup outside this app, such as Downloads or Drive. Your password protects the backup, including any saved API key. Downloaded models are optional.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(progress ?: "Working on your backup…", style = MaterialTheme.typography.bodyMedium)
            }
            message?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCreate, enabled = enabled && !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Create backup") }
                OutlinedButton(onClick = onRestore, enabled = enabled && !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Restore backup") }
            }
        }
    }
}

enum class BackupPasswordMode { CREATE, RESTORE }

/** Passwords stay in memory across rotation and never enter saved instance state. */
class BackupPasswordState : ViewModel() {
    var mode by mutableStateOf<BackupPasswordMode?>(null)
        private set
    var password by mutableStateOf("")
    var confirmation by mutableStateOf("")
    var includeModels by mutableStateOf(true)

    fun open(mode: BackupPasswordMode) {
        close()
        this.mode = mode
    }

    fun close() {
        mode = null
        password = ""
        confirmation = ""
        includeModels = true
    }

    override fun onCleared() { close(); super.onCleared() }
}

@Composable
fun BackupPasswordDialog(
    state: BackupPasswordState,
    busy: Boolean = false,
    error: String? = null,
    progress: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (password: String, includeModels: Boolean) -> Unit
) {
    val mode = state.mode ?: return
    val creating = mode == BackupPasswordMode.CREATE
    val valid = if (creating) state.password.length in 8..1024 && state.password == state.confirmation else state.password.length in 8..1024
    fun dismiss() { if (!busy) { state.close(); onDismiss() } }
    AlertDialog(onDismissRequest = ::dismiss,
        title = { Text(if (creating) "Protect your backup" else "Restore your backup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (creating) "Choose a password and keep it safe. You will need it to restore this backup."
                    else "Enter the backup password. Your existing notes are kept; the backup will be merged into this app.")
                OutlinedTextField(state.password, { state.password = it }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), label = { Text("Backup password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = if (creating) ({ Text("Use at least 8 characters.") }) else null)
                if (creating) {
                    OutlinedTextField(state.confirmation, { state.confirmation = it }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth(), label = { Text("Confirm password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = state.confirmation.isNotEmpty() && state.password != state.confirmation,
                        supportingText = if (state.confirmation.isNotEmpty() && state.password != state.confirmation) ({ Text("Passwords do not match.") }) else null)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                        value = state.includeModels, enabled = !busy, role = Role.Checkbox, onValueChange = { state.includeModels = it }),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = state.includeModels, onCheckedChange = null, enabled = !busy)
                        Text("Include downloaded models (large backup)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!state.includeModels) Text("Models can be downloaded again after restore.", style = MaterialTheme.typography.bodySmall)
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(progress ?: "Checking and restoring your backup…", style = MaterialTheme.typography.bodyMedium)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            TextButton(onClick = { focus.clearFocus(); keyboard?.hide(); onConfirm(state.password, state.includeModels) },
                enabled = !busy && valid, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (busy) "Working…" else if (creating) "Choose backup location" else "Restore backup")
            }
        },
        dismissButton = { TextButton(onClick = ::dismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } }
    )
}
