package com.e2eechat.server;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class ClientRegistryTest {

    private ClientRegistry registry;
    private ClientSession session1;
    private ClientSession session2;

    @Before
    public void setup() {
        registry = new ClientRegistry();
        session1 = new ClientSession(null, registry);
        session2 = new ClientSession(null, registry);
    }

    @Test
    public void testRegisterAndLookup() {
        assertTrue(registry.register("alice", session1));
        assertEquals(session1, registry.lookup("alice"));
    }

    @Test
    public void testDuplicateRegistrationRejected() {
        assertTrue(registry.register("alice", session1));
        assertFalse(registry.register("alice", session2));
        
        // Lookup should still return the first session
        assertEquals(session1, registry.lookup("alice"));
    }

    @Test
    public void testUnregisterExactSession() {
        registry.register("alice", session1);
        
        // Try to unregister with a different session (e.g., reconnect race)
        registry.unregister("alice", session2);
        assertNotNull(registry.lookup("alice")); // Should not be removed
        
        // Unregister with the correct session
        registry.unregister("alice", session1);
        assertNull(registry.lookup("alice")); // Should be removed
    }

    @Test
    public void testGetAllSessions() {
        registry.register("alice", session1);
        registry.register("bob", session2);
        
        assertEquals(2, registry.getAllSessions().size());
        assertTrue(registry.getAllSessions().contains(session1));
        assertTrue(registry.getAllSessions().contains(session2));
    }
}
