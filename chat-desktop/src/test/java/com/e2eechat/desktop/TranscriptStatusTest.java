package com.e2eechat.desktop;

import com.e2eechat.desktop.ui.TranscriptPanel;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Which way a tick is allowed to move.
 *
 * <p>{@code markAllRead} guards its transition and {@code updateStatus} did not, so a redelivered
 * {@code DELIVERY_ACK} - which the client re-acks on every read receipt, and which the relay is
 * expected to repeat - could pull a bubble back from a filled double tick to a plain one. The
 * person watching sees the peer un-read their message.
 *
 * <p>{@code TranscriptPanel} is a Swing component, so every step runs on the event dispatch
 * thread, matching the convention set by {@code SidePanelTest}.
 */
public class TranscriptStatusTest {

    @BeforeClass
    public static void requireADisplay() {
        Assume.assumeFalse("no display on this machine", GraphicsEnvironment.isHeadless());
    }

    /**
     * Runs {@code action} on the event dispatch thread and waits for it to finish, rethrowing
     * whatever it threw so a failing assertion still fails the test cleanly.
     */
    private static void onEdt(Runnable action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    /** One outgoing message from the local user, at {@code status}. */
    private static TranscriptPanel withOutgoing(ChatMessage.Status status) {
        TranscriptPanel transcript = new TranscriptPanel("alice");
        transcript.setSize(600, 400);
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("m1", "alice", "bob", "hello", 1000L, status));
        transcript.setHistory(history);
        return transcript;
    }

    @Test
    public void aRedeliveredAcknowledgementDoesNotUndoARead() throws Exception {
        final ChatMessage.Status[] seen = new ChatMessage.Status[1];

        onEdt(() -> {
            TranscriptPanel transcript = withOutgoing(ChatMessage.Status.SENT);
            transcript.markAllRead();

            transcript.updateStatus("m1", ChatMessage.Status.DELIVERED);

            seen[0] = transcript.getMessages().get(0).getStatus();
        });

        assertEquals("a repeated delivery ack pulled the tick back off a read message",
                ChatMessage.Status.READ, seen[0]);
    }

    /** The guard is about going backwards, not about standing still. */
    @Test
    public void aStatusStillAdvances() throws Exception {
        final ChatMessage.Status[] afterDelivered = new ChatMessage.Status[1];
        final ChatMessage.Status[] afterRead = new ChatMessage.Status[1];

        onEdt(() -> {
            TranscriptPanel transcript = withOutgoing(ChatMessage.Status.SENT);

            transcript.updateStatus("m1", ChatMessage.Status.DELIVERED);
            afterDelivered[0] = transcript.getMessages().get(0).getStatus();

            transcript.updateStatus("m1", ChatMessage.Status.READ);
            afterRead[0] = transcript.getMessages().get(0).getStatus();
        });

        assertEquals(ChatMessage.Status.DELIVERED, afterDelivered[0]);
        assertEquals(ChatMessage.Status.READ, afterRead[0]);
    }

    /**
     * A failed or queued message is not on the delivery ladder at all, so a later send has to be
     * able to put it back on - otherwise a message that goes out on the next connection keeps the
     * warning it was given while it was stuck.
     */
    @Test
    public void aMessageThatFailedCanStillBeSent() throws Exception {
        final ChatMessage.Status[] fromFailed = new ChatMessage.Status[1];
        final ChatMessage.Status[] fromPending = new ChatMessage.Status[1];

        onEdt(() -> {
            TranscriptPanel failed = withOutgoing(ChatMessage.Status.FAILED);
            failed.updateStatus("m1", ChatMessage.Status.SENT);
            fromFailed[0] = failed.getMessages().get(0).getStatus();

            TranscriptPanel pending = withOutgoing(ChatMessage.Status.PENDING);
            pending.updateStatus("m1", ChatMessage.Status.SENT);
            fromPending[0] = pending.getMessages().get(0).getStatus();
        });

        assertEquals(ChatMessage.Status.SENT, fromFailed[0]);
        assertEquals(ChatMessage.Status.SENT, fromPending[0]);
    }

    /** A message going the other way should not be marked read by an ack for the peer's copy. */
    @Test
    public void anUnknownMessageIdChangesNothing() throws Exception {
        final ChatMessage.Status[] seen = new ChatMessage.Status[1];

        onEdt(() -> {
            TranscriptPanel transcript = withOutgoing(ChatMessage.Status.SENT);
            transcript.updateStatus("no-such-message", ChatMessage.Status.READ);
            seen[0] = transcript.getMessages().get(0).getStatus();
        });

        assertEquals(ChatMessage.Status.SENT, seen[0]);
    }
}
