package dev.stxt.press;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.stxt.InlineNode;
import dev.stxt.Node;
import dev.stxt.Parser;
import dev.stxt.pdf.convert.Converter;
import dev.stxt.pdf.layout.LaidOutDocument;
import dev.stxt.pdf.model.Cancellation;
import dev.stxt.pdf.model.Limits;
import dev.stxt.pdf.model.StxtPdfException;
import dev.stxt.pdf.resources.Resources;
import dev.stxt.pdf.stxt.DocumentReader;
import dev.stxt.runtime.NodeWriter;
import dev.stxt.transform.FillJson;
import dev.stxt.transform.TransformException;

/**
 * The jobs of the service (ROADMAP-PDF 2.2: convert, fill and convert, preview) done with the API of the engine as it
 * is, to see what the service can do with it and what it cannot. The fonts are those of {@code ../stxt-pdf/resources},
 * which has to be next to this project, as the corpus of the language is next to its ports.
 */
class EngineJobsTest {

    private static final Path RESOURCES = Path.of("..", "stxt-pdf", "resources");

    /**
     * What every request shares: the converter with the folder of the server, whose fonts and images are read once
     * and kept, and the limits of the service.
     */
    private static final Converter SHARED = Converter.builder().resources(new Resources(RESOURCES)).build();

    private static final String DOCUMENT = """
            Document (dev.stxt.pdf):
            \tLanguage: en
            \tTitle: Test
            \tPage:
            \t\tPaper: A4
            \t\tMargin: 40
            \tSection:
            \t\tParagraph:
            \t\t\tStyle: lead
            \t\t\tContent >>
            \t\t\t\tHello.
            \t\tParagraph: And goodbye.
            """;

    private static final String STYLES = """
            Styles (dev.stxt.pdf):
            \tFor element: Document
            \t\tTypography:
            \t\t\tFont: IBM Plex Sans
            \t\t\tSize: 11
            \tFor style: lead
            \t\tTypography:
            \t\t\tBold: true
            """;

    /**
     * A converter for one request: the shared one, with the styles and the options of the request
     * ({@code Converter.toBuilder()}, which keeps the resources already read).
     */
    private static Converter forRequest(String styles, boolean debug) {
        Converter.Builder b = SHARED.toBuilder().debug(debug);
        if (styles != null) {
            b.styles(styles);
        }
        return b.build();
    }

    private static byte[] pdf(Converter converter, String document) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        converter.toPdf(new DocumentReader().read(document), out);
        return out.toByteArray();
    }

    @Test
    void theFontsOfTheEngineAreNextToThisProject() {
        assertTrue(Files.isDirectory(RESOURCES.resolve("fonts")), "../stxt-pdf/resources/fonts is needed");
    }

    @Test
    void convertsADocumentWithItsStylesToPdf() throws Exception {
        byte[] pdf = pdf(forRequest(STYLES, false), DOCUMENT);

        assertTrue(pdf.length > 1000);
        assertEquals("%PDF-", new String(pdf, 0, 5, "ISO-8859-1"));
    }

    @Test
    void fillsTheSlotsAndConvertsTheResult() throws Exception {
        List<Node> filled = new FillJson().fill(new Parser().parse("""
                Document (dev.stxt.pdf):
                \tLanguage: en
                \tTitle: <title>
                \tPage:
                \t\tPaper: A4
                \tSection:
                \t\tParagraph: [lines]<text>
                """), """
                {"title": "Filled", "lines": [{"text": "One."}, {"text": "Two."}]}
                """);
        String document = NodeWriter.toSTXT(filled);

        byte[] pdf = pdf(forRequest(STYLES, false), document);

        assertTrue(document.contains("Title: Filled"));
        assertEquals("%PDF-", new String(pdf, 0, 5, "ISO-8859-1"));
    }

    @Test
    void previewsTheLaidOutDocumentAsOneSvgPerPage() {
        Converter converter = forRequest(STYLES, true);
        LaidOutDocument laidOut = converter.layOut(new DocumentReader().read(DOCUMENT));

        List<String> pages = converter.toSvg(laidOut);

        assertEquals(laidOut.pages().size(), pages.size());
        assertEquals(1, pages.size());
        assertTrue(pages.get(0).startsWith("<svg"), pages.get(0).substring(0, 20));
    }

    @Test
    void theSameLaidOutDocumentGivesThePdfAndThePreview() throws Exception {
        Converter converter = forRequest(STYLES, false);
        LaidOutDocument laidOut = converter.layOut(new DocumentReader().read(DOCUMENT));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        converter.toPdf(laidOut, out);

        assertEquals("%PDF-", new String(out.toByteArray(), 0, 5, "ISO-8859-1"));
        assertEquals(1, converter.toSvg(laidOut).size());
    }

    @Test
    void anErrorInTheStylesSaysWhichInputAndWhichLine() {
        StxtPdfException e = assertThrows(StxtPdfException.class,
                () -> forRequest(STYLES.replace("Bold: true", "Bold: maybe"), false));

        assertEquals(StxtPdfException.Kind.INVALID_DOCUMENT, e.kind());
        assertTrue(e.inStyles());
        assertEquals(1, e.stylesDocument());
        assertEquals(8, e.line());
        assertFalse(e.code().isBlank());
    }

    @Test
    void anErrorInTheDocumentIsNotInTheStyles() {
        Converter converter = forRequest(STYLES, false);

        StxtPdfException e = assertThrows(StxtPdfException.class,
                () -> pdf(converter, DOCUMENT.replace("Margin: 40", "Margin: wide")));

        assertEquals(StxtPdfException.Kind.INVALID_DOCUMENT, e.kind());
        assertFalse(e.inStyles());
        assertEquals(6, e.line());
    }

    @Test
    void anErrorInTheDataHasItsLineInTheDocumentWithSlots() {
        TransformException e = assertThrows(TransformException.class, () -> new FillJson().fill(new Parser().parse("""
                Document (dev.stxt.pdf):
                \tTitle: <title>
                """), "{}"));

        assertTrue(e.getLine() > 0, "line " + e.getLine());
    }

    @Test
    void aLimitOfTheServiceIsAnErrorOfKindLimit() {
        String longDocument = DOCUMENT.replace("\t\tParagraph: And goodbye.\n",
                "\t\tParagraph: And goodbye.\n".repeat(200));
        Converter converter = SHARED.toBuilder().styles(STYLES).limits(Limits.DEFAULT.withMaxPages(1)).build();

        StxtPdfException e = assertThrows(StxtPdfException.class, () -> pdf(converter, longDocument));

        assertEquals(StxtPdfException.Kind.LIMIT, e.kind());
    }

    @Test
    void aCancelledRequestEndsAsCancelled() {
        Converter converter = forRequest(STYLES, false);
        Cancellation cancelled = () -> true;

        StxtPdfException e = assertThrows(StxtPdfException.class,
                () -> converter.layOut(new DocumentReader().read(DOCUMENT), cancelled));

        assertEquals(StxtPdfException.Kind.CANCELLED, e.kind());
    }
}
