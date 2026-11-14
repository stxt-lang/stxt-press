package dev.stxt.press.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A file written whole or not at all: a new one, one replaced, and a write that fails, with a checked or an unchecked
 * exception, which leaves the file as it was and nothing beside it. And the permissions, where there are POSIX ones: a
 * file replaced keeps its own, and a new one has those of any new file, not only its owner's.
 */
class AtomicFileTest {

    @TempDir
    Path dir;

    private List<String> files() throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.map(f -> f.getFileName().toString()).sorted().toList();
        }
    }

    private static AtomicFile.Content text(String text) {
        return out -> out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void writesANewFileAndReplacesOne() throws IOException {
        Path file = dir.resolve("out.txt");

        AtomicFile.write(file, text("first"));
        assertEquals("first", Files.readString(file));

        AtomicFile.write(file, text("second"));
        assertEquals("second", Files.readString(file));
        assertEquals(List.of("out.txt"), files());
    }

    @Test
    void aWriteThatFailsLeavesTheFileAsItWasAndNothingBesideIt() throws IOException {
        Path file = dir.resolve("out.txt");
        Files.writeString(file, "before");
        IOException io = new IOException("The content cannot be written");
        IllegalStateException unchecked = new IllegalStateException("The job failed");

        assertSame(io, assertThrows(IOException.class, () -> AtomicFile.write(file, out -> {
            out.write("half".getBytes(StandardCharsets.UTF_8));
            throw io;
        })));
        assertSame(unchecked, assertThrows(IllegalStateException.class, () -> AtomicFile.write(file, out -> {
            out.write("half".getBytes(StandardCharsets.UTF_8));
            throw unchecked;
        })));

        assertEquals("before", Files.readString(file));
        assertEquals(List.of("out.txt"), files());
    }

    private boolean posix() throws IOException {
        return Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView.class);
    }

    @Test
    void aNewFileHasThePermissionsOfAnyNewFile() throws IOException {
        assumeTrue(posix(), "a file system with POSIX permissions");
        Path reference = Files.createFile(dir.resolve("reference.txt"));
        Path file = dir.resolve("out.txt");

        AtomicFile.write(file, text("new"));

        assertEquals(Files.getPosixFilePermissions(reference), Files.getPosixFilePermissions(file));
    }

    @Test
    void aFileReplacedKeepsItsPermissions() throws IOException {
        assumeTrue(posix(), "a file system with POSIX permissions");
        Path file = dir.resolve("out.txt");
        for (String permissions : List.of("rw-r-----", "rw-r--r--", "rw-rw-r--", "rw-------")) {
            Files.writeString(file, "before");
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));

            AtomicFile.write(file, text("after"));

            assertEquals(permissions, PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
            assertEquals("after", Files.readString(file));
        }
        assertEquals(List.of("out.txt"), files());
    }

    @Test
    void aFileInAFolderThatDoesNotExistIsNotWritten() {
        assertThrows(IOException.class, () -> AtomicFile.write(dir.resolve("nowhere/out.txt"), text("x")));
    }
}
