package com.e2eechat.desktop;

/**
 * Told when a message that had been queued locally finally reaches the relay.
 *
 * <p>Deliberately not part of {@code MessageListener}. That interface carries events that came off
 * the wire, and this one has no wire event behind it: the outbox is a local idea, invisible to the
 * relay and to the peer. A message leaving the queue is the client telling the window something
 * about itself, so inventing a protocol message type to express it would put a fiction on the
 * wire's enum.
 */
public interface OutboxListener {

    /**
     * @param peerId    who the message was queued for
     * @param messageId the message that has now been handed to the relay, so its bubble can drop
     *                  the clock for a tick
     */
    void onQueuedMessageSent(String peerId, String messageId);
}
