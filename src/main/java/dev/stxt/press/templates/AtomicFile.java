package dev.stxt.press.templates;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * A file written whole or not at all: the content goes to a file beside it, which then takes its place. A write that
 * fails leaves the file that was there, if any, as it was, and no file beside it. It is how the service writes the files
 * of a template or a document ({@link Templates#save}) and the output of a command ({@code -o}).
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
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".tmp");
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                content.writeTo(out);
            }
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
}
