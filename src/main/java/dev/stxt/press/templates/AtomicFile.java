package dev.stxt.press.templates;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.SecureRandom;
import java.util.Objects;

/**
 * A file written whole or not at all: the content goes to a file beside it, which then takes its place. A write that
 * fails leaves the file that was there, if any, as it was, and no file beside it. A file that was there keeps its
 * permissions, and a new one has those of any file the process creates. It is how the service writes the files of a
 * template or a document ({@link Templates#save}) and the output of a command ({@code -o}).
 */
public final class AtomicFile {

    /** What writes the content of the file. */
    @FunctionalInterface
    public interface Content {
        /**
         * Writes the content to {@code out}, which it does not close.
         *
         * @throws IOException if it cannot be written
         */
        void writeTo(OutputStream out) throws IOException;
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private AtomicFile() {
    }

    /**
     * Writes {@code target} with {@code content}: to a hidden file in its folder ({@code .<name>.<random>.tmp}), moved
     * over {@code target} once it is whole, in one step if the file system can do it. If {@code content} or the move
     * fails, the hidden file is deleted and the exception of {@code content}, an unchecked one too, goes on.
     *
     * @param target the file; its folder has to exist; not {@code null}
     * @param content what writes it; not {@code null}
     * @throws IOException if the file cannot be written, or {@code content} fails with one
     */
    public static void write(Path target, Content content) throws IOException {
        Objects.requireNonNull(content, "content");
        Path temporary = createBeside(target);
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                content.writeTo(out);
            }
            keepPermissions(target, temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException deleting) {
                // The error that matters is the one of the write.
                e.addSuppressed(deleting);
            }
            throw e;
        }
    }

    /**
     * Creates the hidden file beside {@code target}, with a name that no file has. Not with
     * {@link Files#createTempFile}, whose file only its owner can read: moved over {@code target}, it would take that
     * to the file, and a server or a container that runs as another user could not read it any more.
     */
    private static Path createBeside(Path target) throws IOException {
        while (true) {
            Path temporary = target.resolveSibling(
                    "." + target.getFileName() + "." + Long.toUnsignedString(RANDOM.nextLong(), 36) + ".tmp");
            try {
                // It fails if the name exists, a symbolic link too, which it does not follow.
                return Files.createFile(temporary);
            } catch (FileAlreadyExistsException e) {
                // Another name.
            }
        }
    }

    /** Gives {@code temporary} the POSIX permissions of {@code target}, if it exists and the file system has them. */
    private static void keepPermissions(Path target, Path temporary) throws IOException {
        if (Files.exists(target)
                && Files.getFileStore(temporary).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(target));
        }
    }
}
