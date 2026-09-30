package com.e2eechat.engine;

import com.e2eechat.desktop.DatabaseHelper;
import com.e2eechat.desktop.DesktopConfig;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The command surface, driven without a pipe.
 *
 * <p>These exercise the real bootstrap - the real PKCS#12 keystore, real PBKDF2 at the shipped
 * iteration count, a real SQLite database - because that bootstrap is the part of {@code Main} that
 * was tangled up with Swing dialogs, and the whole claim of this module is that it survives being
 * pulled out of them.
 */
public class EngineTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File configDir;
    private String dbPath;
    private DesktopConfig config;
    private final List<String> events = new ArrayList<String>();

    @Before
    public void setUp() throws Exception {
        configDir = tmp.newFolder("profile");
        dbPath = new File(configDir, "chat.db").getAbsolutePath();
        DatabaseHelper.initializeDatabase(dbPath);
        config = DesktopConfig.load(configDir, new String[0]);
    }

    private Engine newEngine() {
        return new Engine(configDir, dbPath, config, new Engine.EventSink() {
            @Override
            public void emit(String event, Map<String, Object> payload) {
                events.add(event);
            }
        });
    }

    private static Map<String, Object> payload(String... pairs) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    @Test
    public void everythingIsRefusedUntilAnIdentityIsUnlocked() throws Exception {
        Engine engine = newEngine();
        assertFalse(engine.identityExists());
        assertFalse(engine.isUnlocked());
        try {
            engine.handle("listConversations", payload());
            fail("a locked engine must refuse to read anything");
        } catch (Engine.CommandException e) {
            assertEquals("locked", e.code());
        }
    }

    @Test
    public void statusIsAnsweredWhileStillLocked() throws Exception {
        Map<String, Object> status = newEngine().handle("status", payload());
        assertEquals(Boolean.FALSE, status.get("identityExists"));
        assertEquals(Boolean.FALSE, status.get("unlocked"));
        // The caller needs somewhere to connect before it has an identity, so this is not gated.
        assertNotNull(status.get("relay"));
    }

    @Test
    public void creatingAnIdentityNeedsADisplayName() throws Exception {
        try {
            newEngine().handle("unlock", payload("passphrase", "correct horse battery staple"));
            fail("a first run with no display name should be refused");
        } catch (Engine.CommandException e) {
            assertEquals("display_name_required", e.code());
        }
    }

    @Test
    public void theSamePassphraseReopensTheSameIdentity() throws Exception {
        Map<String, Object> created = newEngine().handle("unlock",
                payload("passphrase", "correct horse battery staple", "displayName", "Aria Chen"));
        assertEquals(Boolean.TRUE, created.get("firstRun"));
        String clientId = String.valueOf(created.get("clientId"));
        assertNotNull(created.get("fingerprint"));

        // A second engine over the same profile: the routing id is a function of the identity key,
        // so it has to come back identical or every peer's address for this user has changed.
        Engine reopened = newEngine();
        assertTrue(reopened.identityExists());
        Map<String, Object> unlocked = reopened.handle("unlock",
                payload("passphrase", "correct horse battery staple"));
        assertEquals(Boolean.FALSE, unlocked.get("firstRun"));
        assertEquals(clientId, String.valueOf(unlocked.get("clientId")));
        assertEquals("Aria Chen", String.valueOf(unlocked.get("displayName")));
    }

    @Test
    public void aWrongPassphraseIsRefusedWithoutSayingWhy() throws Exception {
        newEngine().handle("unlock",
                payload("passphrase", "correct horse battery staple", "displayName", "Aria Chen"));
        try {
            newEngine().handle("unlock", payload("passphrase", "not the passphrase"));
            fail("the wrong passphrase must not unlock the identity");
        } catch (Engine.CommandException e) {
            assertEquals("bad_passphrase", e.code());
            // Deliberately not "no such key" or "bad MAC": the reason is not the caller's business.
            assertEquals("Could not unlock the identity", e.getMessage());
        }
    }

    @Test
    public void anUnlockedEngineReadsAnEmptyHistory() throws Exception {
        Engine engine = newEngine();
        engine.handle("unlock",
                payload("passphrase", "correct horse battery staple", "displayName", "Aria Chen"));

        Map<String, Object> conversations = engine.handle("listConversations", payload());
        assertTrue(((List<?>) conversations.get("conversations")).isEmpty());

        Map<String, Object> history = engine.handle("history", payload("peerId", "someone"));
        assertTrue(((List<?>) history.get("messages")).isEmpty());
    }

    /**
     * A reply to a message the engine cannot find is refused, not sent without its quote.
     *
     * <p>It used to look in the last 200 messages and, finding nothing, send the text as a plain
     * message. The sender believed they had replied to something; the recipient saw an unexplained
     * message with no context. Failing loudly is the only outcome that tells the sender the truth.
     */
    @Test
    public void aReplyToAnUnknownMessageIsRefusedRatherThanSentBare() throws Exception {
        Engine engine = newEngine();
        engine.handle("unlock",
                payload("passphrase", "correct horse battery staple", "displayName", "Aria Chen"));
        try {
            engine.handle("send", payload("peerId", "someone", "text", "yes",
                    "replyToId", "no-such-message"));
            fail("a reply to a message that does not exist must be refused");
        } catch (Engine.CommandException e) {
            assertEquals("reply_target_missing", e.code());
        }
    }

    @Test
    public void anUnknownCommandIsNamedInTheError() throws Exception {
        try {
            newEngine().handle("teleport", payload());
            fail("an unknown command should be refused");
        } catch (Engine.CommandException e) {
            assertEquals("unknown_command", e.code());
            assertTrue(e.getMessage().contains("teleport"));
        }
    }
}
