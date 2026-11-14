package dev.stxt.press.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A file written whole or not at all: a new one, one replaced, and a write that fails, with a checked or an unchecked
 * exception, which leaves the file as it was and nothing beside it.
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

    @Test
    void aFileInAFolderThatDoesNotExistIsNotWritten() {
        assertThrows(IOException.class, () -> AtomicFile.write(dir.resolve("nowhere/out.txt"), text("x")));
    }
}
