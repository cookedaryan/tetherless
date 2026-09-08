package com.e2eechat.desktop;

import com.e2eechat.desktop.ui.Composer;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * What happens to typed text when a send does not go.
 *
 * <p>The composer clears itself the moment it hands the text over, which is right when the message
 * leaves and wrong when it does not: a refusal - no session, a key that has reached its send
 * budget - took the words off the screen and put nothing in their place. Restoring the draft is
 * what makes a refused send visible instead of a disappearance.
 *
 * <p>Skipped where there is no display, which is how CI runs.
 */
public class ComposerDraftTest {

    @BeforeClass
    public static void requireADisplay() {
        Assume.assumeFalse("no display on this machine", GraphicsEnvironment.isHeadless());
    }

    /** The composer has already cleared itself by the time a refusal comes back. */
    @Test
    public void aRestoredDraftIsPutBackInTheEmptyBox() {
        Composer composer = new Composer();
        JTextArea input = inputOf(composer);
        input.setText("");

        composer.restoreDraft("this one did not go");

        assertEquals("this one did not go", input.getText());
    }

    @Test
    public void restoringNothingLeavesTheBoxAlone() {
        Composer composer = new Composer();
        JTextArea input = inputOf(composer);
        input.setText("already typing something new");

        composer.restoreDraft(null);
        composer.restoreDraft("");

        assertEquals("already typing something new", input.getText());
    }

    /** A draft must never overwrite something the user has started typing since. */
    @Test
    public void aRestoredDraftDoesNotOverwriteNewerTyping() {
        Composer composer = new Composer();
        JTextArea input = inputOf(composer);
        input.setText("already typing something new");

        composer.restoreDraft("the older refused message");

        assertEquals("already typing something new", input.getText());
    }

    private static JTextArea inputOf(Container container) {
        JTextArea found = search(container);
        assertNotNull("the composer has no text area", found);
        return found;
    }

    /** Returns null when there is nothing to find, so the recursion can keep looking. */
    private static JTextArea search(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JTextArea) {
                return (JTextArea) child;
            }
            if (child instanceof Container) {
                JTextArea found = search((Container) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
