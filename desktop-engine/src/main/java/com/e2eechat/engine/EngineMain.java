package com.e2eechat.engine;

import com.e2eechat.desktop.DatabaseHelper;
import com.e2eechat.desktop.DesktopConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The engine as a child process: newline-delimited JSON in on stdin, out on stdout.
 *
 * <h2>Why a pipe and not a port</h2>
 * The passphrase, every plaintext message and the identity all cross this channel. A loopback
 * socket is reachable by every other process running as this user; a pipe to a child process is
 * private to its parent by construction. There is deliberately no network listener here.
 *
 * <h2>stdout belongs to the protocol</h2>
 * One stray {@code System.out.println} anywhere in this process - in this code, in the client, in a
 * dependency - would corrupt the frame stream and desynchronise the caller. So the real stdout is
 * captured before anything else runs and {@code System.out} is pointed at stderr. Everything that
 * thinks it is printing to the console ends up on stderr, where it is harmless.
 */
public final class EngineMain {

    private static final Gson GSON = new Gson();
    private static final Type STRING_OBJECT_MAP =
            new TypeToken<Map<String, Object>>() { }.getType();

    /** The real stdout, captured before System.out is diverted. Frames go here and nowhere else. */
    private final PrintStream frames;
    private final Object writeLock = new Object();
    private volatile boolean running = true;

    private EngineMain(PrintStream frames) {
        this.frames = frames;
    }

    public static void main(String[] args) throws Exception {
        // Bound to the descriptor rather than to System.out, so that diverting System.out below -
        // or anyone reassigning it later - cannot redirect the frame stream.
        PrintStream frames = new PrintStream(new FileOutputStream(FileDescriptor.out),
                true, StandardCharsets.UTF_8.name());
        System.setOut(System.err);
        new EngineMain(frames).run(args);
    }

    private void run(String[] args) throws Exception {
        File configDir = resolveConfigDir();
        if (!configDir.exists() && !configDir.mkdirs()) {
            throw new IOException("Could not create the config directory: " + configDir);
        }
        String dbPath = new File(configDir, "chat.db").getAbsolutePath();
        DatabaseHelper.initializeDatabase(dbPath);

        DesktopConfig config = DesktopConfig.load(configDir, args);
        config.applyTlsProperties();

        Engine engine = new Engine(configDir, dbPath, config, new Engine.EventSink() {
            @Override
            public void emit(String event, Map<String, Object> payload) {
                Map<String, Object> frame = new LinkedHashMap<String, Object>();
                frame.put("event", event);
                frame.put("payload", payload);
                write(frame);
            }
        });

        // Tells the caller the pipe is live before it sends anything, so a slow JVM start is
        // distinguishable from a crash.
        Map<String, Object> ready = new LinkedHashMap<String, Object>();
        ready.put("event", "ready");
        ready.put("payload", engineReady(engine));
        write(ready);

        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while (running && (line = in.readLine()) != null) {
            if (!line.trim().isEmpty()) {
                dispatch(engine, line);
            }
        }
    }

    private static Map<String, Object> engineReady(Engine engine) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("identityExists", Boolean.valueOf(engine.identityExists()));
        return out;
    }

    private void dispatch(Engine engine, String line) {
        String id = null;
        try {
            JsonObject root = JsonParser.parseString(line).getAsJsonObject();
            id = root.has("id") ? root.get("id").getAsString() : null;
            String command = root.has("cmd") ? root.get("cmd").getAsString() : null;
            if (command == null) {
                respondError(id, "missing_command", "No cmd in frame");
                return;
            }
            Map<String, Object> payload = root.has("payload") && root.get("payload").isJsonObject()
                    ? GSON.<Map<String, Object>>fromJson(root.getAsJsonObject("payload"),
                            STRING_OBJECT_MAP)
                    : new LinkedHashMap<String, Object>();

            Map<String, Object> result = engine.handle(command, payload);
            if ("shutdown".equals(command)) {
                running = false;
            }
            Map<String, Object> frame = new LinkedHashMap<String, Object>();
            frame.put("id", id);
            frame.put("ok", Boolean.TRUE);
            frame.put("result", result);
            write(frame);
        } catch (JsonParseException | IllegalStateException e) {
            respondError(id, "bad_frame", "Could not parse the frame");
        } catch (Engine.CommandException e) {
            respondError(id, e.code(), e.getMessage());
        } catch (Exception e) {
            // Anything unexpected is reported rather than killing the loop: the caller gets an
            // error it can show, and the next command still works.
            respondError(id, "engine_error", String.valueOf(e.getMessage()));
        }
    }

    private void respondError(String id, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("id", id);
        frame.put("ok", Boolean.FALSE);
        frame.put("error", error);
        write(frame);
    }

    /**
     * Writes one frame, one line.
     *
     * <p>Synchronised because events arrive on the transport's reader thread while responses are
     * written by the command loop; two interleaved writes would produce a line neither side can
     * parse.
     */
    private void write(Map<String, Object> frame) {
        String json = GSON.toJson(frame);
        synchronized (writeLock) {
            frames.println(json);
            frames.flush();
        }
    }

    private static File resolveConfigDir() {
        String configured = System.getProperty("tetherless.config.dir");
        if (configured == null || configured.trim().isEmpty()) {
            return new File(System.getProperty("user.home"), ".tetherless");
        }
        String trimmed = configured.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 1) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return new File(trimmed);
    }
}
