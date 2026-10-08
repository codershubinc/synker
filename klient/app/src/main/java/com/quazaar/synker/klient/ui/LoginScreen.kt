package com.quazaar.synker.klient.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter


@Composable
fun LoginScreen(
    discoveredHost: String,
    discoveredPort: Int,
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var manualIp by remember { mutableStateOf(discoveredHost) }
    var errorMsg by remember { mutableStateOf("") }
    var isLoggingIn by remember { mutableStateOf(false) }
    
    // Update input if discoveredHost changes
    LaunchedEffect(discoveredHost) {
        if (manualIp.isEmpty() || manualIp.startsWith("172.")) {
            manualIp = discoveredHost
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppleBlack)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Synker Admin Login", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppleTextPrimary)
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = manualIp,
            onValueChange = { manualIp = it },
            label = { Text("Daemon IP Address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Admin Password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (errorMsg.isNotEmpty()) {
            Text(errorMsg, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = {
                if (password.isBlank() || manualIp.isBlank()) return@Button
                isLoggingIn = true
                errorMsg = ""
                scope.launch {
                    val success = attemptLogin(context, manualIp, discoveredPort, password)
                    isLoggingIn = false
                    if (success) {
                        context.getSharedPreferences("synker_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().putString("manual_host", manualIp).apply()
                        onLoginSuccess()
                    } else {
                        errorMsg = "Invalid password or server unreachable."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            enabled = !isLoggingIn,
            colors = ButtonDefaults.buttonColors(containerColor = AppleAccent)
        ) {
            Text(if (isLoggingIn) "Connecting..." else "Login", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}


suspend fun attemptLogin(context: Context, host: String, port: Int, pass: String): Boolean = withContext(Dispatchers.IO) {
    var conn: HttpURLConnection? = null
    try {
        val url = URL("http://$host:$port/api/v1/login")
        conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val reqJson = JSONObject()
        reqJson.put("password", pass)

        OutputStreamWriter(conn.outputStream, "UTF-8").use {
            it.write(reqJson.toString())
            it.flush()
        }

        if (conn.responseCode in 200..299) {
            val responseText = conn.inputStream.bufferedReader().readText()
            val resJson = JSONObject(responseText)
            val token = resJson.optString("token")
            if (token.isNotEmpty()) {
                context.getSharedPreferences("synker_prefs", Context.MODE_PRIVATE)
                    .edit().putString("auth_token", token).apply()
                return@withContext true
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        conn?.disconnect()
    }
    return@withContext false
}
