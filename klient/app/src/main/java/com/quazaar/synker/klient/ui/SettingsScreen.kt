package com.quazaar.synker.klient.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.quazaar.synker.klient.QuazaarApplication

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var syncStatus by remember { mutableStateOf("") }
    val syncManager = QuazaarApplication.instance.syncManager
    val dbHelper = QuazaarApplication.instance.databaseHelper

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    Button(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Text("Client Data Management", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))
            
            val context = androidx.compose.ui.platform.LocalContext.current
            Button(onClick = {
                coroutineScope.launch {
                    try {
                        val jsonString = syncManager.buildSyncPayload(dbHelper, forceOverride = true)
                        
                        // Parse and re-stringify with pretty-printing using org.json
                        val formattedJson = org.json.JSONObject(jsonString).toString(4)
                        
                        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                        val file = java.io.File(dir, "quazaar_data_export_${System.currentTimeMillis()}.json")
                        file.writeText(formattedJson)
                        
                        syncStatus = "Saved to:\n${file.absolutePath}"
                    } catch (e: Exception) {
                        syncStatus = "Error: ${e.message}"
                    }
                }
            }) {
                Text("Download Data as JSON")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = {
                    coroutineScope.launch {
                        syncStatus = "Force syncing..."
                        val success = syncManager.performSync(forceOverride = true)
                        syncStatus = if (success) "Overwrite Successful!" else "Overwrite Failed"
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Force Overwrite Server Stats")
            }
            
            if (syncStatus.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(syncStatus, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
