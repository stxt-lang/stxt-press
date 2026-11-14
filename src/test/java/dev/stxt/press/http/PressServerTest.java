package dev.stxt.press.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import dev.stxt.pdf.convert.Converter;

/**
 * The routes of the clients of the HTTP service on a server of the test ({@link ServerFixture}): {@code /render},
 * {@code /print/{path}}, {@code /documents/{path}} and {@code /health}, their formats, and the errors of a job with their
 * status, code, line and source. The routes of the editor are in {@link EditorRoutesTest}, the start, the warm up, the
 * shutdown and the limits in {@link ServerLifecycleTest}, and the token, the {@code Host} and the routes that are off in
 * {@link ServerAccessTest}.
 */
class PressServerTest extends ServerFixture {

    /** {@code /health} says the versions: that of the engine always, that of the service only from its jar. */
    @Test
    void healthGivesTheVersions() throws Exception {
        start(ServerConfig.DEFAULT);
        JsonNode health = json(get("/health"));
        assertEquals("ok", health.get("status").asText());
        assertTrue(health.get("versions").get("stxt-press").isNull());
        assertEquals(Converter.version(), health.get("versions").get("stxt-pdf").asText());
    }

    @Test
    void renderGivesThePdf() throws Exception {
        start(ServerConfig.DEFAULT);

        HttpResponse<byte[]> response = post("/render", render(DOCUMENT, Map.of()));

        assertEquals(200, response.statusCode(), () -> text(response));
        assertEquals("application/pdf", response.headers().firstValue("Content-Type").orElse(null));
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertTrue(isPdf(response.body()));
    }

    @Test
    void renderFillsWithTheDataAsAJsonValue() throws Exception {
        start(ServerConfig.DEFAULT);
        // Written by hand: a JSON library of the client would read 1.10 as 1.1 before sending it.
        String body = render(WITH_SLOTS, Map.of()).replaceFirst("}$",
                ", \"data\": {\"title\": \"Price 1.10\", \"lines\": [{\"text\": 1.10}]}}");

        HttpResponse<byte[]> stxt = post("/render?format=stxt", body);
        HttpResponse<byte[]> pdf = post("/render", body);

        assertEquals(200, stxt.statusCode(), () -> text(stxt));
        assertEquals("text/stxt; charset=utf-8", stxt.headers().firstValue("Content-Type").orElse(null));
        assertTrue(text(stxt).contains("Title: Price 1.10"), text(stxt));
        assertTrue(text(stxt).contains("Paragraph: 1.10"), "the number as it came: " + text(stxt));
        assertTrue(isPdf(pdf.body()));
    }

    @Test
    void renderGivesOneSvgPerPage() throws Exception {
        start(ServerConfig.DEFAULT);

        HttpResponse<byte[]> response = post("/render?format=svg&debug=true", render(DOCUMENT, Map.of()));

        assertEquals(200, response.statusCode(), () -> text(response));
        JsonNode pages = json(response).get("pages");
        assertEquals(1, pages.size());
        assertTrue(pages.get(0).asText().startsWith("<svg"));
    }

    @Test
    void renderTakesTheOptions() throws Exception {
        start(ServerConfig.DEFAULT);
        Map<String, Object> options = Map.of("options", Map.of("pdfUa", 1, "pdfA", "A2B", "debug", false,
                "exclude", List.of("CONTRAST")));

        HttpResponse<byte[]> response = post("/render", render(DOCUMENT, options));

        assertEquals(200, response.statusCode(), () -> text(response));
        assertTrue(text(response).contains("pdfaid"), "the PDF/A metadata");
    }

    @Test
    void aBodyThatIsNotOneOfRenderIsAnErrorOfTheBody() throws Exception {
        start(ServerConfig.DEFAULT);

        for (String body : List.of("", "not json", "[1]", "{\"styles\": []}", "{\"document\": 1}",
                "{\"document\": \"x\", \"other\": 1}", "{\"document\": \"x\", \"styles\": \"x\"}",
                "{\"document\": \"x\", \"options\": {\"pdfUa\": 3}}",
                "{\"document\": \"x\", \"options\": {\"pdfA\": \"A1B\"}}",
                "{\"document\": \"x\", \"options\": {\"exclude\": [\"NONE\"]}}",
                "{\"document\": \"x\", \"options\": {\"color\": true}}", "{\"document\": \"x\"} {}")) {
            error(post("/render", body), 400, HttpError.BODY_NOT_VALID);
        }
    }

    @Test
    void anErrorOfTheDocumentIsA422WithItsLineAndSource() throws Exception {
        start(ServerConfig.DEFAULT);

        HttpResponse<byte[]> response = post("/render", render(DOCUMENT.replace("Margin: 40", "Margin: wide"),
                Map.of()));

        assertEquals(422, response.statusCode(), () -> text(response));
        JsonNode body = json(response);
        assertEquals("INVALID_DOCUMENT", body.get("kind").asText());
        assertEquals(6, body.get("line").asInt());
        assertEquals("document", body.get("source").asText());
        assertFalse(body.has("filled"));
    }

    @Test
    void anErrorOfTheStylesHasTheirPlace() throws Exception {
        start(ServerConfig.DEFAULT);
        String bad = "Styles (dev.stxt.pdf):\n\tFor element: Paragraph\n\t\tTypography:\n\t\t\tBold: maybe\n";

        JsonNode body = json(post("/render", JSON.writeValueAsString(Map.of("document", DOCUMENT,
                "styles", List.of(STYLES, bad)))));

        assertEquals("styles[2]", body.get("source").asText(), body::toString);
        assertEquals(4, body.get("line").asInt());
    }

    @Test
    void anErrorOfTheFilledDocumentCarriesIt() throws Exception {
        start(ServerConfig.DEFAULT);

        HttpResponse<byte[]> response = post("/render", render(WITH_SLOTS + "\t\tUnknown: here\n",
                Map.of("data", JSON.readTree(SAMPLE))));

        assertEquals(422, response.statusCode());
        JsonNode body = json(response);
        assertEquals("filled", body.get("source").asText());
        assertEquals(9, body.get("line").asInt());
        assertTrue(body.get("filled").asText().contains("Paragraph: Two."));
    }

    @Test
    void getPrintFillsTheTemplateWithItsSampleData() throws Exception {
        start(ServerConfig.DEFAULT);

        HttpResponse<byte[]> pdf = get("/print/invoices/invoice");
        HttpResponse<byte[]> stxt = get("/print/invoices/invoice?format=stxt");

        assertEquals(200, pdf.statusCode(), () -> text(pdf));
        assertTrue(isPdf(pdf.body()));
        assertEquals("inline; filename=\"invoice.pdf\"", pdf.headers().firstValue("Content-Disposition").orElse(null));
        assertTrue(text(stxt).contains("Title: Sample"), text(stxt));
    }

    @Test
    void postPrintFillsTheTemplateWithTheBody() throws Exception {
        start(ServerConfig.DEFAULT);

        HttpResponse<byte[]> stxt = post("/print/invoices/invoice?format=stxt",
                "{\"title\": \"Posted\", \"lines\": [{\"text\": \"Only.\"}]}");
        HttpResponse<byte[]> pdf = post("/print/invoices/invoice", SAMPLE);

        assertTrue(text(stxt).contains("Title: Posted"), text(stxt));
        assertFalse(text(stxt).contains("Two."));
        assertTrue(isPdf(pdf.body()));
    }

    @Test
    void dataOfPrintThatAreNotJsonAreAnErrorOfTheData() throws Exception {
        start(ServerConfig.DEFAULT);

        JsonNode body = error(post("/print/invoices/invoice", "{\n\"title\": }"), 422, "JSON_NOT_VALID");

        assertEquals("data", body.get("source").asText());
        assertEquals(2, body.get("line").asInt());
    }

    @Test
    void aTemplateThatDoesNotExistIsA404() throws Exception {
        start(ServerConfig.DEFAULT);

        for (String path : List.of("/print/missing", "/print/invoices/../hello",
                "/print/invoices/invoice.json", "/print/%2E%2E/hello", "/print/",
                "/files/templates/missing", "/print/letters/letter")) {
            error(get(path), 404, "TEMPLATE_NOT_FOUND");
        }
        // A route without a path is no route, and the files of the editor are under /files/.
        for (String path : List.of("/print", "/documents", "/files", "/files/", "/templates", "/templates/hello")) {
            error(get(path), 404, HttpError.NOT_FOUND);
        }
    }

    @Test
    void aDocumentThatDoesNotExistIsA404() throws Exception {
        start(ServerConfig.DEFAULT);

        for (String path : List.of("/documents/missing", "/documents/letters/../hello",
                "/documents/letters/letter.json", "/documents/.styles", "/documents/",
                "/files/documents/missing", "/documents/invoices/invoice")) {
            error(get(path), 404, "DOCUMENT_NOT_FOUND");
        }
    }

    @Test
    void printConvertsADocumentAsItIsWithItsChainOfStyles() throws Exception {
        start(ServerConfig.DEFAULT);

        HttpResponse<byte[]> pdf = get("/documents/letters/letter");
        HttpResponse<byte[]> svg = get("/documents/letters/letter?format=svg");
        HttpResponse<byte[]> stxt = get("/documents/letters/letter?format=stxt");

        assertEquals(200, pdf.statusCode(), () -> text(pdf));
        assertTrue(isPdf(pdf.body()));
        assertEquals("inline; filename=\"letter.pdf\"", pdf.headers().firstValue("Content-Disposition").orElse(null));
        // The colour of letter.styles.stxt, after the root .styles.stxt with the font.
        assertTrue(json(svg).get("pages").get(0).asText().toLowerCase(java.util.Locale.ROOT).contains("#123456"),
                () -> text(svg));
        // Without data the document is as it is: the letter.json beside it is not read.
        assertEquals(DOCUMENT, text(stxt));
        error(post("/documents/letters/letter", SAMPLE), 405, HttpError.METHOD_NOT_ALLOWED);
    }
}
