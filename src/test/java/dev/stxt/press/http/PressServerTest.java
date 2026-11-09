package dev.stxt.press.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.stxt.pdf.convert.Converter;
import dev.stxt.pdf.model.Limits;
import dev.stxt.pdf.resources.Resources;
import dev.stxt.press.templates.Templates;

/**
 * The routes of the HTTP service (ROADMAP-PDF 2.4) on a server of the test, on any free port of {@code 127.0.0.1}: the
 * formats, the templates through {@code /print}, the errors with their status, code, line and source, the token, the
 * routes that can be turned off and the limits.
 */
class PressServerTest {

    private static final Converter SHARED = Converter.builder()
            .resources(new Resources(Path.of("..", "stxt-pdf", "resources"))).build();

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

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

    private static final String SAMPLE = """
            {"title": "Sample", "lines": [{"text": "One."}, {"text": "Two."}]}
            """;

    private static final String STYLES = """
            Styles (dev.stxt.pdf):
            \tFor element: Document
            \t\tTypography:
            \t\t\tFont: IBM Plex Sans
            \t\t\tSize: 11
            """;

    @TempDir
    Path temp;

    private Templates templates;
    private PressServer server;

    @BeforeEach
    void tree() throws IOException {
        Path folder = temp.resolve("templates");
        Files.createDirectories(folder.resolve("invoices"));
        Files.writeString(folder.resolve(".styles.stxt"), STYLES);
        Files.writeString(folder.resolve("invoices/invoice.stxt"), WITH_SLOTS);
        Files.writeString(folder.resolve("invoices/invoice.json"), SAMPLE);
        Files.writeString(folder.resolve("hello.stxt"), DOCUMENT);
        templates = new Templates(folder);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private void start(ServerConfig config) throws IOException {
        start(SHARED, config);
    }

    private void start(Converter shared, ServerConfig config) throws IOException {
        server = PressServer.start(shared, templates, config.withAddress("127.0.0.1", 0));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.address().getPort() + path));
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return CLIENT.send(request(path).GET().build(), BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> post(String path, String body) throws Exception {
        return CLIENT.send(request(path).POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofByteArray());
    }

    /** {@return the body of {@code /render} with {@code document}, the styles with the font and {@code more}} */
    private static String render(String document, Map<String, Object> more) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("document", document);
        body.put("styles", List.of(STYLES));
        body.putAll(more);
        return JSON.writeValueAsString(body);
    }

    private static JsonNode json(HttpResponse<byte[]> response) throws IOException {
        return JSON.readTree(response.body());
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static boolean isPdf(byte[] bytes) {
        return bytes.length > 1000 && new String(Arrays.copyOf(bytes, 5), StandardCharsets.ISO_8859_1).equals("%PDF-");
    }

    /** That {@code response} is an error with {@code status} and {@code code}; {@return its body} */
    private static JsonNode error(HttpResponse<byte[]> response, int status, String code) throws IOException {
        assertEquals(status, response.statusCode(), () -> text(response));
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(null));
        JsonNode body = json(response);
        assertEquals(code, body.get("code").asText(), body::toString);
        assertTrue(body.get("message").isTextual());
        return body;
    }

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
            error(post("/render", body), 400, Routes.BODY_NOT_VALID);
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

        for (String path : List.of("/print/missing", "/print/invoices/../hello", "/print/invoices/invoice.json",
                "/print/%2E%2E/hello", "/print/", "/templates/missing")) {
            error(get(path), 404, "TEMPLATE_NOT_FOUND");
        }
    }

    @Test
    void theTemplatesAreListedWithTheirFiles() throws Exception {
        start(ServerConfig.DEFAULT);

        JsonNode list = json(get("/templates")).get("templates");
        JsonNode invoice = json(get("/templates/invoices/invoice"));

        assertEquals("[{\"path\":\"hello\",\"styles\":false,\"data\":false},"
                + "{\"path\":\"invoices/invoice\",\"styles\":false,\"data\":true}]", list.toString());
        assertEquals(WITH_SLOTS, invoice.get("document").asText());
        assertEquals(".styles.stxt", invoice.get("styles").get(0).get("path").asText());
        assertEquals(STYLES, invoice.get("styles").get(0).get("text").asText());
        assertEquals(SAMPLE, invoice.get("data").asText());
        assertTrue(json(get("/templates/hello")).get("data").isNull());
    }

    @Test
    void theSchemaAndTheFontsOfTheServer() throws Exception {
        start(ServerConfig.DEFAULT);

        HttpResponse<byte[]> schema = get("/schema");
        JsonNode fonts = json(get("/fonts")).get("families");

        assertEquals("text/stxt; charset=utf-8", schema.headers().firstValue("Content-Type").orElse(null));
        assertTrue(text(schema).contains("dev.stxt.pdf"));
        assertTrue(fonts.toString().contains("\"IBM Plex Sans\""), fonts::toString);
    }

    @Test
    void theFontsHaveTheirFacesAndTheirFiles() throws Exception {
        start(ServerConfig.DEFAULT);

        JsonNode faces = json(get("/fonts")).get("faces");
        JsonNode bold = null;
        for (JsonNode face : faces) {
            if (face.get("family").asText().equals("IBM Plex Sans") && face.get("weight").asInt() == 700
                    && face.get("style").asText().equals("normal")) {
                bold = face;
            }
        }
        assertTrue(bold != null, faces::toString);
        String id = bold.get("id").asText();
        HttpResponse<byte[]> file = get("/fonts/" + id);

        assertEquals(200, file.statusCode());
        assertEquals("font/ttf", file.headers().firstValue("Content-Type").orElse(null));
        assertEquals(id, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.body())));
        error(get("/fonts/" + "0".repeat(64)), 404, Routes.NOT_FOUND);
        error(get("/fonts/IBM-Plex-Sans"), 404, Routes.NOT_FOUND);
    }

    /** The editor is the build of {@code web/}, which Maven leaves in {@code target/classes/editor} before the tests. */
    @Test
    void theEditorIsServedWithoutToken() throws Exception {
        start(ServerConfig.DEFAULT.withToken("s3cret"));

        HttpResponse<byte[]> page = get("/");
        HttpResponse<byte[]> script = get("/editor/editor.js");

        assertEquals(200, page.statusCode(), () -> text(page));
        assertEquals("text/html; charset=utf-8", page.headers().firstValue("Content-Type").orElse(null));
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElse("").startsWith("default-src 'self'"));
        assertTrue(text(page).contains("editor/editor.js"));
        assertEquals(200, script.statusCode());
        assertEquals("text/javascript; charset=utf-8", script.headers().firstValue("Content-Type").orElse(null));
        assertEquals(200, get("/editor/editor.css").statusCode());
        error(get("/editor/missing.js"), 404, Routes.NOT_FOUND);
        error(get("/editor/.hidden"), 404, Routes.NOT_FOUND);
        error(get("/editor/..%2Fstxt%2Fdev.stxt.press.stxt"), 404, Routes.NOT_FOUND);
        error(get("/editor/"), 404, Routes.NOT_FOUND);
        error(post("/", ""), 405, Routes.METHOD_NOT_ALLOWED);
        error(get("/schema"), 401, Routes.UNAUTHORIZED);
    }

    @Test
    void theEditorCanBeTurnedOff() throws Exception {
        start(ServerConfig.DEFAULT.withRoutes(false, true, true));

        error(get("/"), 404, Routes.NOT_FOUND);
        error(get("/editor/editor.js"), 404, Routes.NOT_FOUND);
        // The fonts are only for the previews of the editor.
        error(get("/fonts"), 404, Routes.NOT_FOUND);
        error(get("/fonts/" + "0".repeat(64)), 404, Routes.NOT_FOUND);
        assertEquals(200, get("/schema").statusCode());
        server.close();
        start(ServerConfig.DEFAULT.withRoutes(false, true, true).withToken("s3cret"));

        error(get("/"), 401, Routes.UNAUTHORIZED);
    }

    @Test
    void theParametersAreChecked() throws Exception {
        start(ServerConfig.DEFAULT);

        error(get("/print/hello?format=docx"), 400, Routes.PARAMETER_NOT_VALID);
        error(get("/print/hello?debug=yes"), 400, Routes.PARAMETER_NOT_VALID);
        error(get("/print/hello?format=pdf&format=svg"), 400, Routes.PARAMETER_NOT_VALID);
        error(get("/print/hello?page=1"), 400, Routes.PARAMETER_NOT_VALID);
        error(get("/fonts?all=true"), 400, Routes.PARAMETER_NOT_VALID);
        assertEquals(200, get("/print/hello?format=svg&debug=true").statusCode());
    }

    @Test
    void aRouteOrAMethodThatDoesNotExist() throws Exception {
        start(ServerConfig.DEFAULT);

        error(get("/pdf"), 404, Routes.NOT_FOUND);
        error(get("/fonts/a/b"), 404, Routes.NOT_FOUND);
        HttpResponse<byte[]> response = get("/render");
        error(response, 405, Routes.METHOD_NOT_ALLOWED);
        assertEquals("POST", response.headers().firstValue("Allow").orElse(null));
        error(post("/templates", ""), 405, Routes.METHOD_NOT_ALLOWED);
    }

    @Test
    void withATokenEveryRouteButHealthAsksForIt() throws Exception {
        start(ServerConfig.DEFAULT.withToken("s3cret"));

        assertEquals(200, get("/health").statusCode());
        HttpResponse<byte[]> without = get("/print/hello");
        error(without, 401, Routes.UNAUTHORIZED);
        assertEquals("Bearer", without.headers().firstValue("WWW-Authenticate").orElse(null));
        error(get("/missing"), 401, Routes.UNAUTHORIZED);
        error(CLIENT.send(request("/fonts").header("Authorization", "Bearer s3cre").GET().build(),
                BodyHandlers.ofByteArray()), 401, Routes.UNAUTHORIZED);
        assertEquals(200, CLIENT.send(request("/fonts").header("Authorization", "Bearer s3cret").GET().build(),
                BodyHandlers.ofByteArray()).statusCode());
        assertEquals(200, CLIENT.send(request("/fonts").header("Authorization", "bearer s3cret").GET().build(),
                BodyHandlers.ofByteArray()).statusCode());
    }

    @Test
    void renderAndTemplatesCanBeTurnedOff() throws Exception {
        start(ServerConfig.DEFAULT.withRoutes(true, false, false));

        error(post("/render", render(DOCUMENT, Map.of())), 404, Routes.NOT_FOUND);
        error(get("/templates"), 404, Routes.NOT_FOUND);
        error(get("/templates/hello"), 404, Routes.NOT_FOUND);
        assertEquals(200, get("/print/hello").statusCode());
    }

    @Test
    void aBodyLargerThanTheLimitIsA413() throws Exception {
        start(ServerConfig.DEFAULT.withLimits(100, Duration.ofSeconds(30), 4));

        error(post("/render", render(DOCUMENT, Map.of())), 413, Routes.BODY_TOO_LARGE);
    }

    @Test
    void aLimitOfTheEngineIsA413() throws Exception {
        start(SHARED.toBuilder().limits(Limits.DEFAULT.withMaxPages(1)).build(), ServerConfig.DEFAULT);

        JsonNode body = error(post("/render", render(DOCUMENT + "\t\tParagraph: More.\n".repeat(200), Map.of())),
                413, "PAGE_LIMIT");

        assertEquals("LIMIT", body.get("kind").asText());
    }

    @Test
    void aConversionOutOfTimeIsA503() throws Exception {
        start(ServerConfig.DEFAULT.withLimits(2 * 1024 * 1024, Duration.ofNanos(1), 4));

        JsonNode body = error(post("/render", render(DOCUMENT, Map.of())), 503, "CANCELLED");

        assertEquals("CANCELLED", body.get("kind").asText());
        assertTrue(body.get("source").isNull());
    }

    @Test
    void theConfigurationIsChecked() {
        for (Runnable bad : List.<Runnable>of(() -> ServerConfig.DEFAULT.withToken(" "),
                () -> ServerConfig.DEFAULT.withAddress("127.0.0.1", 70000),
                () -> ServerConfig.DEFAULT.withLimits(0, Duration.ofSeconds(1), 1),
                () -> ServerConfig.DEFAULT.withLimits(1, Duration.ZERO, 1),
                () -> ServerConfig.DEFAULT.withLimits(1, Duration.ofSeconds(1), 0))) {
            assertThrows(IllegalArgumentException.class, bad::run);
        }
        assertFalse(ServerConfig.DEFAULT.withToken("s3cret").toString().contains("s3cret"));
    }
}
