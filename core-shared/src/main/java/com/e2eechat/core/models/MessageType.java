package com.e2eechat.core.models;

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
    PONG
}
