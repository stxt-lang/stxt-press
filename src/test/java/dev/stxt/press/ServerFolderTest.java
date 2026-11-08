package dev.stxt.press;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import dev.stxt.press.config.ConfigException;
import dev.stxt.press.config.ServerFile;
import dev.stxt.press.templates.Template;
import dev.stxt.press.templates.Templates;

/**
 * The server folder of the project ({@code server/}), the start of a project and the one of the Docker image: its
 * {@code server.stxt} is valid, every template converts with its sample data and the resources of the folder, and the
 * fonts and the images are the same as those of {@code ../stxt-pdf/resources}.
 */
class ServerFolderTest {

    private static final Path FOLDER = Path.of("server").toAbsolutePath();
    private static final Path STXT_PDF = Path.of("..", "stxt-pdf", "resources").toAbsolutePath().normalize();

    private static ServerFile server() throws ConfigException, IOException {
        return ServerFile.read(FOLDER.resolve(ServerFile.FILE_NAME), Map.of());
    }

    @Test
    void serverFileNamesTheFoldersOfTheFolder() throws ConfigException, IOException {
        ServerFile server = server();

        assertEquals(FOLDER.resolve("resources"), server.resources().root());
        assertEquals(FOLDER.resolve("templates"), server.templateFolder());
        assertEquals("127.0.0.1", server.http().host());
        assertEquals(8080, server.http().port());
    }

    @TestFactory
    Stream<DynamicTest> everyTemplateConvertsWithItsSampleData() throws ConfigException, IOException {
        ServerFile server = server();
        Press press = new Press(server.converter());
        Templates templates = new Templates(server.templateFolder());
        List<Templates.Entry> entries = templates.list();
        assertFalse(entries.isEmpty(), "no templates in server/templates/");
        return entries.stream().map(entry -> DynamicTest.dynamicTest(entry.path(), () -> {
            Template template = templates.get(entry.path());
            assertNotNull(template.data(), entry.path() + " has no sample data");

            ByteArrayOutputStream pdf = new ByteArrayOutputStream();
            press.pdf(template.job(server.http().templateOptions()), pdf);

            assertEquals("%PDF", new String(pdf.toByteArray(), 0, 4, StandardCharsets.US_ASCII));
        }));
    }

    @Test
    void fontsAreThoseOfStxtPdf() throws IOException {
        assertSameFiles("fonts");
    }

    @Test
    void imagesAreThoseOfTheExamplesOfStxtPdf() throws IOException {
        assertSameFiles("images/examples");
    }

    /** The files under {@code folder} of the resources of the server and of {@code stxt-pdf}: the same, byte by byte. */
    private static void assertSameFiles(String folder) throws IOException {
        Path ours = FOLDER.resolve("resources").resolve(folder);
        Path theirs = STXT_PDF.resolve(folder);
        assertTrue(Files.isDirectory(theirs), theirs + " is needed");
        List<Path> files = files(ours);
        assertEquals(files(theirs), files, "the files of server/resources/" + folder);
        for (Path file : files) {
            assertArrayEquals(Files.readAllBytes(theirs.resolve(file)), Files.readAllBytes(ours.resolve(file)),
                    "server/resources/" + folder + "/" + file + " is not the one of stxt-pdf");
        }
    }

    private static List<Path> files(Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            return walk.filter(Files::isRegularFile).map(folder::relativize).sorted().collect(Collectors.toList());
        }
    }
}
