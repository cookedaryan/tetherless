package com.e2eechat.desktop.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.e2eechat.desktop.compose.model.ChatStore
import com.e2eechat.desktop.compose.theme.LocalAppColors

/** Root layout: a fixed-width sidebar, a hairline, and the chat pane filling the rest. */
@Composable
fun ChatScreen(dark: Boolean, onToggleTheme: () -> Unit) {
    val colors = LocalAppColors.current
    val store = remember { ChatStore() }

    Row(Modifier.fillMaxSize().background(colors.pageBg)) {
        Sidebar(
            store = store,
            dark = dark,
            onToggleTheme = onToggleTheme,
            modifier = Modifier.width(320.dp).fillMaxSize(),
        )
        VerticalHairline()
        ChatPane(store = store, modifier = Modifier.weight(1f).fillMaxSize())
    }
}
