package com.e2eechat.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Creates files and directories only their owner can read.
 *
 * <p>Identity keystores and pinned peer keys were written with whatever the process umask happened
 * to be, which on a typical Linux or macOS account is world-readable. On a shared machine that
 * hands any other local account the encrypted identity keystore - to attack the passphrase offline,
 * at leisure - and the peer store, which is a list of everyone the user talks to. Neither is
 * protected by the end-to-end encryption; both sit outside it, on disk.
 *
 * <p>Permissions are applied at creation where the filesystem supports it, rather than afterwards,
 * because the gap between creating a file and tightening it is a window in which the contents are
 * already there and readable.
 *
 * <p>Windows has no POSIX permissions, and neither does every mounted filesystem. There the
 * fallback is {@link File#setReadable(boolean, boolean)} and friends, which map onto whatever the
 * platform can express; it is weaker, and it is better than nothing. A failure to restrict is
 * logged rather than thrown - refusing to start because the filesystem cannot express a mode would
 * make the client unusable in exchange for no security at all.
 */
public final class PrivateFiles {

    private static final Logger LOG = LoggerFactory.getLogger(PrivateFiles.class);

    /** Owner read and write, nothing for anybody else. */
    private static final String FILE_MODE = "rw-------";

    /** Owner needs execute on a directory to enter it. */
    private static final String DIRECTORY_MODE = "rwx------";

    private PrivateFiles() {
    }

    /** True when this filesystem can express POSIX modes at all. */
    private static boolean posixSupported() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /**
     * Attributes to pass to a create call so a file is born private.
     *
     * @return an empty array on a filesystem without POSIX permissions, where the caller should
     *         follow up with {@link #restrict(File)}
     */
    public static FileAttribute<?>[] ownerOnlyFileAttributes() {
        if (!posixSupported()) {
            return new FileAttribute<?>[0];
        }
        return new FileAttribute<?>[]{
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(FILE_MODE))};
    }

    /** Creates {@code directory} and any missing parents, owner-only. No-op if it exists. */
    public static void createPrivateDirectory(File directory) throws IOException {
        if (directory == null || directory.isDirectory()) {
            return;
        }
        if (posixSupported()) {
            Set<PosixFilePermission> mode = PosixFilePermissions.fromString(DIRECTORY_MODE);
            Files.createDirectories(directory.toPath(),
                    PosixFilePermissions.asFileAttribute(mode));
            return;
        }
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create the directory at " + directory);
        }
        restrict(directory);
    }

    /**
     * Narrows an existing file or directory to its owner.
     *
     * <p>Best effort by design: see the class note on why this does not throw.
     */
    public static void restrict(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        Path path = file.toPath();
        if (posixSupported()) {
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                        file.isDirectory() ? DIRECTORY_MODE : FILE_MODE));
                return;
            } catch (IOException | UnsupportedOperationException e) {
                LOG.warn("Could not restrict {} to its owner", file.getAbsolutePath(), e);
                return;
            }
        }

        // Order matters: strip access from everyone first, then grant it back to the owner alone.
        // Granting first and revoking afterwards leaves the file briefly readable.
        boolean ok = file.setReadable(false, false);
        ok &= file.setWritable(false, false);
        ok &= file.setReadable(true, true);
        ok &= file.setWritable(true, true);
        if (file.isDirectory()) {
            ok &= file.setExecutable(true, true);
        }
        if (!ok) {
            LOG.debug("The filesystem holding {} would not accept owner-only permissions",
                    file.getAbsolutePath());
        }
    }
}
