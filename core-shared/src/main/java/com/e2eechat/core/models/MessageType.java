package com.e2eechat.core.models;

/**
 * Wire message kinds.
 *
 * <p>{@code MessageCodec} serialises the {@linkplain Enum#ordinal() ordinal}, so constants may only
 * ever be <em>appended</em>. Inserting one in the middle silently reinterprets every message an
 * older peer sends.
 */
public enum MessageType {
    HELLO,
    HELLO_ACK,
    KEY_EXCHANGE_INIT,
    KEY_EXCHANGE_REPLY,
    KEY_EXCHANGE_REJECT,
    TEXT_MESSAGE,
    DELIVERY_ACK,
    DISCONNECT,
    ERROR,
    PING,
    PONG,

    // --- appended after the initial protocol freeze; see the ordinal warning above ---

    /**
     * Payload is a single byte: {@code 1} while the sender is composing, {@code 0} when they stop.
     * Signed but not encrypted - it carries no content, and the relay already learns who is talking
     * to whom from the routing header.
     */
    TYPING,

    /**
     * Sent when the recipient opens a conversation, to promote the peer's outgoing messages to
     * {@code READ}. Payload is empty; the sender/receiver pair is enough to act on it.
     */
    READ_RECEIPT
}
