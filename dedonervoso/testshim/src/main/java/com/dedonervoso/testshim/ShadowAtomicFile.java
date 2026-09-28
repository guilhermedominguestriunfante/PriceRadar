package com.dedonervoso.testshim;

import android.util.AtomicFile;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Test hosts only. {@link AtomicFile} commits a write by renaming the new file over the old one
 * with {@code java.io.File.renameTo}, which on Windows refuses to replace an existing file: every
 * save after the first was silently dropped when the tests ran there. This does the same rename
 * with {@code Files.move(REPLACE_EXISTING)}, which replaces the target on every host, like
 * rename(2) does on a device. Registered in the tests' robolectric.properties.
 */
@Implements(AtomicFile.class)
public class ShadowAtomicFile {
    @Implementation
    protected static void rename(File source, File target) {
        try {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Same outcome as the framework's: the failure is logged and the old file stays.
            System.err.println("AtomicFile: failed to rename " + source + " to " + target + ": " + e);
        }
    }
}
