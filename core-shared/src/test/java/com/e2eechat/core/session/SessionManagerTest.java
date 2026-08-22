package com.e2eechat.core.session;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.MessageSigner;
import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.Assert.*;

public class SessionManagerTest {

    @Test
    public void testSimultaneousInitiationRace() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair alicePair = kpg.generateKeyPair();
        KeyPair bobPair = kpg.generateKeyPair();

        SessionManager aliceMgr = new SessionManager("alice", id -> bobPair.getPublic());
        SessionManager bobMgr = new SessionManager("bob", id -> alicePair.getPublic());
        
        Session aliceSession = aliceMgr.getSession("bob");
        aliceSession.setState(Session.State.HANDSHAKE_SENT);
        
        Session bobSession = bobMgr.getSession("alice");
        bobSession.setState(Session.State.HANDSHAKE_SENT);
        
        // Alice receives Bob's INIT
        Message bobInit = new MessageBuilder()
                .setType(MessageType.KEY_EXCHANGE_INIT)
                .setSenderId("bob")
                .setReceiverId("alice")
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
        Message signedBobInit = MessageSigner.sign(bobInit, bobPair.getPrivate());
        
        SessionManager.ProcessResult aliceRes = aliceMgr.onMessage(signedBobInit);
        
        // Bob receives Alice's INIT
        Message aliceInit = new MessageBuilder()
                .setType(MessageType.KEY_EXCHANGE_INIT)
                .setSenderId("alice")
                .setReceiverId("bob")
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
        Message signedAliceInit = MessageSigner.sign(aliceInit, alicePair.getPrivate());
        
        SessionManager.ProcessResult bobRes = bobMgr.onMessage(signedAliceInit);
        
        assertNotEquals(aliceRes.outcome, bobRes.outcome);
        assertTrue(aliceRes.outcome == SessionManager.Outcome.HANDSHAKE_PROCEED || aliceRes.outcome == SessionManager.Outcome.HANDSHAKE_DROPPED);
    }
}
