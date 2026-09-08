package com.e2eechat.desktop;

import com.e2eechat.core.build.BuildInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks whether a newer release exists, and says so if one does.
 *
 * <p>Nothing here downloads or installs anything. The client is not code signed, so an updater that
 * fetched and ran a binary would be asking users to trust an unverifiable download - worse than no
 * updater at all. This tells the user a version exists and points at the release page.
 *
 * <h2>What this discloses</h2>
 * A check is an HTTPS request to GitHub carrying no identifier of its own, but it necessarily tells
 * GitHub - and anyone watching the network, through the destination address and the TLS server name
 * - that this address runs Tetherless and roughly when it was started. For a client whose point is
 * to keep the relay from learning who talks to whom, that is worth stating plainly rather than
 * burying. It is off with {@code updates=false} in {@code config.properties}, or
 * {@code -Dtetherless.updates=false}. See the metadata section of {@code docs/security.md}.
 *
 * <h2>Failure is silence</h2>
 * No network, a proxy in the way, a rate limit, an unparseable body, a version string that makes no
 * sense: all of them end the check with nothing shown. An update notice is a convenience, and a
 * convenience must never produce an error dialog, still less block startup.
 */
public final class UpdateChecker {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateChecker.class);

    /** System property naming the repository to ask about, as {@code owner/name}. */
    public static final String REPOSITORY_PROPERTY = "tetherless.updates.repository";

    /** System property, {@code false} to disable the check. */
    public static final String ENABLED_PROPERTY = "tetherless.updates";

    private static final String DEFAULT_REPOSITORY = "cookedaryan/tetherless";

    /** Deliberately short: this runs at startup and must never hold anything up. */
    private static final int TIMEOUT_MILLIS = 5000;

    /** A release body is small; anything larger is not one and is not worth reading. */
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private static final Pattern TAG_NAME = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");

    /** Where the release feed comes from. Exists so a test needs no network. */
    public interface ReleaseFeed {
        /** The JSON body of the latest-release endpoint. */
        String fetch() throws IOException;
    }

    /** A release newer than the one running. */
    public static final class Update {
        private final String version;
        private final String url;

        Update(String version, String url) {
            this.version = version;
            this.url = url;
        }

        /** The released version, without any {@code v} prefix the tag carried. */
        public String version() {
            return version;
        }

        /** The release page to open. */
        public String url() {
            return url;
        }
    }

    private final String currentVersion;
    private final String repository;
    private final ReleaseFeed feed;

    /** A checker pointed at the configured repository over the network. */
    public UpdateChecker() {
        this(BuildInfo.version(), repositoryFromConfiguration(), null);
    }

    UpdateChecker(String currentVersion, String repository, ReleaseFeed feed) {
        this.currentVersion = currentVersion;
        this.repository = repository;
        this.feed = feed == null ? () -> get(latestReleaseUrl(repository)) : feed;
    }

    /**
     * Runs the check away from the caller's thread and reports a newer release if there is one.
     *
     * <p>The callback runs on the Swing event thread, because its only caller updates a window.
     *
     * @param onUpdateAvailable invoked at most once, and only when something newer exists
     */
    public void checkInBackground(Consumer<Update> onUpdateAvailable) {
        if (!isEnabled()) {
            LOG.debug("Update checks are switched off");
            return;
        }
        Thread thread = new Thread(() -> {
            Optional<Update> update = check();
            update.ifPresent(found -> javax.swing.SwingUtilities.invokeLater(
                    () -> onUpdateAvailable.accept(found)));
        }, "Update-Check");
        // Daemon: a slow or hung request must not keep the process alive after the window closes.
        thread.setDaemon(true);
        thread.start();
    }

    /** The check itself. Returns empty for every failure, and for an up-to-date client. */
    Optional<Update> check() {
        if (!isComparable(currentVersion)) {
            LOG.debug("No usable version stamp; skipping the update check");
            return Optional.empty();
        }
        try {
            Optional<String> tag = parseTagName(feed.fetch());
            if (!tag.isPresent()) {
                return Optional.empty();
            }
            String candidate = stripTagPrefix(tag.get());
            if (!isNewer(candidate, currentVersion)) {
                return Optional.empty();
            }
            return Optional.of(new Update(candidate, releasePageUrl(repository, tag.get())));
        } catch (Exception e) {
            // Deliberately swallowed: see the class comment.
            LOG.debug("Update check did not complete: {}", e.toString());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------ configuration

    static boolean isEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY));
    }

    private static String repositoryFromConfiguration() {
        String configured = System.getProperty(REPOSITORY_PROPERTY);
        return configured == null || configured.trim().isEmpty()
                ? DEFAULT_REPOSITORY : configured.trim();
    }

    static String latestReleaseUrl(String repository) {
        return "https://api.github.com/repos/" + repository + "/releases/latest";
    }

    /**
     * The page a user is sent to.
     *
     * <p>Built from the repository and tag rather than read out of the response. The body carries
     * several {@code html_url} fields - the release has one, so does its author - and picking the
     * wrong one would send a user somewhere unexpected from a link the app vouched for.
     */
    static String releasePageUrl(String repository, String tag) {
        return "https://github.com/" + repository + "/releases/tag/" + tag;
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Pulls {@code tag_name} out of a release body.
     *
     * <p>A regular expression rather than a JSON parser, because one field is wanted and adding a
     * JSON dependency to the client to read it would be a poor trade.
     */
    static Optional<String> parseTagName(String json) {
        if (json == null) {
            return Optional.empty();
        }
        Matcher matcher = TAG_NAME.matcher(json);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String tag = matcher.group(1).trim();
        return tag.isEmpty() ? Optional.empty() : Optional.of(tag);
    }

    static String stripTagPrefix(String tag) {
        String trimmed = tag.trim();
        return trimmed.startsWith("v") || trimmed.startsWith("V") ? trimmed.substring(1) : trimmed;
    }

    /** True only when both versions parse and {@code candidate} is strictly the greater. */
    static boolean isNewer(String candidate, String current) {
        if (!isComparable(candidate) || !isComparable(current)) {
            return false;
        }
        String candidateCore = core(candidate);
        String currentCore = core(current);

        int comparison = compareNumeric(candidateCore, currentCore);
        if (comparison != 0) {
            return comparison > 0;
        }
        // Same numbers. A pre-release - 1.0.0-SNAPSHOT, 2.0.0-rc1 - precedes the release it leads
        // to, so a development build sees the matching release as newer, and a release never
        // offers a user a pre-release of the version they already run.
        boolean candidateIsPreRelease = candidate.indexOf('-') >= 0;
        boolean currentIsPreRelease = current.indexOf('-') >= 0;
        return currentIsPreRelease && !candidateIsPreRelease;
    }

    /** True when a version begins with something that can be compared as numbers. */
    static boolean isComparable(String version) {
        if (version == null || version.trim().isEmpty()) {
            return false;
        }
        String core = core(stripTagPrefix(version));
        if (core.isEmpty()) {
            return false;
        }
        for (String part : core.split("\\.", -1)) {
            if (part.isEmpty()) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Everything before the first hyphen: the dotted numbers. */
    private static String core(String version) {
        String stripped = stripTagPrefix(version).trim();
        int hyphen = stripped.indexOf('-');
        return hyphen < 0 ? stripped : stripped.substring(0, hyphen);
    }

    /** Compares dotted numbers, treating a missing component as zero, so 1.2 equals 1.2.0. */
    private static int compareNumeric(String left, String right) {
        String[] leftParts = left.split("\\.", -1);
        String[] rightParts = right.split("\\.", -1);
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            long l = i < leftParts.length ? Long.parseLong(leftParts[i]) : 0;
            long r = i < rightParts.length ? Long.parseLong(rightParts[i]) : 0;
            if (l != r) {
                return l < r ? -1 : 1;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ network

    private static String get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Tetherless");

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("update endpoint answered " + connection.getResponseCode());
            }
            try (InputStream in = connection.getInputStream()) {
                return read(in);
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Reads at most {@link #MAX_RESPONSE_BYTES}, so a hostile or broken endpoint cannot fill the heap. */
    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = in.read(buffer)) != -1) {
            total += count;
            if (total > MAX_RESPONSE_BYTES) {
                throw new IOException("update endpoint returned more than expected");
            }
            out.write(buffer, 0, count);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
