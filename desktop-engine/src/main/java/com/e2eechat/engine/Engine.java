package com.e2eechat.engine;

import com.e2eechat.core.identity.PeerId;
import com.e2eechat.core.keys.JceKeyStoreManager;
import com.e2eechat.core.models.Message;
import com.e2eechat.core.network.ConnectionState;
import com.e2eechat.core.network.MessageListener;
import com.e2eechat.core.session.SessionManager;
import com.e2eechat.desktop.ChatClient;
import com.e2eechat.desktop.ChatMessage;
import com.e2eechat.desktop.Conversation;
import com.e2eechat.desktop.ConversationStore;
import com.e2eechat.desktop.DatabaseKeys;
import com.e2eechat.desktop.DesktopConfig;
import com.e2eechat.desktop.MessageRepository;
import com.e2eechat.desktop.PeerDirectory;
import com.e2eechat.desktop.ProfileStore;

import javax.crypto.SecretKey;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The client, driven by commands instead of by a window.
 *
 * <p>This is the whole of what {@code ChatWindow} and {@code Main} used to do that was not drawing:
 * unlock an identity, stand up a {@link ChatClient}, and turn protocol frames into events. It holds
 * no user interface and no transport of its own - {@code core-shared} and the existing client logic
 * are used exactly as the Swing client uses them, which is the point of the whole migration.
 *
 * <p>Deliberately separate from {@link EngineMain}: that class owns stdin and stdout, this one owns
 * the client, so the command surface can be tested without a pipe.
 *
 * <p><strong>Policy lives above this class.</strong> The engine reports that a message arrived; it
 * does not decide whether to notify, beep, mark a conversation read, or move an unread count. The
 * Swing window entangled those decisions with protocol handling. Here the caller asks for what it
 * wants - {@code readReceipt}, {@code markRead} - because only the caller knows what is on screen.
 */
public class Engine implements MessageListener {

    /**
     * Every command the engine knows.
     *
     * <p>Checked before the unlock gate, so a misspelled command says so instead of reporting
     * "locked" and sending the caller off to debug the wrong problem.
     */
    private static final Set<String> COMMANDS = new HashSet<String>(Arrays.asList(
            "status", "unlock", "shutdown", "connect", "disconnect", "listConversations",
            "history", "send", "startSecureChat", "renegotiate", "setActivePeer", "fingerprint",
            "setVerified", "markRead", "readReceipt", "typing", "search"));

    /** Where events go. Implemented by the stdio loop, and by a recorder in tests. */
    public interface EventSink {
        void emit(String event, Map<String, Object> payload);
    }

    /** A command the engine refuses, carrying a code the caller can branch on. */
    public static class CommandException extends Exception {
        private static final long serialVersionUID = 1L;
        private final String code;

        public CommandException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final File configDir;
    private final String dbPath;
    private final DesktopConfig config;
    private final JceKeyStoreManager keyStore;
    private final ProfileStore profileStore;
    private final EventSink events;

    private ChatClient client;
    private ConversationStore conversations;
    private String fingerprint;

    public Engine(File configDir, String dbPath, DesktopConfig config, EventSink events) {
        this.configDir = configDir;
        this.dbPath = dbPath;
        this.config = config;
        this.events = events;
        this.keyStore = new JceKeyStoreManager(configDir);
        this.profileStore = new ProfileStore(configDir);
    }

    /** True once an identity exists on disk, which decides whether the caller unlocks or creates. */
    public boolean identityExists() {
        return new File(configDir, "identity.p12").exists();
    }

    public boolean isUnlocked() {
        return client != null;
    }

    // ------------------------------------------------------------------ commands

    /**
     * Dispatches one command.
     *
     * @throws CommandException for anything the caller did wrong, which becomes an error frame
     *                          rather than a crash
     */
    public Map<String, Object> handle(String command, Map<String, Object> payload) throws Exception {
        if (!COMMANDS.contains(command)) {
            throw new CommandException("unknown_command", "No such command: " + command);
        }
        if ("status".equals(command)) {
            return status();
        }
        if ("unlock".equals(command)) {
            return unlock(chars(payload, "passphrase"), string(payload, "displayName"));
        }
        if ("shutdown".equals(command)) {
            disconnect();
            return new LinkedHashMap<String, Object>();
        }

        requireUnlocked();

        if ("connect".equals(command)) {
            return connect(payload);
        }
        if ("disconnect".equals(command)) {
            disconnect();
            return result("state", ConnectionState.DISCONNECTED.name());
        }
        if ("listConversations".equals(command)) {
            return listConversations();
        }
        if ("history".equals(command)) {
            return history(required(payload, "peerId"), intOr(payload, "limit", 200));
        }
        if ("send".equals(command)) {
            return send(required(payload, "peerId"), required(payload, "text"),
                    string(payload, "replyToId"));
        }
        if ("startSecureChat".equals(command)) {
            client.startSecureChat(required(payload, "peerId"));
            return new LinkedHashMap<String, Object>();
        }
        if ("renegotiate".equals(command)) {
            client.restartSecureChat(required(payload, "peerId"));
            return new LinkedHashMap<String, Object>();
        }
        if ("setActivePeer".equals(command)) {
            client.setCurrentPeerId(string(payload, "peerId"));
            return new LinkedHashMap<String, Object>();
        }
        if ("fingerprint".equals(command)) {
            return fingerprints(string(payload, "peerId"));
        }
        if ("setVerified".equals(command)) {
            client.getPeerDirectory().setVerified(required(payload, "peerId"),
                    bool(payload, "verified"));
            return new LinkedHashMap<String, Object>();
        }
        if ("markRead".equals(command)) {
            String peerId = required(payload, "peerId");
            client.getMessageRepository().markConversationRead(client.getClientId(), peerId);
            return new LinkedHashMap<String, Object>();
        }
        if ("readReceipt".equals(command)) {
            client.sendReadReceipt(required(payload, "peerId"));
            return new LinkedHashMap<String, Object>();
        }
        if ("typing".equals(command)) {
            client.sendTyping(required(payload, "peerId"), bool(payload, "typing"));
            return new LinkedHashMap<String, Object>();
        }
        if ("search".equals(command)) {
            return search(required(payload, "query"), intOr(payload, "limit", 50));
        }
        // Reachable only if a name is added to COMMANDS without a branch above.
        throw new CommandException("not_implemented", "Command is declared but not handled: "
                + command);
    }

    private Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("identityExists", Boolean.valueOf(identityExists()));
        out.put("unlocked", Boolean.valueOf(isUnlocked()));
        out.put("relay", config.host() + ":" + config.port());
        if (client != null) {
            out.put("clientId", client.getClientId());
            out.put("displayName", client.getLocalDisplayName());
            out.put("fingerprint", fingerprint);
            out.put("connectionState", client.getConnectionState().name());
        }
        return out;
    }

    /**
     * Unlocks an existing identity or creates the first one, then stands up the client.
     *
     * <p>This mirrors {@code Main}'s bootstrap step for step, including the database-key derivation
     * and the migration from the old HKDF key. The differences are that a failure is a returned
     * error rather than a dialog, and that the passphrase is wiped here rather than after a window
     * opens.
     */
    private synchronized Map<String, Object> unlock(char[] passphrase, String displayName)
            throws Exception {
        if (client != null) {
            throw new CommandException("already_unlocked", "The identity is already unlocked");
        }
        if (passphrase == null || passphrase.length == 0) {
            throw new CommandException("passphrase_required", "A passphrase is required");
        }
        boolean firstRun = !identityExists();
        if (firstRun && (displayName == null || displayName.trim().isEmpty())) {
            throw new CommandException("display_name_required",
                    "A display name is required when creating an identity");
        }

        try {
            KeyPair identity;
            try {
                identity = keyStore.loadOrCreateIdentity(passphrase);
            } catch (Exception e) {
                throw new CommandException("bad_passphrase", "Could not unlock the identity");
            }
            String clientId = PeerId.of(identity.getPublic());
            String name = displayName != null && !displayName.trim().isEmpty()
                    ? displayName.trim()
                    : profileStore.getDisplayName().orElse(clientId);

            SecretKey dbKey = deriveDatabaseKey(passphrase, clientId, firstRun);

            // Only after the passphrase has actually unlocked the identity, so a failed attempt
            // cannot overwrite a good name.
            profileStore.setDisplayName(name);

            MessageRepository repository = new MessageRepository(dbPath, dbKey);
            Function<String, PublicKey> peerKeyLookup = new Function<String, PublicKey>() {
                @Override
                public PublicKey apply(String senderId) {
                    try {
                        Optional<PublicKey> stored = keyStore.getPeerKey(senderId);
                        return stored.orElse(null);
                    } catch (Exception e) {
                        return null;
                    }
                }
            };

            SessionManager sessions = new SessionManager(clientId, peerKeyLookup);
            PeerDirectory directory = new PeerDirectory(configDir);
            client = new ChatClient(clientId, identity, sessions, repository, keyStore, directory,
                    name);
            client.addMessageListener(this);
            conversations = new ConversationStore(dbPath);
            fingerprint = keyStore.fingerprint(identity.getPublic());

            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("clientId", clientId);
            out.put("displayName", name);
            out.put("fingerprint", fingerprint);
            out.put("firstRun", Boolean.valueOf(firstRun));
            return out;
        } finally {
            Arrays.fill(passphrase, '\0');
        }
    }

    /**
     * The database key, stretched with PBKDF2 from the typed passphrase.
     *
     * <p>A profile with no stored parameters is either new or still encrypted under the old HKDF
     * derivation; both end up with fresh PBKDF2 parameters, and only the second has rows to
     * re-encrypt. A failed migration leaves the database untouched and refuses to continue rather
     * than carrying on with a key that cannot read it.
     */
    private SecretKey deriveDatabaseKey(char[] passphrase, String clientId, boolean firstRun)
            throws Exception {
        Optional<ProfileStore.KdfParameters> stored = profileStore.getKdfParameters();
        if (stored.isPresent()) {
            return DatabaseKeys.derive(passphrase, stored.get().salt, stored.get().iterations);
        }
        byte[] salt = DatabaseKeys.newSalt();
        SecretKey fresh = DatabaseKeys.derive(passphrase, salt, DatabaseKeys.ITERATIONS);
        if (!firstRun) {
            SecretKey legacy = DatabaseKeys.legacyHkdf(passphrase, clientId);
            int migrated = new MessageRepository(dbPath, legacy).migrateEncryption(legacy, fresh);
            if (migrated < 0) {
                throw new CommandException("migration_failed",
                        "Could not re-encrypt the local message database; it was left untouched");
            }
        }
        profileStore.setKdfParameters(salt, DatabaseKeys.ITERATIONS);
        return fresh;
    }

    private Map<String, Object> connect(Map<String, Object> payload) {
        String host = string(payload, "host");
        Integer port = payload.get("port") == null ? null
                : Integer.valueOf(intOr(payload, "port", config.port()));
        client.connect(host != null ? host : config.host(),
                port != null ? port.intValue() : config.port());
        return result("state", client.getConnectionState().name());
    }

    private synchronized void disconnect() {
        if (client != null) {
            client.disconnect();
        }
    }

    private Map<String, Object> listConversations() {
        List<Conversation> found = client.getMessageRepository()
                .getConversations(client.getClientId());
        List<Object> rows = new ArrayList<Object>();
        for (Conversation c : found) {
            ConversationStore.State state = conversations.get(c.getPeerId());
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("peerId", c.getPeerId());
            row.put("displayName", client.displayNameFor(c.getPeerId()));
            row.put("lastMessage", c.getLastMessage());
            row.put("lastTimestamp", Long.valueOf(c.getLastTimestamp()));
            row.put("lastFromSelf", Boolean.valueOf(c.isLastFromSelf()));
            row.put("unread", Integer.valueOf(c.getUnreadCount()));
            row.put("verified", Boolean.valueOf(client.getPeerDirectory()
                    .isVerified(c.getPeerId())));
            row.put("pinned", Boolean.valueOf(state.pinned));
            row.put("muted", Boolean.valueOf(state.muted));
            row.put("archived", Boolean.valueOf(state.archived));
            rows.add(row);
        }
        return result("conversations", rows);
    }

    private Map<String, Object> history(String peerId, int limit) {
        List<ChatMessage> found = client.getMessageRepository()
                .getMessages(client.getClientId(), peerId, limit);
        List<Object> rows = new ArrayList<Object>();
        for (ChatMessage m : found) {
            rows.add(describe(m, client.getClientId()));
        }
        return result("messages", rows);
    }

    private Map<String, Object> send(String peerId, String text, String replyToId)
            throws CommandException {
        client.setCurrentPeerId(peerId);
        ChatMessage replyTo = null;
        if (replyToId != null) {
            replyTo = findMessage(peerId, replyToId, REPLY_LOOKUP_LIMIT);
            if (replyTo == null) {
                // Refused rather than sent bare. Dropping the quote silently means the sender
                // believes they replied to something while the recipient sees an unexplained
                // message with no context.
                throw new CommandException("reply_target_missing",
                        "The message being replied to could not be found");
            }
        }
        ChatMessage sent = client.sendMessage(text, replyTo);
        if (sent == null) {
            return result("sent", null);
        }
        return result("sent", describe(sent, client.getClientId()));
    }

    private Map<String, Object> search(String query, int limit) {
        List<ChatMessage> found = client.getMessageRepository()
                .searchMessages(client.getClientId(), query, limit);
        List<Object> rows = new ArrayList<Object>();
        for (ChatMessage m : found) {
            rows.add(describe(m, client.getClientId()));
        }
        return result("messages", rows);
    }

    private Map<String, Object> fingerprints(String peerId) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ours", fingerprint);
        if (peerId != null) {
            out.put("theirs", client.getPeerFingerprint(peerId));
            out.put("verified", Boolean.valueOf(client.getPeerDirectory().isVerified(peerId)));
        }
        return out;
    }

    // -------------------------------------------------------------- protocol events

    @Override
    public void onMessageReceived(Message msg) {
        switch (msg.getType()) {
            case TEXT_MESSAGE:
                // The broadcast frame carries only the text; the reply fields were saved to the
                // repository just before it, so they are read back from that row. Without this an
                // incoming reply arrived live with its quote missing, and only appeared after the
                // conversation was reopened.
                ChatMessage stored = findMessage(msg.getSenderId(), msg.getMessageId(), 50);
                Map<String, Object> incoming = stored != null
                        ? describe(stored, client.getClientId())
                        : new LinkedHashMap<String, Object>();
                incoming.put("messageId", msg.getMessageId());
                incoming.put("peerId", msg.getSenderId());
                incoming.put("text", text(msg));
                incoming.put("timestamp", Long.valueOf(msg.getTimestamp()));
                incoming.put("direction", "in");
                incoming.put("status", ChatMessage.Status.DELIVERED.name());
                // For notifications, which name the sender rather than showing a bare id.
                incoming.put("displayName", client.displayNameFor(msg.getSenderId()));
                events.emit("message", incoming);
                return;

            case DELIVERY_ACK:
                String acked = text(msg);
                if (acked != null && !acked.isEmpty()) {
                    Map<String, Object> ack = new LinkedHashMap<String, Object>();
                    ack.put("messageId", acked);
                    ack.put("status", ChatMessage.Status.DELIVERED.name());
                    events.emit("deliveryStatus", ack);
                }
                return;

            case READ_RECEIPT:
                events.emit("readReceipt", single("peerId", msg.getSenderId()));
                return;

            case TYPING:
                Map<String, Object> typing = new LinkedHashMap<String, Object>();
                typing.put("peerId", msg.getSenderId());
                typing.put("typing", Boolean.valueOf(msg.getPayload() != null
                        && msg.getPayload().length > 0 && msg.getPayload()[0] == 1));
                events.emit("typing", typing);
                return;

            case ERROR:
                // A null payload is legal on the wire; an error with no reason in it has nothing to
                // report, so it is dropped rather than turned into an empty banner.
                String reason = text(msg);
                if (reason != null && !reason.isEmpty()) {
                    events.emit("error", single("message", reason));
                }
                return;

            default:
                // Handshake traffic is handled inside ChatClient; nothing to report.
        }
    }

    @Override
    public void onConnectionStateChanged(ConnectionState state) {
        events.emit("connectionState", single("state", state.name()));
    }

    // -------------------------------------------------------------------- helpers

    /**
     * How far back a reply may reach. Generous on purpose: the window loads up to 5000 messages
     * when it jumps to an old search hit, and a reply target the engine cannot see would otherwise
     * be refused for being merely old.
     */
    private static final int REPLY_LOOKUP_LIMIT = 5000;

    /** One message from a conversation by id, or null. Newest-first scan, so recent hits are cheap. */
    private ChatMessage findMessage(String peerId, String messageId, int limit) {
        for (ChatMessage candidate : client.getMessageRepository()
                .getMessages(client.getClientId(), peerId, limit)) {
            if (messageId.equals(candidate.getMessageId())) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<String, Object> describe(ChatMessage m, String self) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("messageId", m.getMessageId());
        row.put("peerId", m.getSender().equals(self) ? m.getReceiver() : m.getSender());
        row.put("text", m.getContent());
        row.put("timestamp", Long.valueOf(m.getTimestamp()));
        row.put("direction", m.getSender().equals(self) ? "out" : "in");
        row.put("status", m.getStatus() == null ? null : m.getStatus().name());
        row.put("replyToId", m.getReplyToId());
        row.put("replyToSender", m.getReplyToSender());
        row.put("replyToPreview", m.getReplyToPreview());
        row.put("error", Boolean.valueOf(m.isError()));
        return row;
    }

    private static String text(Message msg) {
        if (msg.getPayload() == null) {
            return "";
        }
        return new String(msg.getPayload(), StandardCharsets.UTF_8);
    }

    private void requireUnlocked() throws CommandException {
        if (client == null) {
            throw new CommandException("locked", "Unlock an identity first");
        }
    }

    private static Map<String, Object> result(String key, Object value) {
        return single(key, value);
    }

    private static Map<String, Object> single(String key, Object value) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put(key, value);
        return out;
    }

    private static String string(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String required(Map<String, Object> payload, String key)
            throws CommandException {
        String value = string(payload, key);
        if (value == null || value.isEmpty()) {
            throw new CommandException("missing_field", "Missing required field: " + key);
        }
        return value;
    }

    private static char[] chars(Map<String, Object> payload, String key) {
        String value = string(payload, key);
        return value == null ? null : value.toCharArray();
    }

    private static boolean bool(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static int intOr(Map<String, Object> payload, String key, int fallback) {
        Object value = payload == null ? null : payload.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
