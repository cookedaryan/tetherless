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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Jumping to a message by id.
 *
 * <p>This is what makes a search result something a person can act on rather than something they
 * can only read.
 *
 * <p>{@code TranscriptPanel} is a Swing component under construction here, so every step -
 * building it, loading history, and calling {@code scrollTo} - runs on the event dispatch thread,
 * matching the convention set by {@code SidePanelTest}.
 */
public class TranscriptScrollToTest {

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

    private static TranscriptPanel withHistory(int count) {
        TranscriptPanel transcript = new TranscriptPanel("alice");
        transcript.setSize(600, 400);
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            history.add(new ChatMessage("m" + i, "alice", "bob", "message " + i,
                    1000L + i, ChatMessage.Status.SENT));
        }
        transcript.setHistory(history);
        return transcript;
    }

    @Test
    public void aKnownMessageIsFound() throws Exception {
        final boolean[] found = new boolean[1];

        onEdt(() -> {
            TranscriptPanel transcript = withHistory(40);
            found[0] = transcript.scrollTo("m7");
        });

        assertTrue(found[0]);
    }

    @Test
    public void anUnknownMessageIsReportedRatherThanIgnored() throws Exception {
        final boolean[] foundUnknown = new boolean[1];
        final boolean[] foundNull = new boolean[1];

        onEdt(() -> {
            TranscriptPanel transcript = withHistory(40);
            foundUnknown[0] = transcript.scrollTo("no-such-message");
            foundNull[0] = transcript.scrollTo(null);
        });

        assertFalse(foundUnknown[0]);
        assertFalse(foundNull[0]);
    }

    @Test
    public void replacingTheHistoryForgetsTheOldRows() throws Exception {
        final boolean[] foundBeforeReplace = new boolean[1];
        final boolean[] foundAfterReplace = new boolean[1];

        onEdt(() -> {
            TranscriptPanel transcript = withHistory(10);
            foundBeforeReplace[0] = transcript.scrollTo("m3");

            transcript.setHistory(new ArrayList<ChatMessage>());

            foundAfterReplace[0] = transcript.scrollTo("m3");
        });

        assertTrue(foundBeforeReplace[0]);
        assertFalse("rows from the previous conversation should be gone", foundAfterReplace[0]);
    }
}
