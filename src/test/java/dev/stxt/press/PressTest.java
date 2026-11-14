package dev.stxt.press;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.stxt.pdf.convert.Converter;
import dev.stxt.pdf.model.Limits;
import dev.stxt.pdf.model.PdfA;
import dev.stxt.pdf.model.StxtPdfException.Kind;
import dev.stxt.pdf.resources.Resources;

/**
 * The core of the service (ROADMAP-PDF 2.2): the jobs, the fill before converting, the separate styles in order, and the
 * input each error belongs to. The fonts are those of {@code ../stxt-pdf/resources}, as in {@link EngineJobsTest}.
 */
class PressTest {

    private static final Converter SHARED = Converter.builder()
            .resources(new Resources(Path.of("..", "stxt-pdf", "resources"))).build();

    private static final Press PRESS = new Press(SHARED);

    private static final String DOCUMENT = """
            Document (dev.stxt.pdf):
            \tLanguage: en
            \tTitle: Test
            \tPage:
            \t\tPaper: A4
            \t\tMargin: 40
            \tSection:
            \t\tParagraph: Hello.
            """;

    /** Line 3 is a Slot of a value, and line 7 a repeated one. */
    private static final String WITH_SLOTS = """
            Document (dev.stxt.pdf):
            \tLanguage: en
            \tTitle: <title>
            \tPage:
            \t\tPaper: A4
            \tSection:
            \t\tParagraph: [lines]<text>
            """;

    private static final String DATA = """
            {"title": "Filled", "lines": [{"text": "One."}, {"text": "Two."}, {"text": "Three."}]}
            """;

    private static final String STYLES = """
            Styles (dev.stxt.pdf):
            \tFor element: Document
            \t\tTypography:
            \t\t\tFont: IBM Plex Sans
            \t\t\tSize: 11
            """;

    /** The bold of line 4 is not a boolean. */
    private static final String BAD_STYLES = """
            Styles (dev.stxt.pdf):
            \tFor element: Paragraph
            \t\tTypography:
            \t\t\tBold: maybe
            """;

    /** {@return a job with {@code document} and the styles that give it its font, which the engine requires} */
    private static Job job(String document) {
        return Job.of(document).withStyles(List.of(new Job.Styles("styles[1]", STYLES)));
    }

    private static byte[] pdf(Job job) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PRESS.pdf(job, out);
        return out.toByteArray();
    }

    private static boolean isPdf(byte[] bytes) {
        return bytes.length > 1000 && new String(Arrays.copyOf(bytes, 5), StandardCharsets.ISO_8859_1).equals("%PDF-");
    }

    @Test
    void convertsADocumentAsItIs() throws Exception {
        assertTrue(isPdf(pdf(job(DOCUMENT))));
    }

    @Test
    void convertsADocumentWithSeveralStyles() throws Exception {
        Job job = job(DOCUMENT).withStyles(List.of(new Job.Styles("styles[1]", STYLES),
                new Job.Styles("styles[2]", STYLES.replace("Size: 11", "Size: 12"))));

        assertTrue(isPdf(pdf(job)));
    }

    @Test
    void fillsTheSlotsBeforeConverting() throws Exception {
        assertTrue(isPdf(pdf(job(WITH_SLOTS).withData(DATA))));
    }

    @Test
    void previewsOneSvgPerPage() {
        List<String> pages = PRESS.svg(job(WITH_SLOTS).withData(DATA).withOptions(Options.DEFAULT.withDebug(true)));

        assertEquals(1, pages.size());
        assertTrue(pages.get(0).startsWith("<svg"));
    }

    @Test
    void fillGivesTheFilledText() {
        String filled = Press.fill(WITH_SLOTS, DATA);

        assertTrue(filled.contains("Title: Filled"), filled);
        assertTrue(filled.contains("Paragraph: Three."), filled);
    }

    /** The text a job converts: filled with its data, or the document as it is without them. */
    @Test
    void theTextOfAJobIsFilledOnlyWithData() {
        assertEquals(WITH_SLOTS, Press.filled(job(WITH_SLOTS)));
        assertEquals(Press.fill(WITH_SLOTS, DATA), Press.filled(job(WITH_SLOTS).withData(DATA)));
    }

    @Test
    void aDocumentWithoutSlotsIsFilledAsItIs() {
        assertEquals(Press.fill(DOCUMENT, "{}"), Press.fill(DOCUMENT, DATA));
        assertTrue(Press.fill(DOCUMENT, DATA).contains("Paragraph: Hello."));
    }

    @Test
    void anErrorOfTheDocumentIsOfTheDocument() {
        PressException e = assertThrows(PressException.class,
                () -> pdf(job(DOCUMENT.replace("Margin: 40", "Margin: wide"))));

        assertEquals(Kind.INVALID_DOCUMENT, e.kind());
        assertEquals(PressException.DOCUMENT, e.source());
        assertEquals(6, e.line());
        assertNull(e.filled());
    }

    @Test
    void anErrorOfTheStylesHasTheirName() {
        Job job = job(DOCUMENT).withStyles(List.of(new Job.Styles("common", STYLES),
                new Job.Styles("invoices/.styles.stxt", BAD_STYLES)));

        PressException e = assertThrows(PressException.class, () -> pdf(job));

        assertEquals(Kind.INVALID_DOCUMENT, e.kind());
        assertEquals("invoices/.styles.stxt", e.source());
        assertEquals(4, e.line());
    }

    @Test
    void dataThatAreNotJsonAreAnErrorOfTheData() {
        PressException e = assertThrows(PressException.class, () -> Press.fill(WITH_SLOTS, "{\n\"title\": }"));

        assertEquals(Kind.INVALID_DOCUMENT, e.kind());
        assertEquals("JSON_NOT_VALID", e.code());
        assertEquals(PressException.DATA, e.source());
        assertEquals(2, e.line());
    }

    @Test
    void aSlotWithoutDataIsAnErrorOfTheDocumentAtTheLineOfTheSlot() {
        PressException e = assertThrows(PressException.class,
                () -> pdf(job(WITH_SLOTS).withData("{\"lines\": []}")));

        assertEquals("SLOT_NO_DATA", e.code());
        assertEquals(PressException.DOCUMENT, e.source());
        assertEquals(3, e.line());
    }

    @Test
    void anErrorOfTheFilledDocumentHasTheLineOfTheFilledText() {
        String document = WITH_SLOTS + "\t\tUnknown: here\n";

        PressException e = assertThrows(PressException.class, () -> pdf(job(document).withData(DATA)));

        assertEquals(Kind.INVALID_DOCUMENT, e.kind());
        assertEquals(PressException.FILLED, e.source());
        assertEquals(Press.fill(document, DATA), e.filled());
        assertEquals(10, e.line(), "line 8 of the document with Slots, after two more paragraphs");
        assertTrue(e.filled().lines().toList().get(e.line() - 1).contains("Unknown: here"), e.filled());
    }

    /**
     * The error as one line: the name of its source and its line, then its code and its message; the filled document
     * by that name, and an error of no input without a place.
     */
    @Test
    void anErrorIsDescribedWithTheNameOfItsSource() {
        PressException data = assertThrows(PressException.class, () -> Press.fill(WITH_SLOTS, "{\n\"title\": }"));
        assertEquals("invoice.json:2: JSON_NOT_VALID: " + data.getMessage(),
                data.describe(Map.of(PressException.DATA, "invoice.json")));
        assertEquals("data:2: JSON_NOT_VALID: " + data.getMessage(), data.describe(Map.of()));

        PressException filled = assertThrows(PressException.class,
                () -> pdf(job(WITH_SLOTS + "\t\tUnknown: here\n").withData(DATA)));
        assertEquals("the filled document:10: " + filled.code() + ": " + filled.getMessage(),
                filled.describe(Map.of(PressException.DOCUMENT, "invoice.stxt")));

        PressException cancelled = assertThrows(PressException.class,
                () -> PRESS.pdf(job(DOCUMENT), new ByteArrayOutputStream(), () -> true));
        assertEquals(cancelled.code() + ": " + cancelled.getMessage(), cancelled.describe(Map.of()));
    }

    @Test
    void anErrorLeavesTheTargetUntouched() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThrows(PressException.class, () -> PRESS.pdf(job(DOCUMENT.replace("Margin: 40", "Margin: wide")), out));

        assertEquals(0, out.size());
    }

    @Test
    void aLimitOfTheServerIsALimit() {
        Press small = new Press(SHARED.toBuilder().limits(Limits.DEFAULT.withMaxPages(1)).build());
        String longDocument = DOCUMENT + "\t\tParagraph: More.\n".repeat(200);

        PressException e = assertThrows(PressException.class,
                () -> small.pdf(job(longDocument), new ByteArrayOutputStream()));

        assertEquals(Kind.LIMIT, e.kind());
    }

    @Test
    void aCancelledJobIsCancelledAndOfNoInput() {
        PressException e = assertThrows(PressException.class,
                () -> PRESS.pdf(job(DOCUMENT), new ByteArrayOutputStream(), () -> true));

        assertEquals(Kind.CANCELLED, e.kind());
        assertNull(e.source());
    }

    @Test
    void theOptionsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> Options.DEFAULT.withPdfUa(3));
        assertThrows(IllegalArgumentException.class, () -> Options.DEFAULT.withPdfA(PdfA.A2B).withPdfUa(2));
    }

    @Test
    void convertsWithPdfA() throws Exception {
        assertTrue(isPdf(pdf(job(DOCUMENT).withOptions(Options.DEFAULT.withPdfA(PdfA.A2B)))));
    }
}
