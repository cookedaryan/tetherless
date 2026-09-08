package com.e2eechat.desktop;

import com.e2eechat.core.identity.PeerId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Local record of what each peer calls themselves.
 *
 * <p>Peer ids are derived from identity keys and carry no name, so the name a peer asks to be shown
 * as arrives separately in their {@code HELLO} and is remembered here. A peer may change it freely
 * without becoming unreachable, which was the entire point of moving it out of the id.
 *
 * <p><strong>These names are self-asserted and prove nothing.</strong> Anyone may claim any name,
 * including one already in use. The id and the safety number are the only identity that matters, so
 * callers should keep the id visible wherever a name might be relied upon.
 *
 * <p>Thread-safe: the relay reader thread writes names as HELLOs arrive while the event dispatch
 * thread reads them to paint.
 */
public class PeerDirectory {

    private static final Logger logger = LoggerFactory.getLogger(PeerDirectory.class);
    private static final String FILE_NAME = "peer-names.properties";
    private static final String VERIFIED_FILE_NAME = "peer-verification.properties";

    private final File file;
    private final File verifiedFile;
    private final ConcurrentMap<String, String> names = new ConcurrentHashMap<>();
    private final Set<String> verified = ConcurrentHashMap.newKeySet();

    public PeerDirectory(File configDir) {
        this.file = new File(configDir, FILE_NAME);
        this.verifiedFile = new File(configDir, VERIFIED_FILE_NAME);
        load();
        loadVerified();
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (Exception e) {
            logger.warn("Could not read {}", file, e);
            return;
        }
        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key);
            if (value != null && !value.trim().isEmpty()) {
                names.put(key, value.trim());
            }
        }
    }

    /**
     * Records the name a peer asked to be shown as. No-op for a blank name, so a peer that sends
     * none does not erase one already known.
     */
    public void setName(String peerId, String displayName) {
        if (peerId == null || displayName == null || displayName.trim().isEmpty()) {
            return;
        }
        String previous = names.put(peerId, displayName.trim());
        if (!displayName.trim().equals(previous)) {
            persist();
        }
    }

    /**
     * The label to show for a peer.
     *
     * <p>Falls back to the short form of the id when no name is known, so a peer is always
     * identifiable by something. Ids stored before the format change are shown as their old
     * {@code name@fingerprint} text rather than being mangled.
     */
    public String nameFor(String peerId) {
        if (peerId == null) {
            return "";
        }
        String known = names.get(peerId);
        if (known != null) {
            return known;
        }
        if (PeerId.isLegacyFormat(peerId)) {
            return peerId.substring(0, peerId.indexOf('@'));
        }
        return PeerId.shortForm(peerId);
    }

    /** True when the peer has actually told us a name, rather than us falling back to their id. */
    public boolean hasName(String peerId) {
        return peerId != null && names.containsKey(peerId);
    }

    private void loadVerified() {
        if (!verifiedFile.exists()) {
            return;
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(verifiedFile)) {
            props.load(in);
        } catch (Exception e) {
            logger.warn("Could not read {}", verifiedFile, e);
            return;
        }
        for (String key : props.stringPropertyNames()) {
            if (Boolean.parseBoolean(props.getProperty(key))) {
                verified.add(key);
            }
        }
    }

    /**
     * Records that the local user has compared safety numbers with this peer and they matched.
     *
     * <p>This is the user's own judgement, not anything the peer asserted, which is why it lives in
     * its own file rather than beside the names peers claim for themselves.
     */
    public synchronized void setVerified(String peerId, boolean isVerified) {
        if (peerId == null) {
            return;
        }
        boolean changed = isVerified ? verified.add(peerId) : verified.remove(peerId);
        if (changed) {
            persistVerified();
        }
    }

    /** True when the user has marked this peer verified. */
    public boolean isVerified(String peerId) {
        return peerId != null && verified.contains(peerId);
    }

    private void persistVerified() {
        Properties props = new Properties();
        for (String peerId : verified) {
            props.setProperty(peerId, "true");
        }
        try (FileOutputStream out = new FileOutputStream(verifiedFile)) {
            props.store(out, "Peers whose safety number this user has compared and accepted.");
        } catch (Exception e) {
            logger.error("Could not persist peer verification to {}", verifiedFile, e);
        }
    }

    private void persist() {
        Properties props = new Properties();
        props.putAll(names);
        try (FileOutputStream out = new FileOutputStream(file)) {
            props.store(out, "Display names peers have asked to be shown as. Self-asserted; not proof of identity.");
        } catch (Exception e) {
            logger.error("Could not persist peer names to {}", file, e);
        }
    }
}
