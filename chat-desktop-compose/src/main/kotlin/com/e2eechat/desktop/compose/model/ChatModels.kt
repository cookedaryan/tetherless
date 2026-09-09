package com.e2eechat.desktop.compose.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

/** Which side of the conversation a message is on. */
enum class Direction { IN, OUT }

/** Delivery state of an outgoing message. Mirrors the Swing client's `ChatMessage.Status`. */
enum class DeliveryStatus { SENT, DELIVERED, READ, FAILED }

/** One message in a transcript. `dateHeader` non-null marks the first message of a new day. */
data class Message(
    val id: String,
    val direction: Direction,
    val text: String,
    val time: String,
    val status: DeliveryStatus = DeliveryStatus.READ,
    val dateHeader: String? = null,
)

/** One row in the conversation list. */
data class Conversation(
    val id: String,
    val name: String,
    val lastMessage: String,
    val time: String,
    val unread: Int = 0,
    val verified: Boolean = false,
    val online: Boolean = false,
)

/**
 * In-memory UI state and the actions that mutate it.
 *
 * <p>This is deliberately the whole seam between the UI and the world. Phase 1 seeds it with sample
 * data so the interface is alive without the network; phase 2 replaces the body of [send] and the
 * seeding with the real `ChatClient` / `MessageRepository` from the Swing module (or a client-core
 * module extracted from it) without the composables changing.
 */
class ChatStore {
    val conversations: SnapshotStateList<Conversation> = mutableStateListOf()
    private val threads = mutableStateMapOf<String, SnapshotStateList<Message>>()

    var selectedId: String? by mutableStateOf(null)
        private set

    init {
        conversations.addAll(SampleData.conversations)
        SampleData.threads.forEach { (id, msgs) -> threads[id] = mutableStateListOf(*msgs.toTypedArray()) }
        selectedId = conversations.firstOrNull()?.id
    }

    fun select(id: String) {
        selectedId = id
        val index = conversations.indexOfFirst { it.id == id }
        if (index >= 0 && conversations[index].unread > 0) {
            conversations[index] = conversations[index].copy(unread = 0)
        }
    }

    fun messagesFor(id: String?): SnapshotStateList<Message> =
        if (id == null) EMPTY else threads.getOrPut(id) { mutableStateListOf() }

    fun selected(): Conversation? = conversations.firstOrNull { it.id == selectedId }

    /** Appends an outgoing message to the open conversation and updates its list preview. */
    fun send(text: String) {
        val id = selectedId ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val now = clock()
        messagesFor(id).add(
            Message(
                id = "m" + System.nanoTime(),
                direction = Direction.OUT,
                text = trimmed,
                time = now,
                status = DeliveryStatus.SENT,
            )
        )
        val index = conversations.indexOfFirst { it.id == id }
        if (index >= 0) {
            conversations[index] = conversations[index].copy(lastMessage = trimmed, time = now)
        }
    }

    private fun clock(): String {
        val c = java.util.Calendar.getInstance()
        return String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    private companion object {
        val EMPTY: SnapshotStateList<Message> = mutableStateListOf()
    }
}

/** Placeholder content so the UI is legible before the client is wired in. */
object SampleData {
    val conversations = listOf(
        Conversation("p1", "Aria Chen", "The safety numbers match — verified ✓", "14:32", unread = 0, verified = true, online = true),
        Conversation("p2", "Noor Rahman", "Sending the build over now", "13:05", unread = 2, verified = false, online = true),
        Conversation("p3", "Devs · pinned", "Ratchet lands on the feature branch", "11:47", unread = 0, verified = true),
        Conversation("p4", "Kae Watanabe", "let's sync tomorrow morning", "Yesterday", unread = 0, verified = false),
        Conversation("p5", "Marco Silva", "👍", "Yesterday", unread = 0, verified = false),
        Conversation("p6", "Priya Nair", "Draft of the deck is ready", "Mon", unread = 0, verified = true),
    )

    val threads = mapOf(
        "p1" to listOf(
            Message("a1", Direction.IN, "Hey — did you get the invite?", "14:20", dateHeader = "Today"),
            Message("a2", Direction.OUT, "Yes, just read your key fingerprint aloud", "14:22", DeliveryStatus.READ),
            Message("a3", Direction.IN, "Same here. Every group matches.", "14:31"),
            Message("a4", Direction.IN, "The safety numbers match — verified ✓", "14:32"),
        ),
        "p2" to listOf(
            Message("b1", Direction.IN, "Build's almost done compiling", "12:58", dateHeader = "Today"),
            Message("b2", Direction.OUT, "No rush, take your time", "13:01", DeliveryStatus.DELIVERED),
            Message("b3", Direction.IN, "Sending the build over now", "13:05"),
        ),
        "p3" to listOf(
            Message("c1", Direction.IN, "Ratchet lands on the feature branch", "11:47", dateHeader = "Today"),
        ),
    )
}
