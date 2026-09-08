package com.e2eechat.core.session;

/**
 * Thrown when a message could not be encrypted because the session's key has reached its send
 * budget and a fresh handshake has been started in its place.
 *
 * <p>Distinct from the generic failures around it on purpose. Every other reason encryption can
 * fail means something is wrong; this one means the client is renewing itself and the same message
 * will go if it is offered again in a moment. A caller that cannot tell the two apart can only
 * report both as an error or neither — and reporting neither is what used to happen, which left
 * the sender watching their messages disappear with nothing on screen to say why.
 */
public class SessionRenewalRequiredException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String peerId;

    SessionRenewalRequiredException(String peerId) {
        super("The key for this conversation has reached its send limit. A new one is being "
                + "negotiated; send again in a moment.");
        this.peerId = peerId;
    }

    /** The peer whose session is being renewed. */
    public String getPeerId() {
        return peerId;
    }
}
