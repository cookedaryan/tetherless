package com.e2eechat.server;

import com.e2eechat.core.identity.PeerId;
import com.e2eechat.core.models.Message;
import com.e2eechat.core.protocol.HelloPayload;
import com.e2eechat.core.protocol.SignatureVerifier;

import java.security.PublicKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether an opening {@code HELLO} may claim the id it names.
 *
 * <p>Registration used to be taken at face value: the relay read {@code senderId} off an unsigned
 * frame and handed out that address. Since a peer id is public - it is what you give someone so
 * they can message you - anyone could connect, claim somebody else's, and have the real owner
 * refused with {@code ID_TAKEN} on their next login. One socket per victim was enough to lock a
 * whole relay's users out of their own accounts.
 *
 * <p>A peer id is the SHA-256 of an identity public key, so the claim is checkable: the HELLO
 * carries the key it was derived from, and is signed with the matching private key. Presenting the
 * key proves which id is being claimed; the signature proves the claimant holds it.
 *
 * <p>The relay learns nothing new from this. It already saw the id, and the public key is public
 * by definition - what changes is only that it now has to be produced rather than asserted.
 */
final class RegistrationAuthenticator {

    /**
     * How far a registration's timestamp may be from ours.
     *
     * <p>Tighter than the five minutes clients allow each other, because there is no store and
     * forward here: a registration is written and read within one round trip, and the window is
     * how long a captured frame stays usable.
     */
    static final long MAX_SKEW_MS = 60_000;

    /** Beyond this many remembered registrations, expired entries are swept before adding more. */
    private static final int SWEEP_THRESHOLD = 4096;

    /**
     * Message ids of registrations already accepted, against the moment they stop being replayable.
     *
     * <p>Freshness alone leaves a minute in which a captured HELLO can be presented again -
     * against this relay, or against a different one, since the signature says nothing about which
     * relay it was meant for. Remembering the id for as long as the frame is fresh closes that:
     * the second use of one is refused. Entries cannot accumulate, because anything older than the
     * skew window would be rejected on its timestamp anyway.
     */
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    /** Why a registration was refused, or {@code null} when it was accepted. */
    String reject(Message hello) {
        String claimedId = hello.getSenderId();
        if (claimedId == null || !PeerId.isValid(claimedId)) {
            return "BAD_PEER_ID";
        }

        long now = System.currentTimeMillis();
        if (Math.abs(now - hello.getTimestamp()) > MAX_SKEW_MS) {
            return "STALE_REGISTRATION";
        }

        PublicKey presented;
        try {
            presented = HelloPayload.decode(hello.getPayload()).getPublicKey();
        } catch (Exception e) {
            return "NO_IDENTITY_KEY";
        }

        // The id is a hash of the key, so this is what ties the claim to the key being presented.
        // Without it a squatter could sign a HELLO with their own key and name any id they liked.
        if (!PeerId.of(presented).equals(claimedId)) {
            return "ID_DOES_NOT_MATCH_KEY";
        }

        // Demanded explicitly, because SignatureVerifier exempts HELLO: on the client-to-client
        // path a HELLO is what introduces the key, so there is nothing to check it against and it
        // travels unsigned. Registration is the opposite case - the key is right there in the
        // frame - and leaning on the shared policy here would have accepted an unsigned one.
        // Matching the id would then be the whole test, and a peer id is a hash of a public key
        // that anybody can hold, so the squatting this class exists to stop would still work.
        if (hello.getSignature() == null || hello.getSignature().length == 0) {
            return "BAD_SIGNATURE";
        }

        if (SignatureVerifier.verify(hello, presented) != SignatureVerifier.VerificationResult.VALID) {
            return "BAD_SIGNATURE";
        }

        if (!remember(hello.getMessageId(), now)) {
            return "REPLAYED_REGISTRATION";
        }
        return null;
    }

    /** Records a registration id, returning false if it has already been used. */
    private boolean remember(String messageId, long now) {
        if (messageId == null || messageId.isEmpty()) {
            return false;
        }
        if (seen.size() > SWEEP_THRESHOLD) {
            seen.values().removeIf(expiry -> expiry <= now);
        }
        Long existing = seen.putIfAbsent(messageId, now + MAX_SKEW_MS);
        if (existing == null) {
            return true;
        }
        if (existing <= now) {
            // Expired, and only still here because no sweep has run. Reclaim it.
            seen.put(messageId, now + MAX_SKEW_MS);
            return true;
        }
        return false;
    }
}
