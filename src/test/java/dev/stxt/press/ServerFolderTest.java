package dev.stxt.press;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

import dev.stxt.pdf.tree.DocumentSchema;
import dev.stxt.press.config.ConfigException;
import dev.stxt.press.config.ServerFile;
import dev.stxt.press.templates.Template;
import dev.stxt.press.templates.Templates;

/**
 * The server folder of the project ({@code server/}), the start of a project and the one of the Docker image: its
 * {@code server.stxt} is valid, every template converts with its sample data and the resources of the folder, every
 * example of {@code stxt-pdf} has its template, which with its sample data gives the pages of the example, the complete
 * examples are each a document and a template that give the same, the fonts and the images are the same as those of
 * {@code ../stxt-pdf/resources}, and the schemas of {@code .stxt/} are those of the jar.
 */
class ServerFolderTest {

    private static final Path FOLDER = Path.of("server").toAbsolutePath();
    private static final Path STXT_PDF = Path.of("..", "stxt-pdf", "resources").toAbsolutePath().normalize();
    private static final Path EXAMPLES = Path.of("..", "stxt-pdf", "test", "examples").toAbsolutePath().normalize();

    /** The template made from each example of stxt-pdf, from its English version. */
    private static final Map<String, String> TEMPLATE_OF_EXAMPLE = Map.ofEntries(
            Map.entry("01-invoice", "examples/invoice"),
            Map.entry("02-letter", "examples/letter"),
            Map.entry("03-resume", "examples/resume"),
            Map.entry("04-annual-report", "examples/annual-report"),
            Map.entry("05-newsletter", "examples/newsletter"),
            Map.entry("06-menu", "examples/menu"),
            Map.entry("07-contract", "examples/contract"),
            Map.entry("08-manual", "examples/manual"),
            Map.entry("09-catalog", "examples/catalog"),
            Map.entry("10-certificate", "examples/certificate"),
            Map.entry("11-timetable", "examples/timetable"));

    private static ServerFile server() throws ConfigException, IOException {
        return ServerFile.read(FOLDER.resolve(ServerFile.FILE_NAME), Map.of());
    }

    @Test
    void serverFileNamesTheFoldersOfTheFolder() throws ConfigException, IOException {
        ServerFile server = server();

        assertEquals(FOLDER.resolve("resources"), server.resources().root());
        assertEquals(FOLDER.resolve("templates"), server.templateFolder());
        assertEquals(FOLDER.resolve("documents"), server.documentFolder());
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

    /** Every document converts as it is, with its chain of styles and the options of the server, to PDF and to SVG. */
    @TestFactory
    Stream<DynamicTest> everyDocumentConverts() throws ConfigException, IOException {
        ServerFile server = server();
        Press press = new Press(server.converter());
        Templates documents = new Templates(server.documentFolder(), Templates.Kind.DOCUMENT);
        List<Templates.Entry> entries = documents.list();
        assertFalse(entries.isEmpty(), "no documents in server/documents/");
        return entries.stream().map(entry -> DynamicTest.dynamicTest(entry.path(), () -> {
            Template document = documents.get(entry.path());
            Job job = document.job(server.http().templateOptions());

            ByteArrayOutputStream pdf = new ByteArrayOutputStream();
            press.pdf(job, pdf);

            assertEquals("%PDF", new String(pdf.toByteArray(), 0, 4, StandardCharsets.US_ASCII));
            assertFalse(press.svg(job).isEmpty());
        }));
    }

    @Test
    void everyExampleOfStxtPdfHasItsTemplate() throws IOException {
        List<String> examples;
        try (Stream<Path> list = Files.list(EXAMPLES)) {
            examples = list.filter(Files::isDirectory).map(dir -> dir.getFileName().toString()).sorted().toList();
        }
        assertEquals(examples, TEMPLATE_OF_EXAMPLE.keySet().stream().sorted().toList(),
                "the examples of " + EXAMPLES);
    }

    @TestFactory
    Stream<DynamicTest> everyTemplateWithItsSampleDataGivesThePagesOfItsExample() throws ConfigException, IOException {
        // The SVG pages and not the PDF: a template can repeat what the example writes by hand only with another
        // structure (a Grid with one Area for each job of the resume, a Table for the Markdown table of the report),
        // which looks the same but is tagged differently in the PDF.
        ServerFile server = server();
        Press press = new Press(server.converter());
        Templates templates = new Templates(server.templateFolder());
        Options options = server.http().templateOptions();
        return TEMPLATE_OF_EXAMPLE.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> DynamicTest.dynamicTest(entry.getValue(), () -> {
                    Path example = EXAMPLES.resolve(entry.getKey());
                    Job expected = Job.of(Files.readString(example.resolve("en/input.stxt")))
                            .withStyles(List.of(new Job.Styles("styles.stxt",
                                    Files.readString(example.resolve("styles.stxt")))))
                            .withOptions(options);

                    assertEquals(press.svg(expected), press.svg(templates.get(entry.getValue()).job(options)),
                            entry.getValue() + " with its sample data is not " + entry.getKey());
                }));
    }

    /** The complete examples are the same in the two folders: each one is a document and a template. */
    @Test
    void theExamplesAreDocumentsAndTemplates() throws ConfigException, IOException {
        ServerFile server = server();
        List<String> templates = examples(new Templates(server.templateFolder()));
        List<String> documents = examples(new Templates(server.documentFolder(), Templates.Kind.DOCUMENT));

        assertFalse(templates.isEmpty(), "no examples in server/templates/examples/");
        assertEquals(templates, documents, "the examples of server/templates/ and of server/documents/");
    }

    /**
     * Each example template, filled with its sample data, is its document, and both have the same chain of styles: so
     * the two look the same, whatever the comments say.
     */
    @TestFactory
    Stream<DynamicTest> everyExampleTemplateWithItsSampleDataIsItsDocument() throws ConfigException, IOException {
        ServerFile server = server();
        Templates templates = new Templates(server.templateFolder());
        Templates documents = new Templates(server.documentFolder(), Templates.Kind.DOCUMENT);
        return examples(templates).stream().map(path -> DynamicTest.dynamicTest(path, () -> {
            Template template = templates.get(path);
            Template document = documents.get(path);

            assertEquals(withoutComments(document.document()), withoutComments(Press.fill(template.document(),
                    template.data())), path + " with its sample data is not the document " + path);
            assertEquals(chain(document), chain(template), "the styles of " + path);
        }));
    }

    /** {@return the paths under {@code examples/} of a folder} */
    private static List<String> examples(Templates folder) throws IOException {
        return folder.list().stream().map(Templates.Entry::path).filter(path -> path.startsWith("examples/")).toList();
    }

    /** {@return each styles file of the chain, by its name, without its comments} */
    private static List<String> chain(Template template) {
        return template.styles().stream().map(styles -> styles.name() + "\n" + withoutComments(styles.text())).toList();
    }

    /** {@return the text with LF, without its comment lines and without blank lines at its end} */
    private static String withoutComments(String text) {
        return lines(text).lines().filter(line -> !line.strip().startsWith("#")).collect(Collectors.joining("\n"))
                .stripTrailing();
    }

    @Test
    void fontsAreThoseOfStxtPdf() throws IOException {
        assertSameFiles("fonts");
    }

    @Test
    void imagesAreThoseOfTheExamplesOfStxtPdf() throws IOException {
        assertSameFiles("images/examples");
    }

    @Test
    void definitionsAreTheSchemasOfTheJar() throws IOException {
        // .stxt/ holds the schemas for the editors (discovery): those the server and the engine validate with.
        assertEquals(List.of(Path.of("dev.stxt.pdf.stxt"), Path.of("dev.stxt.press.stxt")),
                files(FOLDER.resolve(".stxt")));
        assertEquals(lines(DocumentSchema.text()), lines(Files.readString(FOLDER.resolve(".stxt/dev.stxt.pdf.stxt"))),
                "server/.stxt/dev.stxt.pdf.stxt is not the schema of the engine");
        try (InputStream in = ServerFile.class.getResourceAsStream("/stxt/dev.stxt.press.stxt")) {
            assertEquals(lines(new String(in.readAllBytes(), StandardCharsets.UTF_8)),
                    lines(Files.readString(FOLDER.resolve(".stxt/dev.stxt.press.stxt"))),
                    "server/.stxt/dev.stxt.press.stxt is not the schema of server.stxt");
        }
    }

    /** The text with LF line ends: a checkout with core.autocrlf has CRLF, and the jar of the engine, LF. */
    private static String lines(String text) {
        return text.replace("\r\n", "\n");
    }

    /** The files under {@code folder} of the resources of the server and of stxt-pdf: byte for byte the same. */
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
