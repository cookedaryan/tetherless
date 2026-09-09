package com.e2eechat.core.util;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Owner-only permissions on the files that hold key material.
 *
 * <p>POSIX-only, and skipped elsewhere rather than asserted loosely: Windows has no equivalent
 * mode, so there is nothing here that a green result on that platform would actually prove.
 */
public class PrivateFilesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void assumePosix() {
        Assume.assumeTrue("POSIX permissions are not supported on this filesystem",
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    }

    private static void assertOwnerOnly(File file) throws Exception {
        Set<PosixFilePermission> mode = Files.getPosixFilePermissions(file.toPath());
        assertFalse(file + " is group readable", mode.contains(PosixFilePermission.GROUP_READ));
        assertFalse(file + " is world readable", mode.contains(PosixFilePermission.OTHERS_READ));
        assertFalse(file + " is group writable", mode.contains(PosixFilePermission.GROUP_WRITE));
        assertFalse(file + " is world writable", mode.contains(PosixFilePermission.OTHERS_WRITE));
        assertTrue("the owner must still be able to read it",
                mode.contains(PosixFilePermission.OWNER_READ));
    }

    @Test
    public void aRestrictedFileIsReadableOnlyByItsOwner() throws Exception {
        assumePosix();
        File file = tmp.newFile("secret");
        Files.setPosixFilePermissions(file.toPath(),
                PosixFilePermissions.fromString("rw-rw-rw-"));

        PrivateFiles.restrict(file);

        assertOwnerOnly(file);
    }

    @Test
    public void aPrivateDirectoryIsOwnerOnlyAndEnterable() throws Exception {
        assumePosix();
        File dir = new File(tmp.getRoot(), "nested/config");

        PrivateFiles.createPrivateDirectory(dir);

        assertTrue(dir.isDirectory());
        assertOwnerOnly(dir);
        assertTrue("the owner has to be able to enter it",
                Files.getPosixFilePermissions(dir.toPath())
                        .contains(PosixFilePermission.OWNER_EXECUTE));
    }

    /** A file created with these attributes is private from the moment it exists. */
    @Test
    public void aFileCreatedWithTheAttributesIsNeverBrieflyReadable() throws Exception {
        assumePosix();
        File file = new File(tmp.getRoot(), "born-private");

        Files.createFile(file.toPath(), PrivateFiles.ownerOnlyFileAttributes());

        assertOwnerOnly(file);
    }

    @Test
    public void restrictingSomethingAbsentIsHarmless() {
        PrivateFiles.restrict(new File(tmp.getRoot(), "does-not-exist"));
        PrivateFiles.restrict(null);
    }
}
