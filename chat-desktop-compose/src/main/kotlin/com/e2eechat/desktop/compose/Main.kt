package com.e2eechat.desktop.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.e2eechat.desktop.compose.theme.AppTheme
import com.e2eechat.desktop.compose.ui.ChatScreen

/**
 * Entry point for the Compose Multiplatform desktop client.
 *
 * <p>Phase 1: the shell, the theme and the primary chat surface, on in-memory sample data. The
 * networking, identity and persistence still live in the Swing `chat-desktop` module; phase 2 wires
 * this front end to that logic (or to a client-core module extracted from it) behind the single
 * [com.e2eechat.desktop.compose.model.ChatStore] seam.
 */
fun main() = application {
    var dark by remember { mutableStateOf(true) }
    val windowState = rememberWindowState(width = 1180.dp, height = 780.dp)
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Tetherless",
    ) {
        AppTheme(dark = dark) {
            ChatScreen(dark = dark, onToggleTheme = { dark = !dark })
        }
    }
}
