package com.virjar.tk

import androidx.compose.runtime.*
import com.virjar.tk.client.*
import com.virjar.tk.dto.UserDto
import com.virjar.tk.navigation.*
import com.virjar.tk.ui.screen.*
import com.virjar.tk.ui.theme.TeamTalkTheme
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val appJson = Json { ignoreUnknownKeys = true }

/**
 * Android entry point — single-window navigation with Login/Register + Main content.
 * Desktop uses its own Main.kt with dual windows and calls [MainAppContent] directly.
 */
@Composable
fun App() {
    val apiClient = remember { ApiClient() }

    // Navigation state
    var navDestination by remember { mutableStateOf<NavDestination>(NavDestination.Login) }

    // User context (null when not logged in)
    var userContext by remember { mutableStateOf<UserContext?>(null) }

    // Try to restore session from persistent storage
    LaunchedEffect(Unit) {
        val (token, uid, userJson) = apiClient.restoreSession() ?: return@LaunchedEffect
        try {
            val user = appJson.decodeFromString<UserDto>(userJson)
            val ctx = UserContext(token, uid, user, apiClient, apiClient.getTokenStorage())
            ctx.onForceLogout = {
                userContext?.destroy()
                userContext = null
                navDestination = NavDestination.Login
            }
            ctx.persistSession()
            ctx.connectTcp()
            userContext = ctx
            navDestination = NavDestination.Main()
            AppLog.i("App", "Session restored for uid=${user.uid}")
        } catch (e: Exception) {
            AppLog.e("App", "Failed to restore session", e)
            apiClient.getTokenStorage().clear()
        }
    }

    if (userContext != null) {
        MainAppContent(
            userContext = userContext!!,
            onLogout = {
                userContext?.destroy()
                userContext = null
                navDestination = NavDestination.Login
            },
        )
    } else {
        TeamTalkTheme {
            val scope = rememberCoroutineScope()
            when (val dest = navDestination) {
                is NavDestination.Login -> LoginScreen(
                    onLoginSuccess = {
                        navDestination = NavDestination.Main()
                    },
                    onNavigateToRegister = { navDestination = NavDestination.Register },
                    onLogin = { user, pass ->
                        val result = apiClient.login(user, pass)
                        val ctx = UserContext(result.accessToken, result.uid, result.user, apiClient, apiClient.getTokenStorage())
                        ctx.onForceLogout = {
                            userContext?.destroy()
                            userContext = null
                            navDestination = NavDestination.Login
                        }
                        ctx.persistSession()
                        scope.launch {
                            try {
                                ctx.connectTcp()
                            } catch (e: Exception) {
                                AppLog.e("App", "connectTcp after login failed", e)
                            }
                        }
                        userContext = ctx
                        true
                    },
                )

                is NavDestination.Register -> RegisterScreen(
                    onRegisterSuccess = {
                        navDestination = NavDestination.Main()
                    },
                    onNavigateBack = { navDestination = NavDestination.Login },
                    onRegister = { user, pass, name ->
                        val result = apiClient.register(user, pass, name)
                        val ctx = UserContext(result.accessToken, result.uid, result.user, apiClient, apiClient.getTokenStorage())
                        ctx.onForceLogout = {
                            userContext?.destroy()
                            userContext = null
                            navDestination = NavDestination.Login
                        }
                        ctx.persistSession()
                        scope.launch {
                            try {
                                ctx.connectTcp()
                            } catch (e: Exception) {
                                AppLog.e("App", "connectTcp after register failed", e)
                            }
                        }
                        userContext = ctx
                        true
                    },
                )

                else -> {}
            }
        }
    }
}
