package com.e2eechat.core.keys;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Optional;

public interface IdentityKeyStore {
    KeyPair loadOrCreateIdentity(char[] passphrase) throws Exception;
    void storePeerKey(String peerId, PublicKey key) throws Exception;
    Optional<PublicKey> getPeerKey(String peerId) throws Exception;
    String fingerprint(PublicKey key) throws Exception;
}
