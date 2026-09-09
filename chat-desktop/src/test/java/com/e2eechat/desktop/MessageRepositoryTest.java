package com.e2eechat.desktop;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Exercises {@link MessageRepository} against a real in-memory SQLite database, built through the
 * production migration path so the fixture always has whatever columns the repository writes.
 */
public class MessageRepositoryTest {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String CAROL = "carol";

    private MessageRepository repository;
    private String dbPath;
    private SecretKey dbKey;

    /** SQLite discards a shared in-memory database when the last connection to it closes. */
    private Connection keepAlive;

    @Before
    public void setUp() throws Exception {
        String dbName = "memdb_" + UUID.randomUUID().toString().replace("-", "");
        dbPath = "file:" + dbName + "?mode=memory&cache=shared";
        keepAlive = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

        DatabaseHelper.initializeDatabase(dbPath);

        dbKey = key((byte) 1);
        repository = new MessageRepository(dbPath, dbKey);
    }

    @After
    public void tearDown() throws Exception {
        if (keepAlive != null) {
            keepAlive.close();
        }
    }

    private static SecretKey key(byte seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i + seed);
        }
        return new SecretKeySpec(bytes, "AES");
    }

    /** The raw column as it sits on disk, before the repository decrypts it. */
    private String storedContent(String sender) throws Exception {
        try (PreparedStatement pstmt = keepAlive.prepareStatement(
                "SELECT content FROM messages WHERE sender = ? LIMIT 1")) {
            pstmt.setString(1, sender);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue("no row stored for " + sender, rs.next());
                return rs.getString("content");
            }
        }
    }

    private int rowCount() throws Exception {
        try (Statement stmt = keepAlive.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM messages")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ------------------------------------------------------------ at-rest encryption

    @Test
    public void bodiesAreEncryptedOnDiskAndReadBackInTheClear() throws Exception {
        repository.saveMessage(ALICE, BOB, "Hello Bob, this is a secret!", 123456L);

        String stored = storedContent(ALICE);
        assertNotEquals("Hello Bob, this is a secret!", stored);
        assertFalse("plaintext survived into the database", stored.contains("Hello"));

        List<ChatMessage> messages = repository.getMessages(ALICE, BOB, 10);
        assertEquals(1, messages.size());
        assertEquals("Hello Bob, this is a secret!", messages.get(0).getContent());
        assertEquals(ALICE, messages.get(0).getSender());
        assertEquals(BOB, messages.get(0).getReceiver());
    }

    /**
     * Identical text must not produce identical ciphertext, or the database leaks which messages
     * repeat without anyone needing the key.
     */
    @Test
    public void repeatedTextEncryptsDifferentlyEachTime() throws Exception {
        repository.saveMessage(ALICE, BOB, "same words", 1);
        repository.saveMessage(BOB, ALICE, "same words", 2);

        assertNotEquals(storedContent(ALICE), storedContent(BOB));
    }

    @Test
    public void aRowWrittenBeforeAtRestEncryptionStillReads() throws Exception {
        // Rows predating CLIENT-DESKTOP-05 hold plaintext, which is not valid Base64.
        try (PreparedStatement pstmt = keepAlive.prepareStatement(
                "INSERT INTO messages (sender, receiver, content, timestamp) VALUES (?, ?, ?, ?)")) {
            pstmt.setString(1, ALICE);
            pstmt.setString(2, BOB);
            pstmt.setString(3, "written before encryption landed");
            pstmt.setLong(4, 10L);
            pstmt.executeUpdate();
        }

        List<ChatMessage> messages = repository.getMessages(ALICE, BOB, 10);
        assertEquals(1, messages.size());
        assertEquals("written before encryption landed", messages.get(0).getContent());
    }

    @Test
    public void aRowThatWillNotDecryptIsReportedRatherThanThrown() {
        repository.saveMessage(ALICE, BOB, "readable under the right key", 1);

        List<ChatMessage> underWrongKey =
                new MessageRepository(dbPath, key((byte) 99)).getMessages(ALICE, BOB, 10);

        assertEquals(1, underWrongKey.size());
        assertTrue("a failed decryption should be visible to the user",
                underWrongKey.get(0).isError());
    }

    // ----------------------------------------------------------------- reading

    @Test
    public void messagesComeBackOldestFirst() {
        repository.saveMessage(ALICE, BOB, "third", 300);
        repository.saveMessage(ALICE, BOB, "first", 100);
        repository.saveMessage(BOB, ALICE, "second", 200);

        List<ChatMessage> messages = repository.getMessages(ALICE, BOB, 10);

        assertEquals(3, messages.size());
        assertEquals("first", messages.get(0).getContent());
        assertEquals("second", messages.get(1).getContent());
        assertEquals("third", messages.get(2).getContent());
    }

    /** The limit has to take the newest messages, not the first ones written. */
    @Test
    public void theLimitKeepsTheMostRecentMessages() {
        for (int i = 1; i <= 5; i++) {
            repository.saveMessage(ALICE, BOB, "message " + i, i * 100L);
        }

        List<ChatMessage> messages = repository.getMessages(ALICE, BOB, 2);

        assertEquals(2, messages.size());
        assertEquals("message 4", messages.get(0).getContent());
        assertEquals("message 5", messages.get(1).getContent());
    }

    @Test
    public void aConversationHoldsOnlyItsTwoParticipants() {
        repository.saveMessage(ALICE, BOB, "for bob", 1);
        repository.saveMessage(ALICE, CAROL, "for carol", 2);
        repository.saveMessage(CAROL, ALICE, "from carol", 3);

        List<ChatMessage> withBob = repository.getMessages(ALICE, BOB, 10);

        assertEquals(1, withBob.size());
        assertEquals("for bob", withBob.get(0).getContent());
    }

    @Test
    public void knownPeersCoverBothDirections() {
        repository.saveMessage(ALICE, BOB, "Hi Bob", 100);
        repository.saveMessage(CAROL, ALICE, "Hi Alice", 101);
        repository.saveMessage(ALICE, "dave", "Hi Dave", 102);

        List<String> peers = repository.getKnownPeers(ALICE);

        assertEquals(3, peers.size());
        assertTrue(peers.contains(BOB));
        assertTrue(peers.contains(CAROL));
        assertTrue(peers.contains("dave"));
    }

    // ------------------------------------------------------------- redelivery

    /**
     * The relay may deliver the same message twice. A second copy must not become a second row, or
     * the transcript shows it twice.
     */
    @Test
    public void aRedeliveredMessageDoesNotCreateASecondRow() throws Exception {
        String messageId = UUID.randomUUID().toString();
        repository.saveMessage(messageId, BOB, ALICE, "only once", 1,
                ChatMessage.Status.DELIVERED, null, null, null, false);
        repository.saveMessage(messageId, BOB, ALICE, "only once", 1,
                ChatMessage.Status.DELIVERED, null, null, null, false);

        assertEquals(1, rowCount());
        assertEquals(1, repository.getMessages(ALICE, BOB, 10).size());
    }

    /** Messages without an id are distinct events, so they must not collapse into one another. */
    @Test
    public void messagesWithoutAnIdAreNotDeduplicated() throws Exception {
        repository.saveMessage(ALICE, BOB, "one", 1);
        repository.saveMessage(ALICE, BOB, "two", 2);

        assertEquals(2, rowCount());
    }

    // ------------------------------------------------------------ delivery state

    /**
     * Rows written before SENDING was removed must still open.
     *
     * <p>readRow already catches IllegalArgumentException from Status.valueOf and falls back to
     * SENT. This pins that behaviour against the constant that no longer exists, because the
     * fallback is the only reason removing it is safe.
     */
    @Test
    public void aRowStoredWithARetiredStatusStillReads() throws Exception {
        try (PreparedStatement pstmt = keepAlive.prepareStatement(
                "INSERT INTO messages (message_id, sender, receiver, content, timestamp, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            pstmt.setString(1, "legacy-1");
            pstmt.setString(2, ALICE);
            pstmt.setString(3, BOB);
            pstmt.setString(4, "written when SENDING existed");
            pstmt.setLong(5, 1L);
            pstmt.setString(6, "SENDING");
            pstmt.executeUpdate();
        }

        List<ChatMessage> messages = repository.getMessages(ALICE, BOB, 10);

        assertEquals(1, messages.size());
        assertEquals(ChatMessage.Status.SENT, messages.get(0).getStatus());
    }

    @Test
    public void statusAdvancesForTheNamedMessage() {
        String messageId = UUID.randomUUID().toString();
        repository.saveMessage(messageId, ALICE, BOB, "in flight", 1,
                ChatMessage.Status.SENT, null, null, null, true);

        repository.updateStatus(messageId, ChatMessage.Status.DELIVERED);

        assertEquals(ChatMessage.Status.DELIVERED,
                repository.getMessages(ALICE, BOB, 10).get(0).getStatus());
    }

    /**
     * The regression the transcript already refused, now refused by the row as well.
     *
     * <p>This client re-acknowledges on every read receipt and the relay may redeliver, so a
     * {@code DELIVERY_ACK} arriving after a {@code READ_RECEIPT} is ordinary traffic, not an
     * attack. Writing it moved the row back to {@code DELIVERED}; the open window kept showing the
     * filled tick, so nothing looked wrong until a restart read the downgrade back.
     */
    @Test
    public void aLateDeliveryAckCannotPullAReadMessageBack() {
        repository.saveMessage("m1", ALICE, BOB, "read already", 1,
                ChatMessage.Status.READ, null, null, null, true);

        repository.updateStatus("m1", ChatMessage.Status.DELIVERED);

        assertEquals(ChatMessage.Status.READ,
                repository.getMessages(ALICE, BOB, 10).get(0).getStatus());
    }

    @Test
    public void statusNeverWalksBackDownTheLadder() {
        repository.saveMessage("m1", ALICE, BOB, "delivered", 1,
                ChatMessage.Status.DELIVERED, null, null, null, true);

        repository.updateStatus("m1", ChatMessage.Status.SENT);
        repository.updateStatus("m1", ChatMessage.Status.PENDING);

        assertEquals(ChatMessage.Status.DELIVERED,
                repository.getMessages(ALICE, BOB, 10).get(0).getStatus());
    }

    /** Re-applying the status a message already has changes nothing and is not an error. */
    @Test
    public void reapplyingTheSameStatusIsHarmless() {
        repository.saveMessage("m1", ALICE, BOB, "delivered", 1,
                ChatMessage.Status.DELIVERED, null, null, null, true);

        repository.updateStatus("m1", ChatMessage.Status.DELIVERED);

        assertEquals(ChatMessage.Status.DELIVERED,
                repository.getMessages(ALICE, BOB, 10).get(0).getStatus());
    }

    /**
     * FAILED is off the ladder in both directions, so the guard must not trap a message there.
     *
     * <p>A send that fails has to be able to say so, and a retry that succeeds has to be able to
     * clear the warning again.
     */
    @Test
    public void failureIsNotOnTheLadderAndMovesInBothDirections() {
        repository.saveMessage("m1", ALICE, BOB, "went out", 1,
                ChatMessage.Status.SENT, null, null, null, true);

        repository.updateStatus("m1", ChatMessage.Status.FAILED);
        assertEquals(ChatMessage.Status.FAILED,
                repository.getMessages(ALICE, BOB, 10).get(0).getStatus());

        repository.updateStatus("m1", ChatMessage.Status.SENT);
        assertEquals(ChatMessage.Status.SENT,
                repository.getMessages(ALICE, BOB, 10).get(0).getStatus());
    }

    @Test
    public void updatingToANullStatusIsANoOp() {
        repository.saveMessage("m1", ALICE, BOB, "untouched", 1,
                ChatMessage.Status.SENT, null, null, null, true);

        repository.updateStatus("m1", null);

        assertEquals(ChatMessage.Status.SENT,
                repository.getMessages(ALICE, BOB, 10).get(0).getStatus());
    }

    @Test
    public void updatingAnUnknownOrNullIdIsANoOp() {
        repository.saveMessage(ALICE, BOB, "untouched", 1);

        repository.updateStatus(null, ChatMessage.Status.READ);
        repository.updateStatus("no-such-message", ChatMessage.Status.READ);

        assertEquals(ChatMessage.Status.SENT,
                repository.getMessages(ALICE, BOB, 10).get(0).getStatus());
    }

    /**
     * A double tick has to survive a restart, which means the row has to carry it rather than the
     * transcript alone.
     */
    @Test
    public void anAdvancedStatusIsStillThereAfterReopeningTheDatabase() {
        repository.saveMessage("m1", ALICE, BOB, "delivered later", 1,
                ChatMessage.Status.SENT, null, null, null, true);

        repository.updateStatus("m1", ChatMessage.Status.DELIVERED);

        MessageRepository reopened = new MessageRepository(dbPath, dbKey);
        assertEquals(ChatMessage.Status.DELIVERED,
                reopened.getMessages(ALICE, BOB, 10).get(0).getStatus());
    }

    @Test
    public void aReadReceiptAdvancesSentAndDeliveredMessagesOnly() {
        repository.saveMessage("m1", ALICE, BOB, "sent", 1,
                ChatMessage.Status.SENT, null, null, null, true);
        repository.saveMessage("m2", ALICE, BOB, "delivered", 2,
                ChatMessage.Status.DELIVERED, null, null, null, true);
        repository.saveMessage("m3", ALICE, BOB, "failed", 3,
                ChatMessage.Status.FAILED, null, null, null, true);

        repository.markOutgoingRead(ALICE, BOB);

        List<ChatMessage> messages = repository.getMessages(ALICE, BOB, 10);
        assertEquals(ChatMessage.Status.READ, messages.get(0).getStatus());
        assertEquals(ChatMessage.Status.READ, messages.get(1).getStatus());
        assertEquals("a failed send must not be reported as read",
                ChatMessage.Status.FAILED, messages.get(2).getStatus());
    }

    // ---------------------------------------------------------- conversation list

    @Test
    public void theConversationListCarriesTheNewestMessagePerPeer() {
        repository.saveMessage(ALICE, BOB, "older to bob", 100);
        repository.saveMessage(BOB, ALICE, "newest from bob", 300);
        repository.saveMessage(ALICE, CAROL, "only to carol", 200);

        List<Conversation> conversations = repository.getConversations(ALICE);

        assertEquals(2, conversations.size());
        // Ordered newest first.
        assertEquals(BOB, conversations.get(0).getPeerId());
        assertEquals("newest from bob", conversations.get(0).getLastMessage());
        assertFalse(conversations.get(0).isLastFromSelf());

        assertEquals(CAROL, conversations.get(1).getPeerId());
        assertEquals("only to carol", conversations.get(1).getLastMessage());
        assertTrue(conversations.get(1).isLastFromSelf());
    }

    @Test
    public void unreadCountsOnlyIncomingMessagesThatWereNotMarkedRead() {
        repository.saveMessage("in1", BOB, ALICE, "unread one", 1,
                ChatMessage.Status.DELIVERED, null, null, null, false);
        repository.saveMessage("in2", BOB, ALICE, "unread two", 2,
                ChatMessage.Status.DELIVERED, null, null, null, false);
        repository.saveMessage("in3", BOB, ALICE, "already seen", 3,
                ChatMessage.Status.DELIVERED, null, null, null, true);
        repository.saveMessage("out1", ALICE, BOB, "mine", 4,
                ChatMessage.Status.SENT, null, null, null, true);

        assertEquals(2, repository.getConversations(ALICE).get(0).getUnreadCount());
    }

    @Test
    public void markingAConversationReadClearsItsBadge() {
        repository.saveMessage("in1", BOB, ALICE, "unread one", 1,
                ChatMessage.Status.DELIVERED, null, null, null, false);
        repository.saveMessage("in2", BOB, ALICE, "unread two", 2,
                ChatMessage.Status.DELIVERED, null, null, null, false);

        assertEquals(2, repository.markConversationRead(ALICE, BOB));
        assertEquals(0, repository.getConversations(ALICE).get(0).getUnreadCount());
        // Nothing is left to clear the second time round.
        assertEquals(0, repository.markConversationRead(ALICE, BOB));
    }

    @Test
    public void markingOneConversationReadLeavesTheOthersAlone() {
        repository.saveMessage("b1", BOB, ALICE, "from bob", 1,
                ChatMessage.Status.DELIVERED, null, null, null, false);
        repository.saveMessage("c1", CAROL, ALICE, "from carol", 2,
                ChatMessage.Status.DELIVERED, null, null, null, false);

        repository.markConversationRead(ALICE, BOB);

        for (Conversation conversation : repository.getConversations(ALICE)) {
            int expected = CAROL.equals(conversation.getPeerId()) ? 1 : 0;
            assertEquals(conversation.getPeerId(), expected, conversation.getUnreadCount());
        }
    }

    // -------------------------------------------------------------------- search

    @Test
    public void searchMatchesRegardlessOfCase() {
        repository.saveMessage(ALICE, BOB, "Meet me at the Harbour", 1);
        repository.saveMessage(ALICE, BOB, "nothing relevant here", 2);

        List<ChatMessage> hits = repository.searchMessages(ALICE, "HARBOUR", 10);

        assertEquals(1, hits.size());
        assertEquals("Meet me at the Harbour", hits.get(0).getContent());
    }

    @Test
    public void searchSpansEveryConversationTheUserIsPartOf() {
        repository.saveMessage(ALICE, BOB, "shared keyword", 1);
        repository.saveMessage(CAROL, ALICE, "shared keyword", 2);
        repository.saveMessage(BOB, CAROL, "shared keyword", 3);

        assertEquals(2, repository.searchMessages(ALICE, "shared", 10).size());
    }

    @Test
    public void searchStopsAtTheLimit() {
        for (int i = 0; i < 10; i++) {
            repository.saveMessage(ALICE, BOB, "needle " + i, i);
        }

        assertEquals(3, repository.searchMessages(ALICE, "needle", 3).size());
    }

    @Test
    public void aBlankSearchMatchesNothing() {
        repository.saveMessage(ALICE, BOB, "anything", 1);

        assertTrue(repository.searchMessages(ALICE, null, 10).isEmpty());
        assertTrue(repository.searchMessages(ALICE, "", 10).isEmpty());
        assertTrue(repository.searchMessages(ALICE, "   ", 10).isEmpty());
    }

    // --------------------------------------------------------------------- replies

    @Test
    public void replyMetadataSurvivesARoundTrip() {
        repository.saveMessage("reply-1", ALICE, BOB, "yes, agreed", 2,
                ChatMessage.Status.SENT, "original-1", BOB, "shall we meet?", true);

        ChatMessage stored = repository.getMessages(ALICE, BOB, 10).get(0);

        assertTrue(stored.hasReply());
        assertEquals("original-1", stored.getReplyToId());
        assertEquals(BOB, stored.getReplyToSender());
        assertEquals("shall we meet?", stored.getReplyToPreview());
    }

    /** The quoted preview is message content too, so it must not sit on disk in the clear. */
    @Test
    public void theQuotedPreviewIsEncryptedAtRestAsWell() throws Exception {
        repository.saveMessage("reply-1", ALICE, BOB, "yes", 2,
                ChatMessage.Status.SENT, "original-1", BOB, "shall we meet?", true);

        try (Statement stmt = keepAlive.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT reply_to_preview FROM messages")) {
            assertTrue(rs.next());
            String stored = rs.getString("reply_to_preview");
            assertNotEquals("shall we meet?", stored);
            assertFalse(stored.contains("meet"));
        }
    }

    @Test
    public void aMessageWithoutAReplyCarriesNoQuote() {
        repository.saveMessage(ALICE, BOB, "standalone", 1);

        ChatMessage stored = repository.getMessages(ALICE, BOB, 10).get(0);

        assertFalse(stored.hasReply());
        assertNull(stored.getReplyToPreview());
    }

    // ------------------------------------------------------------ key migration

    @Test
    public void migrationRewritesEveryRowUnderTheNewKey() {
        repository.saveMessage(ALICE, BOB, "first", 1);
        repository.saveMessage(BOB, ALICE, "second", 2);
        SecretKey newKey = key((byte) 42);

        assertEquals(2, repository.migrateEncryption(dbKey, newKey));

        List<ChatMessage> underNewKey =
                new MessageRepository(dbPath, newKey).getMessages(ALICE, BOB, 10);
        assertEquals(2, underNewKey.size());
        assertEquals("first", underNewKey.get(0).getContent());
        assertEquals("second", underNewKey.get(1).getContent());
    }

    @Test
    public void migrationCarriesTheQuotedPreviewAcrossToo() {
        repository.saveMessage("reply-1", ALICE, BOB, "yes", 2,
                ChatMessage.Status.SENT, "original-1", BOB, "shall we meet?", true);
        SecretKey newKey = key((byte) 42);

        assertEquals(1, repository.migrateEncryption(dbKey, newKey));

        ChatMessage migrated =
                new MessageRepository(dbPath, newKey).getMessages(ALICE, BOB, 10).get(0);
        assertEquals("shall we meet?", migrated.getReplyToPreview());
    }

    /**
     * A half-applied migration would leave some rows under the old key and some under the new,
     * which nothing could then read in full. It has to be all or nothing.
     */
    @Test
    public void aMigrationUnderTheWrongKeyRollsBackAndLeavesTheDatabaseReadable() {
        repository.saveMessage(ALICE, BOB, "first", 1);
        repository.saveMessage(BOB, ALICE, "second", 2);

        assertEquals(-1, repository.migrateEncryption(key((byte) 77), key((byte) 42)));

        List<ChatMessage> stillThere = repository.getMessages(ALICE, BOB, 10);
        assertEquals(2, stillThere.size());
        assertEquals("first", stillThere.get(0).getContent());
        assertEquals("second", stillThere.get(1).getContent());
    }

    @Test
    public void migratingAnEmptyDatabaseIsAllowed() {
        assertEquals(0, repository.migrateEncryption(dbKey, key((byte) 42)));
    }
}
