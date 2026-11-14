package dev.stxt.press;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.stxt.press.config.ConfigException;
import dev.stxt.press.config.ServerFile;

/**
 * The Docker image, without Docker: the {@code server.stxt} of the image is that of {@code server/} with the
 * {@code sed -e} of the {@code Dockerfile}, each of which has to find its line once (or the build of the image fails at
 * its {@code grep}). Java's {@code $} matches before CRLF too, as the {@code sed} does once the {@code Dockerfile} has
 * left the file with LF. The result listens on every address and does not save, and keeps the rest of the folder: the
 * port the image exposes and its health check calls, the warm up, no token, and the folders.
 */
class DockerfileTest {

    private static final Path DOCKERFILE = Path.of("Dockerfile");
    private static final Path SERVER_FILE = Path.of("server", ServerFile.FILE_NAME);
    private static final Pattern SED = Pattern.compile("-e 's/([^/]*)/([^/]*)/'");

    @TempDir
    Path temp;

    /** The text of {@code server.stxt} with each {@code sed} of the {@code Dockerfile} applied, as the image has it. */
    private static String imageServerFile(String dockerfile) throws IOException {
        String text = Files.readString(SERVER_FILE);
        Matcher sed = SED.matcher(dockerfile);
        int count = 0;
        while (sed.find()) {
            Pattern line = Pattern.compile(sed.group(1), Pattern.MULTILINE);
            assertEquals(1, line.matcher(text).results().count(), "server.stxt has once the line of " + sed.group());
            String replacement = sed.group(2).replace("\\t", "\t");
            text = line.matcher(text).replaceAll(Matcher.quoteReplacement(replacement));
            count++;
        }
        assertEquals(2, count, "the sed of the Dockerfile change Host and Save");
        return text;
    }

    @Test
    void theImageListensOnEveryAddressWithoutSaveAndKeepsTheRest() throws IOException, ConfigException {
        String dockerfile = Files.readString(DOCKERFILE);
        String text = imageServerFile(dockerfile);
        // The folders the server.stxt names, empty: what is read here is the configuration, not the folder.
        for (String folder : List.of("resources", "templates", "documents")) {
            Files.createDirectory(temp.resolve(folder));
        }
        Path file = Files.writeString(temp.resolve(ServerFile.FILE_NAME), text);

        ServerFile image = ServerFile.read(file, Map.of());
        ServerFile folder = ServerFile.read(SERVER_FILE.toAbsolutePath(), Map.of());

        assertEquals("0.0.0.0", image.http().host());
        assertFalse(image.http().save());
        assertTrue(folder.http().save(), "the folder saves, for the editor of its machine");
        assertNull(image.http().token(), "the image answers without token, as the README says");
        assertTrue(image.warmUp());
        assertEquals(folder.http().port(), image.http().port());
        assertEquals(temp.resolve("templates"), image.templateFolder());
        assertEquals(temp.resolve("documents"), image.documentFolder());

        int port = image.http().port();
        assertTrue(dockerfile.contains("\nEXPOSE " + port + "\n"), "the image exposes the port of its server.stxt");
        assertTrue(dockerfile.contains("http://127.0.0.1:" + port + "/health"), "the health check calls that port");
    }
}
