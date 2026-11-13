package dev.stxt.press.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.stxt.pdf.convert.Converter;
import dev.stxt.pdf.resources.Resources;
import dev.stxt.press.Job;
import dev.stxt.press.Options;
import dev.stxt.press.Press;
import dev.stxt.press.PressException;

/**
 * The templates folder (ROADMAP-PDF 2.3): the chain of styles and which one wins, the sample data and the data of a
 * request, the paths that are not a template and the links that leave the folder. The tree is written in a temporary
 * folder before each test; every level of styles gives its own color to the text, which the SVG of the preview shows.
 */
class TemplatesTest {

    private static final Press PRESS = new Press(Converter.builder()
            .resources(new Resources(Path.of("..", "stxt-pdf", "resources"))).build());

    /** A document with a Slot of a value (line 3), one of a number (line 6) and a repeated one (line 8). */
    private static final String INVOICE = """
            Document (dev.stxt.pdf):
            \tLanguage: en
            \tTitle: <title>
            \tPage:
            \t\tPaper: A4
            \t\tMargin: <margin>
            \tSection:
            \t\tParagraph: [lines]<text>
            """;

    private static final String SAMPLE = """
            {"title": "Sample", "margin": 40, "lines": [{"text": "One."}, {"text": "Two."}]}
            """;

    private static final String RECEIPT = """
            Document (dev.stxt.pdf):
            \tLanguage: en
            \tTitle: Receipt
            \tPage:
            \t\tPaper: A4
            \tSection:
            \t\tParagraph: Paid.
            """;

    /** A document with its own {@code Styles}, which win over those of every level. */
    private static final String OWN = """
            Document (dev.stxt.pdf):
            \tLanguage: en
            \tTitle: Own
            \tPage:
            \t\tPaper: A4
            \tStyles:
            \t\tFor element: Document
            \t\t\tTypography:
            \t\t\t\tColor: #444444
            \tSection:
            \t\tParagraph: Mine.
            """;

    @TempDir
    Path temp;

    private Path folder;
    private Templates templates;

    /** The styles of one level: the font and its size at the root, and a color of its own in each. */
    private static String styles(String color, boolean font) {
        return "Styles (dev.stxt.pdf):\n\tFor element: Document\n\t\tTypography:\n"
                + (font ? "\t\t\tFont: IBM Plex Sans\n\t\t\tSize: 11\n" : "") + "\t\t\tColor: " + color + "\n";
    }

    private void write(String relative, String text) throws IOException {
        Path file = folder.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @BeforeEach
    void tree() throws IOException {
        folder = temp.resolve("templates");
        write(".styles.stxt", styles("#111111", true));
        write("invoices/.styles.stxt", styles("#222222", false));
        // invoices/2026 has no .styles.stxt: a level that is missing.
        write("invoices/2026/invoice.stxt", INVOICE);
        write("invoices/2026/invoice.styles.stxt", styles("#333333", false));
        write("invoices/2026/invoice.json", SAMPLE);
        write("invoices/receipt.stxt", RECEIPT);
        write("own.stxt", OWN);
        write("unfilled.stxt", INVOICE);
        write("notes.txt", "Not a template.");
        write(".hidden.stxt", RECEIPT);
        write(".git/ignored.stxt", RECEIPT);
        write("with.dot/ignored.stxt", RECEIPT);
        templates = new Templates(folder);
    }

    /** A symbolic link; where the system cannot create one (Windows without the privilege), the test is skipped. */
    private static void link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "No symbolic links: " + e.getMessage());
        }
    }

    private static String svg(Job job) {
        return String.join("\n", PRESS.svg(job));
    }

    private static boolean isPdf(Job job) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PRESS.pdf(job, out);
        byte[] bytes = out.toByteArray();
        return bytes.length > 1000 && new String(Arrays.copyOf(bytes, 5), StandardCharsets.ISO_8859_1).equals("%PDF-");
    }

    private static List<String> names(Template template) {
        return template.styles().stream().map(Job.Styles::name).toList();
    }

    @Test
    void theStylesAreChainedFromTheRootToTheTemplate() throws IOException {
        Template invoice = templates.get("invoices/2026/invoice");

        assertEquals(List.of(".styles.stxt", "invoices/.styles.stxt", "invoices/2026/invoice.styles.stxt"),
                names(invoice));
        assertEquals("invoice", invoice.name());
        assertEquals(INVOICE, invoice.document());
    }

    @Test
    void theNearestStylesWin() throws IOException {
        String invoice = svg(templates.get("invoices/2026/invoice").job(Options.DEFAULT));
        String receipt = svg(templates.get("invoices/receipt").job(Options.DEFAULT));

        assertTrue(invoice.contains("#333333"), "the styles of the template");
        assertFalse(invoice.contains("#222222") || invoice.contains("#111111"));
        assertTrue(receipt.contains("#222222"), "the styles of its folder, without its own");
        assertFalse(receipt.contains("#111111"));
    }

    @Test
    void theStylesOfTheTemplateWinOverAll() throws IOException {
        String own = svg(templates.get("own").job(Options.DEFAULT));

        assertTrue(own.contains("#444444"), own);
        assertFalse(own.contains("#111111"));
    }

    @Test
    void getFillsWithTheSampleData() throws IOException {
        Template invoice = templates.get("invoices/2026/invoice");
        Job job = invoice.job(Options.DEFAULT);

        assertEquals(SAMPLE, invoice.data());
        assertTrue(isPdf(job));
        assertTrue(Press.fill(job.document(), job.data()).contains("Title: Sample"));
    }

    @Test
    void postReplacesTheSampleDataWhole() throws IOException {
        Job job = templates.get("invoices/2026/invoice").job(Options.DEFAULT,
                "{\"title\": \"Posted\", \"margin\": 30, \"lines\": [{\"text\": \"Only.\"}]}");

        String filled = Press.fill(job.document(), job.data());
        assertTrue(filled.contains("Title: Posted"), filled);
        assertFalse(filled.contains("Two."), filled);
        assertTrue(isPdf(job));
    }

    @Test
    void withoutSampleDataATemplateIsConvertedAsItIs() throws IOException {
        Template receipt = templates.get("invoices/receipt");

        assertNull(receipt.data());
        assertTrue(isPdf(receipt.job(Options.DEFAULT)));
    }

    @Test
    void withoutSampleDataATemplateWithSlotsGivesTheErrorOfTheEngine() throws IOException {
        Job job = templates.get("unfilled").job(Options.DEFAULT);

        PressException e = assertThrows(PressException.class, () -> PRESS.svg(job));
        assertEquals(PressException.DOCUMENT, e.source());
        assertEquals(6, e.line(), "Margin: <margin> is not a number");
    }

    @Test
    void anErrorOfTheStylesHasThePathOfTheirFile() throws IOException {
        write("broken/.styles.stxt", """
                Styles (dev.stxt.pdf):
                \tFor element: Paragraph
                \t\tTypography:
                \t\t\tBold: maybe
                """);
        write("broken/receipt.stxt", RECEIPT);

        Job job = templates.get("broken/receipt").job(Options.DEFAULT);

        PressException e = assertThrows(PressException.class, () -> PRESS.svg(job));
        assertEquals("broken/.styles.stxt", e.source());
        assertEquals(4, e.line());
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "missing", "invoices", "invoices/", "/invoices/receipt", "invoices//receipt",
            "invoices/../own", "../templates/own", "invoices\\receipt", ".hidden", ".styles", "invoices/.styles",
            "invoices/2026/invoice.styles", "invoices/2026/invoice.json", "invoices/2026/invoice.stxt", "notes",
            ".git/ignored", "with.dot/ignored", "own " })
    void aPathThatIsNotATemplateIsNotFound(String path) {
        TemplateException e = assertThrows(TemplateException.class, () -> templates.get(path));

        assertEquals(TemplateException.NOT_FOUND, e.code());
        assertEquals(path, e.path());
    }

    @Test
    void noPathIsNotFound() {
        assertEquals(TemplateException.NOT_FOUND,
                assertThrows(TemplateException.class, () -> templates.get(null)).code());
    }

    @Test
    void aLinkThatLeavesTheFolderIsNotRead() throws IOException {
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.stxt"), RECEIPT);
        Files.writeString(outside.resolve("styles.stxt"), styles("#555555", false));
        link(folder.resolve("linked.stxt"), outside.resolve("secret.stxt"));
        link(folder.resolve("away"), outside);
        Files.createDirectories(folder.resolve("shared"));
        link(folder.resolve("shared/.styles.stxt"), outside.resolve("styles.stxt"));
        write("shared/receipt.stxt", RECEIPT);

        for (String path : List.of("linked", "away/secret", "shared/receipt")) {
            TemplateException e = assertThrows(TemplateException.class, () -> templates.get(path), path);
            assertEquals(TemplateException.LINK_OUTSIDE, e.code(), path);
            assertFalse(e.getMessage().contains(outside.toString()), "the message does not say where the link goes");
        }
        List<String> listed = templates.list().stream().map(Templates.Entry::path).toList();
        assertFalse(listed.contains("linked"));
        assertFalse(listed.stream().anyMatch(path -> path.startsWith("away/")));
    }

    @Test
    void aLinkInsideTheFolderIsATemplate() throws IOException {
        link(folder.resolve("alias.stxt"), folder.resolve("invoices/receipt.stxt"));

        assertEquals(RECEIPT, templates.get("alias").document());
        assertEquals(List.of(".styles.stxt"), names(templates.get("alias")));
    }

    @Test
    void theListHasEveryTemplateWithItsFiles() throws IOException {
        assertEquals(List.of(
                new Templates.Entry("invoices/2026/invoice", true, true),
                new Templates.Entry("invoices/receipt", false, false),
                new Templates.Entry("own", false, false),
                new Templates.Entry("unfilled", false, false)), templates.list());
    }

    @Test
    void aTemplateChangedIsReadAgain() throws IOException {
        write("invoices/receipt.stxt", RECEIPT.replace("Paid.", "Changed."));

        assertTrue(templates.get("invoices/receipt").document().contains("Changed."));
    }

    @Test
    void theFolderHasToExist() {
        assertThrows(IOException.class, () -> new Templates(temp.resolve("missing")));
        assertThrows(IOException.class, () -> new Templates(folder.resolve("notes.txt")));
        assertThrows(IOException.class, () -> new Templates(temp.resolve("missing"), Templates.Kind.DOCUMENT));
    }

    // ---- A documents folder: the same rules, without data

    @Test
    void aDocumentHasItsChainOfStylesAndNoData() throws IOException {
        Templates documents = new Templates(folder, Templates.Kind.DOCUMENT);

        Template invoice = documents.get("invoices/2026/invoice");

        assertEquals(Templates.Kind.DOCUMENT, documents.kind());
        assertEquals(List.of(".styles.stxt", "invoices/.styles.stxt", "invoices/2026/invoice.styles.stxt"), names(invoice));
        // Its invoice.json is not read: a document is converted as it is.
        assertNull(invoice.data());
        assertFalse(documents.files(invoice).containsKey(PressException.DATA));
        assertTrue(isPdf(documents.get("invoices/receipt").job(Options.DEFAULT)));
    }

    @Test
    void theListOfDocumentsSaysNoData() throws IOException {
        assertEquals(List.of(
                new Templates.Entry("invoices/2026/invoice", true, false),
                new Templates.Entry("invoices/receipt", false, false),
                new Templates.Entry("own", false, false),
                new Templates.Entry("unfilled", false, false)), new Templates(folder, Templates.Kind.DOCUMENT).list());
    }

    @Test
    void theErrorsOfADocumentHaveTheirOwnCodes() throws IOException {
        Templates documents = new Templates(folder, Templates.Kind.DOCUMENT);

        TemplateException missing = assertThrows(TemplateException.class, () -> documents.get("missing"));
        assertEquals(TemplateException.DOCUMENT_NOT_FOUND, missing.code());
        assertTrue(missing.notFound());
        assertEquals("There is no document missing", missing.getMessage());

        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.stxt"), RECEIPT);
        link(folder.resolve("linked.stxt"), outside.resolve("secret.stxt"));
        TemplateException link = assertThrows(TemplateException.class, () -> documents.get("linked"));
        assertEquals(TemplateException.DOCUMENT_LINK_OUTSIDE, link.code());
        assertFalse(link.notFound());
        assertTrue(link.getMessage().contains("documents folder"), link.getMessage());
    }

    // ---- The save

    private static Templates.Change change(String document, Map<String, String> styles, String data) {
        return new Templates.Change(document, styles, data);
    }

    @Test
    void aSaveWritesOnlyWhatChangedAndGivesTheNewVersion() throws IOException {
        Template before = templates.get("invoices/2026/invoice");
        Path shared = folder.resolve(".styles.stxt");
        long sharedTime = Files.getLastModifiedTime(shared).toMillis();

        Templates.Saved saved = templates.save("invoices/2026/invoice", before.version(),
                change(INVOICE.replace("Paper: A4", "Paper: A5"), Map.of(".styles.stxt", before.styles().get(0).text(),
                        "invoices/2026/invoice.styles.stxt", styles("#444444", false)), "{\"a\": 1}"));

        assertEquals(List.of("invoices/2026/invoice.stxt", "invoices/2026/invoice.styles.stxt",
                "invoices/2026/invoice.json"), saved.written());
        assertTrue(Files.readString(folder.resolve("invoices/2026/invoice.stxt")).contains("Paper: A5"));
        assertEquals("{\"a\": 1}", Files.readString(folder.resolve("invoices/2026/invoice.json")));
        // The shared styles came as they were: not written.
        assertEquals(sharedTime, Files.getLastModifiedTime(shared).toMillis());
        assertEquals(templates.get("invoices/2026/invoice"), saved.template());
        assertFalse(before.version().equals(saved.template().version()));
        // No file of its own is left beside them.
        try (var list = Files.list(folder.resolve("invoices/2026"))) {
            assertEquals(3, list.count());
        }
    }

    @Test
    void aSaveFromAnotherVersionWritesNothing() throws IOException {
        Template before = templates.get("invoices/receipt");
        write("invoices/receipt.stxt", RECEIPT.replace("Paid.", "Changed on disk."));

        TemplateException e = assertThrows(TemplateException.class, () -> templates.save("invoices/receipt",
                before.version(), change(RECEIPT.replace("Paid.", "Mine."), Map.of(), null)));

        assertEquals(TemplateException.FILES_CHANGED, e.code());
        assertEquals(TemplateException.Reason.CHANGED, e.reason());
        assertTrue(Files.readString(folder.resolve("invoices/receipt.stxt")).contains("Changed on disk."));
    }

    @Test
    void aSaveKeepsTheLineEndsOfEachFile() throws IOException {
        write("invoices/receipt.stxt", RECEIPT.replace("\n", "\r\n"));
        Template before = templates.get("invoices/receipt");

        templates.save("invoices/receipt", before.version(),
                change(RECEIPT.replace("Paid.", "Mine."), Map.of("invoices/.styles.stxt", "Styles (dev.stxt.pdf):\r\n"),
                        null));

        String receipt = Files.readString(folder.resolve("invoices/receipt.stxt"));
        assertTrue(receipt.contains("Mine.\r\n"), receipt);
        assertFalse(receipt.replace("\r\n", "").contains("\n"), "every line end is CRLF");
        // A file with LF stays with LF.
        assertEquals("Styles (dev.stxt.pdf):\n", Files.readString(folder.resolve("invoices/.styles.stxt")));
    }

    @Test
    void aSaveCreatesTheOwnStylesAndTheDataOnlyWithText() throws IOException {
        Template before = templates.get("invoices/receipt");

        Templates.Saved blank = templates.save("invoices/receipt", before.version(),
                change(RECEIPT, Map.of("invoices/receipt.styles.stxt", " \n"), " "));
        assertEquals(List.of(), blank.written());
        assertFalse(Files.exists(folder.resolve("invoices/receipt.styles.stxt")));
        assertFalse(Files.exists(folder.resolve("invoices/receipt.json")));

        Templates.Saved created = templates.save("invoices/receipt", before.version(),
                change(RECEIPT, Map.of("invoices/receipt.styles.stxt", styles("#555555", false)), "{}"));
        assertEquals(List.of("invoices/receipt.styles.stxt", "invoices/receipt.json"), created.written());
        assertEquals(List.of(".styles.stxt", "invoices/.styles.stxt", "invoices/receipt.styles.stxt"),
                names(created.template()));
        assertEquals("{}", created.template().data());
    }

    @Test
    void aSaveTakesOnlyTheFilesOfTheChain() throws IOException {
        Template before = templates.get("invoices/receipt");

        for (String name : List.of("own.styles.stxt", "invoices/2026/.styles.stxt", "../outside.stxt", "invoices/x")) {
            assertThrows(IllegalArgumentException.class, () -> templates.save("invoices/receipt", before.version(),
                    change(RECEIPT, Map.of(name, "x"), null)), name);
        }
        // A folder .styles.stxt that does not exist is not created.
        assertFalse(Files.exists(folder.resolve("invoices/2026/.styles.stxt")));
        Templates documents = new Templates(folder, Templates.Kind.DOCUMENT);
        assertThrows(IllegalArgumentException.class, () -> documents.save("invoices/receipt",
                documents.get("invoices/receipt").version(), change(RECEIPT, Map.of(), "{}")));
        assertEquals(TemplateException.DOCUMENT_NOT_FOUND, assertThrows(TemplateException.class,
                () -> documents.save("missing", "x", change(RECEIPT, Map.of(), null))).code());
    }

    @Test
    void aSaveWritesWhereALinkInsideTheFolderGoes() throws IOException {
        link(folder.resolve("alias.stxt"), folder.resolve("invoices/receipt.stxt"));

        templates.save("alias", templates.get("alias").version(), change(RECEIPT.replace("Paid.", "Linked."),
                Map.of(), null));

        assertTrue(Files.isSymbolicLink(folder.resolve("alias.stxt")));
        assertTrue(Files.readString(folder.resolve("invoices/receipt.stxt")).contains("Linked."));
    }

    @Test
    void theVersionChangesWithAnyFile() throws IOException {
        String version = templates.get("invoices/receipt").version();
        assertEquals(version, templates.get("invoices/receipt").version());
        assertEquals(64, version.length());

        write("invoices/receipt.json", "{}");
        String withData = templates.get("invoices/receipt").version();
        write("invoices/.styles.stxt", styles("#999999", false));
        String withStyles = templates.get("invoices/receipt").version();

        assertEquals(3, Set.of(version, withData, withStyles).size());
    }
}
