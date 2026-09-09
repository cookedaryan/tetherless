package com.e2eechat.desktop.compose.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.e2eechat.desktop.compose.model.ChatStore
import com.e2eechat.desktop.compose.model.Conversation
import com.e2eechat.desktop.compose.model.Direction
import com.e2eechat.desktop.compose.model.Message
import com.e2eechat.desktop.compose.theme.LocalAppColors

/** The right column: header, transcript, composer. Switching conversation cross-fades the body. */
@Composable
fun ChatPane(store: ChatStore, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(modifier = modifier.background(colors.chatBg)) {
        val conv = store.selected()
        if (conv == null) {
            EmptyState()
            return@Column
        }
        ChatHeader(conv)
        HairlineDivider()
        Crossfade(
            targetState = conv.id,
            animationSpec = tween(200),
            modifier = Modifier.weight(1f),
        ) { id ->
            Transcript(store.messagesFor(id))
        }
        HairlineDivider()
        Composer(onSend = store::send)
    }
}

@Composable
private fun ChatHeader(conv: Conversation) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().height(60.dp).background(colors.headerBg).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(conv.name, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conv.name, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (conv.verified) {
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Filled.Verified, contentDescription = "Verified", tint = colors.accent, modifier = Modifier.size(15.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    if (conv.online) "encrypted · online" else "encrypted",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
            }
        }
        IconButton(onClick = {}) { Icon(Icons.Filled.Call, contentDescription = "Call", tint = colors.icon) }
        IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = colors.icon) }
    }
}

@Composable
private fun Transcript(messages: List<Message>) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items(messages.size) { index ->
            val msg = messages[index]
            msg.dateHeader?.let { DateSeparator(it) }
            MessageBubble(msg)
        }
    }
}

@Composable
private fun DateSeparator(label: String) {
    val colors = LocalAppColors.current
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(colors.inputBg).padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(label, color = colors.textSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MessageBubble(msg: Message) {
    val colors = LocalAppColors.current
    val outgoing = msg.direction == Direction.OUT
    val shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (outgoing) 16.dp else 4.dp,
        bottomEnd = if (outgoing) 4.dp else 16.dp,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .clip(shape)
                .background(if (outgoing) colors.bubbleOut else colors.bubbleIn)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(msg.text, color = colors.textPrimary, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.End)) {
                Text(msg.time, color = if (outgoing) colors.timeOut else colors.timeIn, fontSize = 11.sp)
                if (outgoing) {
                    Spacer(Modifier.width(4.dp))
                    StatusTicks(msg.status)
                }
            }
        }
    }
}

@Composable
private fun Composer(onSend: (String) -> Unit) {
    val colors = LocalAppColors.current
    var text by remember { mutableStateOf("") }

    fun submit() {
        if (text.isNotBlank()) {
            onSend(text)
            text = ""
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().background(colors.composerBg).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.inputBg)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text("Message", color = colors.textSecondary, fontSize = 14.sp)
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { e ->
                        if (e.key == Key.Enter && e.type == KeyEventType.KeyDown && !e.isShiftPressed) {
                            submit(); true
                        } else {
                            false
                        }
                    },
            )
        }
        Spacer(Modifier.width(10.dp))
        AnimatedVisibility(
            visible = text.isNotBlank(),
            enter = scaleIn(tween(140)) + fadeIn(tween(140)),
            exit = scaleOut(tween(120)) + fadeOut(tween(120)),
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = { submit() }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = colors.badgeText)
                }
            }
        }
    }
}

@Composable
private fun HairlineDivider() {
    val colors = LocalAppColors.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
}

@Composable
private fun EmptyState() {
    val colors = LocalAppColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text("Select a conversation", color = colors.textSecondary, fontSize = 15.sp)
            Text("Messages are end-to-end encrypted", color = colors.textSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
fun VerticalHairline(modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Box(modifier.width(1.dp).fillMaxHeight().background(colors.divider))
}
