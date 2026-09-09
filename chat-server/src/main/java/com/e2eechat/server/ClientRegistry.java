package com.e2eechat.server;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.util.Redact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

public class ClientRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ClientRegistry.class);
    private final ConcurrentHashMap<String, ClientSession> clients = new ConcurrentHashMap<>();

    /**
     * Shared by every session, because the replay memory it keeps only works relay-wide: a
     * registration refused on one connection must stay refused when it is presented on another.
     */
    private final RegistrationAuthenticator authenticator = new RegistrationAuthenticator();

    /**
     * Checks that an opening {@code HELLO} may claim the id it names.
     *
     * <p>Lives here because owning the id space is what this class does: {@link #register} decides
     * who holds an address, and this decides who is allowed to ask.
     *
     * @return the reason to refuse, or {@code null} when the claim is good
     */
    public String rejectRegistration(Message hello) {
        return authenticator.reject(hello);
    }

    /**
     * Registers a new client session.
     * @param clientId The ID of the client to register.
     * @param session The session to register.
     * @return true if successfully registered, false if another session is already registered with this ID.
     */
    public boolean register(String clientId, ClientSession session) {
        ClientSession existing = clients.putIfAbsent(clientId, session);
        if (existing == null) {
            logger.info("Client registered: {}", Redact.id(clientId));
            return true;
        } else {
            logger.warn("Client registration rejected (duplicate ID): {}", Redact.id(clientId));
            return false;
        }
    }

    /**
     * Unregisters a client session, but only if the currently registered session matches the expected one.
     * This prevents a dying old session from unregistering a newly reconnected session.
     * @param clientId The ID of the client.
     * @param expectedSession The session that is requesting to be unregistered.
     */
    public void unregister(String clientId, ClientSession expectedSession) {
        if (clientId == null) {
            return;
        }
        boolean removed = clients.remove(clientId, expectedSession);
        if (removed) {
            logger.info("Client unregistered: {}", Redact.id(clientId));
        }
    }

    /**
     * Looks up a client session by ID.
     */
    public ClientSession lookup(String clientId) {
        if (clientId == null) {
            return null;
        }
        return clients.get(clientId);
    }

    /**
     * Returns all currently active sessions.
     */
    public Collection<ClientSession> getAllSessions() {
        return clients.values();
    }
}
