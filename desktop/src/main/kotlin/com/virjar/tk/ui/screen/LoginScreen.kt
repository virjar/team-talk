package com.virjar.tk.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onLogin: suspend (String, String) -> Boolean,
    showServerSettings: Boolean = false,
    currentServerUrl: String = "",
    currentTcpHost: String = "",
    onServerConfigChange: ((baseUrl: String, tcpHost: String) -> Unit)? = null,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var serverUrl by remember(currentServerUrl) { mutableStateOf(currentServerUrl) }
    var tcpHostField by remember(currentTcpHost) { mutableStateOf(currentTcpHost) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("TeamTalk", style = MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Login", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it; error = "" },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = "" },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (error.isNotEmpty()) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                loading = true
                error = ""
                scope.launch {
                    try {
                        if (onServerConfigChange != null && showServerSettings) {
                            if (serverUrl.isNotBlank() && serverUrl != currentServerUrl ||
                                tcpHostField.isNotBlank() && tcpHostField != currentTcpHost
                            ) {
                                val baseUrl = serverUrl.ifBlank { currentServerUrl }
                                val host = tcpHostField.ifBlank { currentTcpHost }
                                onServerConfigChange(baseUrl, host)
                            }
                        }
                        val success = onLogin(username, password)
                        if (success) onLoginSuccess() else error = "Login failed"
                    } catch (e: Exception) {
                        AppLog.e("Login", "login failed", e)
                        error = e.message ?: "Network error"
                    } finally {
                        loading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading && username.isNotBlank() && password.isNotBlank(),
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Login")
        }
        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = onNavigateToRegister) {
            Text("No account? Register")
        }

        if (showServerSettings) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Advanced Settings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showAdvanced = !showAdvanced },
            )
            AnimatedVisibility(visible = showAdvanced) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("Server URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tcpHostField,
                        onValueChange = { tcpHostField = it },
                        label = { Text("TCP Host") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
        }
    }
}
