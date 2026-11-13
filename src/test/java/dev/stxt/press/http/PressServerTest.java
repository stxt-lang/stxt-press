package dev.stxt.press.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.stxt.pdf.convert.Converter;
import dev.stxt.pdf.model.Limits;
import dev.stxt.pdf.resources.Resources;
import dev.stxt.press.Options;
import dev.stxt.press.Press;
import dev.stxt.press.templates.TemplateException;
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

    private static final String LETTER_STYLES = """
            Styles (dev.stxt.pdf):
            \tFor element: Paragraph
            \t\tTypography:
            \t\t\tColor: #123456
            """;

    @TempDir
    Path temp;

    private Templates templates;
    private Templates documents;
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

        Path docs = temp.resolve("documents");
        Files.createDirectories(docs.resolve("letters"));
        Files.writeString(docs.resolve(".styles.stxt"), STYLES);
        Files.writeString(docs.resolve("hello.stxt"), DOCUMENT);
        Files.writeString(docs.resolve("letters/letter.stxt"), DOCUMENT);
        Files.writeString(docs.resolve("letters/letter.styles.stxt"), LETTER_STYLES);
        // Not read: a document has no data.
        Files.writeString(docs.resolve("letters/letter.json"), SAMPLE);
        documents = new Templates(docs, Templates.Kind.DOCUMENT);
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
        server = PressServer.start(shared, templates, documents, config.withAddress("127.0.0.1", 0));
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
        // An error carries text of the request: the browser takes it as JSON and nothing else.
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
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

        HttpResponse<byte[]> pdf = get("/print/templates/invoices/invoice");
        HttpResponse<byte[]> stxt = get("/print/templates/invoices/invoice?format=stxt");

        assertEquals(200, pdf.statusCode(), () -> text(pdf));
        assertTrue(isPdf(pdf.body()));
        assertEquals("inline; filename=\"invoice.pdf\"", pdf.headers().firstValue("Content-Disposition").orElse(null));
        assertTrue(text(stxt).contains("Title: Sample"), text(stxt));
    }

    @Test
    void postPrintFillsTheTemplateWithTheBody() throws Exception {
        start(ServerConfig.DEFAULT);

        HttpResponse<byte[]> stxt = post("/print/templates/invoices/invoice?format=stxt",
                "{\"title\": \"Posted\", \"lines\": [{\"text\": \"Only.\"}]}");
        HttpResponse<byte[]> pdf = post("/print/templates/invoices/invoice", SAMPLE);

        assertTrue(text(stxt).contains("Title: Posted"), text(stxt));
        assertFalse(text(stxt).contains("Two."));
        assertTrue(isPdf(pdf.body()));
    }

    @Test
    void dataOfPrintThatAreNotJsonAreAnErrorOfTheData() throws Exception {
        start(ServerConfig.DEFAULT);

        JsonNode body = error(post("/print/templates/invoices/invoice", "{\n\"title\": }"), 422, "JSON_NOT_VALID");

        assertEquals("data", body.get("source").asText());
        assertEquals(2, body.get("line").asInt());
    }

    @Test
    void aTemplateThatDoesNotExistIsA404() throws Exception {
        start(ServerConfig.DEFAULT);

        for (String path : List.of("/print/templates/missing", "/print/templates/invoices/../hello",
                "/print/templates/invoices/invoice.json", "/print/templates/%2E%2E/hello", "/print/templates/",
                "/templates/missing", "/print/templates/letters/letter")) {
            error(get(path), 404, "TEMPLATE_NOT_FOUND");
        }
        // /print is /print/templates or /print/documents.
        error(get("/print/hello"), 404, HttpError.NOT_FOUND);
        error(get("/print/"), 404, HttpError.NOT_FOUND);
    }

    @Test
    void aDocumentThatDoesNotExistIsA404() throws Exception {
        start(ServerConfig.DEFAULT);

        for (String path : List.of("/print/documents/missing", "/print/documents/letters/../hello",
                "/print/documents/letters/letter.json", "/print/documents/.styles", "/print/documents/",
                "/documents/missing", "/print/documents/invoices/invoice")) {
            error(get(path), 404, "DOCUMENT_NOT_FOUND");
        }
    }

    @Test
    void printConvertsADocumentAsItIsWithItsChainOfStyles() throws Exception {
        start(ServerConfig.DEFAULT);

        HttpResponse<byte[]> pdf = get("/print/documents/letters/letter");
        HttpResponse<byte[]> svg = get("/print/documents/letters/letter?format=svg");
        HttpResponse<byte[]> stxt = get("/print/documents/letters/letter?format=stxt");

        assertEquals(200, pdf.statusCode(), () -> text(pdf));
        assertTrue(isPdf(pdf.body()));
        assertEquals("inline; filename=\"letter.pdf\"", pdf.headers().firstValue("Content-Disposition").orElse(null));
        // The colour of letter.styles.stxt, after the root .styles.stxt with the font.
        assertTrue(json(svg).get("pages").get(0).asText().toLowerCase(java.util.Locale.ROOT).contains("#123456"),
                () -> text(svg));
        // Without data the document is as it is: the letter.json beside it is not read.
        assertEquals(DOCUMENT, text(stxt));
        error(post("/print/documents/letters/letter", SAMPLE), 405, HttpError.METHOD_NOT_ALLOWED);
    }

    @Test
    void theDocumentsAreListedWithTheirFiles() throws Exception {
        start(ServerConfig.DEFAULT);

        JsonNode list = json(get("/documents")).get("documents");
        JsonNode letter = json(get("/documents/letters/letter"));

        assertEquals("[{\"path\":\"hello\",\"styles\":false},{\"path\":\"letters/letter\",\"styles\":true}]",
                list.toString());
        assertEquals(DOCUMENT, letter.get("document").asText());
        assertEquals(List.of(".styles.stxt", "letters/letter.styles.stxt"),
                letter.get("styles").findValuesAsText("path"));
        assertFalse(letter.has("data"));
    }

    @Test
    void aServerWithoutFoldersHasNoTemplatesOrDocuments() throws Exception {
        server = PressServer.start(SHARED, null, null, ServerConfig.DEFAULT.withAddress("127.0.0.1", 0));

        assertEquals("{\"templates\":[]}", text(get("/templates")));
        assertEquals("{\"documents\":[]}", text(get("/documents")));
        error(get("/print/templates/hello"), 404, "TEMPLATE_NOT_FOUND");
        error(get("/print/documents/hello"), 404, "DOCUMENT_NOT_FOUND");
        error(get("/documents/hello"), 404, "DOCUMENT_NOT_FOUND");
    }

    @Test
    void eachFolderIsOfItsKind() {
        assertThrows(IllegalArgumentException.class,
                () -> PressServer.start(SHARED, documents, null, ServerConfig.DEFAULT.withAddress("127.0.0.1", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> PressServer.start(SHARED, null, templates, ServerConfig.DEFAULT.withAddress("127.0.0.1", 0)));
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
        error(get("/fonts/" + "0".repeat(64)), 404, HttpError.NOT_FOUND);
        error(get("/fonts/IBM-Plex-Sans"), 404, HttpError.NOT_FOUND);
    }

    /**
     * The index and the editor are the build of {@code web/}, which Maven leaves in {@code target/classes/editor} before
     * the tests.
     */
    @Test
    void theIndexAndTheEditorAreServedWithoutToken() throws Exception {
        start(ServerConfig.DEFAULT.withToken("s3cret"));

        HttpResponse<byte[]> index = get("/");
        assertEquals(200, index.statusCode(), () -> text(index));
        assertEquals("text/html; charset=utf-8", index.headers().firstValue("Content-Type").orElse(null));
        assertTrue(index.headers().firstValue("Content-Security-Policy").orElse("").startsWith("default-src 'self'"));
        assertTrue(text(index).contains("/assets/index.js"));

        // One page for the blank document and for every template and document, which exists or not: that is not told
        // without token.
        for (String url : List.of("/editor", "/editor/templates/hello", "/editor/templates/catalogs/catalog",
                "/editor/templates/missing/one", "/editor/documents/hello", "/editor/documents/reference/tables/basic")) {
            HttpResponse<byte[]> page = get(url);
            assertEquals(200, page.statusCode(), url);
            String policy = page.headers().firstValue("Content-Security-Policy").orElse("");
            assertTrue(policy.startsWith("default-src 'self'"), policy);
            // The PDF is shown in a frame of a blob: URL; nothing else is framed.
            assertTrue(policy.contains("; frame-src blob:;"), policy);
            assertTrue(text(page).contains("/assets/editor.js"), url);
        }
        for (String url : List.of("/editor/templates/", "/editor/documents/", "/editor/templates/a//b",
                "/editor/templates/a/", "/editor/documents/a.b", "/editor/templates/..%2Fx", "/editor/documents/a%2Fb")) {
            error(get(url), 404, HttpError.NOT_FOUND);
        }
        // Any other path under /editor is not a page, and asks for the token as any route that does not exist.
        for (String url : List.of("/editor/", "/editor/hello", "/editor/editor.js", "/editor/template/hello")) {
            error(get(url), 401, HttpError.UNAUTHORIZED);
        }
        error(get("/editor/templates/hello?mode=document"), 400, HttpError.PARAMETER_NOT_VALID);
        error(get("/editor?format=pdf"), 400, HttpError.PARAMETER_NOT_VALID);

        HttpResponse<byte[]> script = get("/assets/editor.js");
        assertEquals(200, script.statusCode());
        assertEquals("text/javascript; charset=utf-8", script.headers().firstValue("Content-Type").orElse(null));
        assertEquals(200, get("/assets/index.js").statusCode());
        assertEquals(200, get("/assets/editor.css").statusCode());
        error(get("/assets/missing.js"), 404, HttpError.NOT_FOUND);
        error(get("/assets/.hidden"), 404, HttpError.NOT_FOUND);
        error(get("/assets/..%2Fstxt%2Fdev.stxt.press.stxt"), 404, HttpError.NOT_FOUND);
        error(get("/assets/"), 404, HttpError.NOT_FOUND);
        // A page has one URL.
        error(get("/assets/index.html"), 404, HttpError.NOT_FOUND);
        error(get("/assets/editor.html"), 404, HttpError.NOT_FOUND);

        error(post("/", ""), 405, HttpError.METHOD_NOT_ALLOWED);
        error(post("/editor/templates/hello", ""), 405, HttpError.METHOD_NOT_ALLOWED);
        error(get("/schema"), 401, HttpError.UNAUTHORIZED);
        error(get("/templates/hello"), 401, HttpError.UNAUTHORIZED);
        error(get("/documents/hello"), 401, HttpError.UNAUTHORIZED);
    }

    /** A server that warms up answers every request with 503, /health too, until the warm up ends. */
    @Test
    void aServerThatWarmsUpAnswers503UntilItEnds() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server = PressServer.startWarmingUp(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return SHARED;
        }, templates, documents, ServerConfig.DEFAULT.withAddress("127.0.0.1", 0));
        PressServer warming = server;
        Thread warmUp = new Thread(warming::warmUp);
        warmUp.start();

        HttpResponse<byte[]> health = get("/health");
        error(health, 503, HttpError.SERVER_STARTING);
        assertEquals("1", health.headers().firstValue("Retry-After").orElse(null));
        error(get("/print/templates/hello"), 503, HttpError.SERVER_STARTING);
        error(get("/"), 503, HttpError.SERVER_STARTING);

        release.countDown();
        warmUp.join(60_000);
        assertFalse(warmUp.isAlive());
        assertEquals(200, get("/health").statusCode());
        assertEquals(200, get("/print/templates/hello").statusCode());
    }

    /**
     * The warm up converts every template with its sample data and every document; one that fails is reported, and that
     * of a template without sample data says so, since a template with Slots needs the data of the request.
     */
    @Test
    void theWarmUpConvertsEveryTemplateAndDocument() throws Exception {
        Path folder = templates.folder();
        Files.writeString(folder.resolve("broken.stxt"), "Document (dev.stxt.pdf):\n\tColour: red\n");
        Files.writeString(folder.resolve("broken.json"), "{}");
        // A template without sample data: its failure is only information.
        Files.writeString(folder.resolve("plain.stxt"), "Document (dev.stxt.pdf):\n\tColour: red\n");

        // A document that fails is always a warning: it has no data to wait for.
        Files.writeString(documents.folder().resolve("broken.stxt"), "Document (dev.stxt.pdf):\n\tColour: red\n");

        WarmUp.Result result = WarmUp.run(new Press(SHARED), templates, documents, "IBM Plex Sans", Options.DEFAULT,
                Duration.ofSeconds(30));

        assertEquals(4, result.converted(), result::toString);
        assertEquals(List.of("broken", "plain", "broken"), result.failures().stream().map(WarmUp.Failure::path).toList());
        assertEquals(List.of(Templates.Kind.TEMPLATE, Templates.Kind.TEMPLATE, Templates.Kind.DOCUMENT),
                result.failures().stream().map(WarmUp.Failure::kind).toList());
        assertTrue(result.failures().get(0).withData());
        assertFalse(result.failures().get(1).withData());
        assertTrue(result.failures().get(2).withData());
        assertTrue(result.failures().get(0).error().startsWith("NODE_NOT_DEFINED_IN_SCHEMA"), result::toString);

        WarmUp.Result none = WarmUp.run(new Press(SHARED), null, null, "IBM Plex Sans", Options.DEFAULT,
                Duration.ofSeconds(30));
        assertEquals(1, none.converted(), none::toString);
        assertEquals(List.of(), none.failures());
    }

    /**
     * The shutdown of {@code start} (SIGTERM, Ctrl+C): at once without requests, and with one in progress, it waits for
     * it and answers the new ones with {@code 503}.
     */
    @Test
    void closingWaitsOnlyForTheRequestsInProgress() throws Exception {
        start(ServerConfig.DEFAULT);
        long started = System.nanoTime();
        server.close(Duration.ofSeconds(30));
        server = null;
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5, "without requests it stops at once");

        start(ServerConfig.DEFAULT);
        PressServer closing = server;
        byte[] body = "{\"document\": \"Document (dev.stxt.pdf):\"}".getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", closing.address().getPort())) {
            // A request whose body has not all arrived: it is in progress until the rest comes.
            OutputStream out = socket.getOutputStream();
            out.write(("POST /render?format=stxt HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n"
                    + "Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body, 0, 10);
            out.flush();
            long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (closing.gate().active() == 0 && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertEquals(1, closing.gate().active());

            started = System.nanoTime();
            Thread close = new Thread(() -> closing.close(Duration.ofSeconds(30)));
            close.start();
            HttpResponse<byte[]> refused = get("/health");
            while (refused.statusCode() == 200 && System.nanoTime() < until) {
                refused = get("/health");
            }
            error(refused, 503, HttpError.SERVER_BUSY);
            assertTrue(close.isAlive(), "it waits for the request in progress");

            out.write(body, 10, body.length - 10);
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("HTTP/1.1 200 OK", in.readLine());
            close.join(10_000);
            assertFalse(close.isAlive(), "it stops when the request in progress ends");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 10);
        } finally {
            server = null;
        }
    }

    /**
     * The shutdown with a request that does not end in time: {@code close(grace)} returns when the grace runs out, and
     * the request is cut without a response.
     */
    @Test
    void closingCutsWhatDoesNotEndInTime() throws Exception {
        start(ServerConfig.DEFAULT);
        PressServer closing = server;
        try (Socket socket = new Socket("127.0.0.1", closing.address().getPort())) {
            // A body that never arrives whole: the request is in progress until the JDK closes it, after
            // sun.net.httpserver.maxReqTime (3 s in the tests).
            OutputStream out = socket.getOutputStream();
            out.write(("POST /render HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n"
                    + "Content-Length: 100\r\n\r\n{").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (closing.gate().active() == 0 && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertEquals(1, closing.gate().active());

            long started = System.nanoTime();
            closing.close(Duration.ofMillis(300));
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            assertTrue(took.toMillis() >= 300, "it waits for the request up to the grace: " + took);
            // Well before the JDK would close the request itself.
            assertTrue(took.toMillis() < 2500, "it stops when the grace runs out: " + took);

            socket.setSoTimeout(5000);
            try {
                assertEquals(-1, socket.getInputStream().read(), "the request is cut without a response");
            } catch (IOException e) {
                // Reset: cut too.
            }
        } finally {
            server = null;
        }
    }

    /**
     * With every conversion that can run at once running, a new one is answered at once with {@code 503} and
     * {@code Retry-After}: there is no queue. A fill and {@code /health}, which are no conversions, answer all the
     * same. The test takes the permits instead of running conversions, which at once would be fragile.
     */
    @Test
    void aConversionThatDoesNotFitIsAnswered503() throws Exception {
        start(ServerConfig.DEFAULT);
        Semaphore permits = server.conversions().permits();
        int taken = permits.drainPermits();
        assertEquals(ServerConfig.DEFAULT.concurrentConversions(), taken);
        try {
            HttpResponse<byte[]> busy = post("/render", render(DOCUMENT, Map.of()));
            error(busy, 503, HttpError.SERVER_BUSY);
            assertEquals("1", busy.headers().firstValue("Retry-After").orElse(null));
            error(get("/print/templates/hello?format=svg"), 503, HttpError.SERVER_BUSY);

            HttpResponse<byte[]> filled = get("/print/templates/invoices/invoice?format=stxt");
            assertEquals(200, filled.statusCode(), () -> text(filled));
            assertEquals(200, get("/health").statusCode());
        } finally {
            permits.release(taken);
        }
        assertEquals(taken, permits.availablePermits(), "a conversion refused gives back no permit it did not take");

        HttpResponse<byte[]> pdf = post("/render", render(DOCUMENT, Map.of()));
        assertEquals(200, pdf.statusCode(), () -> text(pdf));
        assertTrue(isPdf(pdf.body()));
    }

    /**
     * A client that sends the headers of a request and not its body keeps a thread of the pool while it waits for it.
     * As many as the threads leave the server without answering, {@code /health} too, until the JDK closes their
     * connections: after {@code sun.net.httpserver.maxReqTime}, which the pom sets to 3 s for the tests.
     */
    @Test
    void aClientThatDoesNotSendTheBodyLosesItsThread() throws Exception {
        assertEquals("3", System.getProperty(PressServer.MAX_REQUEST_TIME), "set by the argLine of surefire");
        // One conversion at once: the pool has its 4 threads, the least.
        start(ServerConfig.DEFAULT.withLimits(2 * 1024 * 1024, Duration.ofSeconds(30), 1));
        List<Socket> slow = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                Socket socket = new Socket("127.0.0.1", server.address().getPort());
                slow.add(socket);
                socket.getOutputStream().write(("POST /render HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                        + "Content-Type: application/json\r\nContent-Length: 100\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            }
            long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (server.gate().active() < 4 && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertEquals(4, server.gate().active(), "every thread waits for a body");
            assertThrows(HttpTimeoutException.class, () -> CLIENT.send(request("/health")
                    .timeout(Duration.ofMillis(500)).GET().build(), BodyHandlers.ofByteArray()));

            HttpResponse<byte[]> health = null;
            while (health == null && System.nanoTime() < until) {
                try {
                    health = CLIENT.send(request("/health").timeout(Duration.ofSeconds(2)).GET().build(),
                            BodyHandlers.ofByteArray());
                } catch (IOException e) {
                    // Still without threads, or the JDK closed this one too while it waited for a thread.
                }
            }
            assertTrue(health != null && health.statusCode() == 200, "the server answers again");
            for (Socket socket : slow) {
                // Closed by the server: no response, since there is no one to give it to.
                socket.setSoTimeout(5000);
                try {
                    assertEquals(-1, socket.getInputStream().read());
                } catch (IOException e) {
                    // Reset: the 100 bytes it was waiting for never came.
                }
            }
        } finally {
            for (Socket socket : slow) {
                socket.close();
            }
        }
    }

    /**
     * The time limits that {@code start} sets: the request has a fixed time, and the response the timeout of a
     * conversion and a margin, so that a conversion in time is never cut.
     */
    @Test
    void theTimeLimitsFollowTheTimeoutOfAConversion() {
        assertEquals(Map.of(PressServer.MAX_REQUEST_TIME, "30", PressServer.MAX_RESPONSE_TIME, "60"),
                PressServer.timeLimits(Duration.ofSeconds(30)));
        assertEquals("32", PressServer.timeLimits(Duration.ofMillis(1500)).get(PressServer.MAX_RESPONSE_TIME));
        // Too long to be counted: no limit, as the JDK takes a negative number.
        assertEquals("-1", PressServer.timeLimits(Duration.ofSeconds(Long.MAX_VALUE))
                .get(PressServer.MAX_RESPONSE_TIME));
    }

    /** The status and the body of a response to {@link #raw}. */
    private record Raw(int status, String body) {
    }

    /**
     * {@return the response to a request written as it is, with these headers} The {@code HttpClient} of the JDK does
     * not let a test set {@code Host}.
     */
    private Raw raw(String requestLine, String... headers) throws IOException {
        StringBuilder request = new StringBuilder(requestLine).append("\r\n");
        for (String header : headers) {
            request.append(header).append("\r\n");
        }
        request.append("Connection: close\r\n\r\n");
        try (Socket socket = new Socket("127.0.0.1", server.address().getPort())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Raw(Integer.parseInt(response.substring("HTTP/1.1 ".length(), "HTTP/1.1 ".length() + 3)),
                    response.substring(response.indexOf("\r\n\r\n") + 4));
        }
    }

    /**
     * A server without token on {@code 127.0.0.1} answers only to the local names in {@code Host}: a page of another
     * site that makes its name resolve to {@code 127.0.0.1} (DNS rebinding) gets nothing, not even {@code /health}.
     */
    @Test
    void aLocalServerWithoutTokenAnswersOnlyToLocalNames() throws Exception {
        start(ServerConfig.DEFAULT);
        int port = server.address().getPort();

        for (String host : List.of("localhost:" + port, "127.0.0.1:" + port, "[::1]:" + port, "LocalHost", "127.0.0.1",
                "localhost:")) {
            assertEquals(200, raw("GET /templates HTTP/1.1", "Host: " + host).status(), host);
        }
        for (String host : List.of("evil.example:" + port, "evil.example", "localhost.evil.example",
                "127.0.0.1.nip.io:" + port, "localhost.", "localhost:80:80", "[::1", "[::2]:" + port)) {
            for (String path : List.of("/templates", "/templates/hello", "/health", "/", "/editor", "/print/templates/hello")) {
                Raw response = raw("GET " + path + " HTTP/1.1", "Host: " + host);
                assertEquals(403, response.status(), host + " " + path);
                JsonNode body = JSON.readTree(response.body());
                assertEquals(HttpError.HOST_NOT_ALLOWED, body.get("code").asText());
                assertTrue(body.get("message").asText().contains("localhost, 127.0.0.1, [::1]"), body::toString);
            }
        }
        // Without Host, as HTTP/1.0 allows, or with two. Two are refused by the later updates of the JDK 17 too, with a
        // 400 of their own before the handler (17.0.13 lets them through): refused either way.
        assertEquals(403, raw("GET /health HTTP/1.0").status());
        int twoHosts = raw("GET /health HTTP/1.1", "Host: localhost", "Host: evil.example").status();
        assertTrue(twoHosts == 403 || twoHosts == 400, () -> "two Host: " + twoHosts);
    }

    /** With a token, the page of another site cannot do anything: the server answers to any {@code Host}. */
    @Test
    void withATokenAnyHostIsAnswered() throws Exception {
        start(ServerConfig.DEFAULT.withToken("s3cret"));

        assertEquals(200, raw("GET /health HTTP/1.1", "Host: evil.example").status());
        assertEquals(401, raw("GET /templates HTTP/1.1", "Host: evil.example").status());
        assertEquals(200, raw("GET /templates HTTP/1.1", "Host: evil.example", "Authorization: Bearer s3cret").status());
    }

    /**
     * The names a server without token answers to, by the address it listens on: only on a loopback one, which a
     * proxy does not reach, and with the {@code Host} of its configuration besides the local names.
     */
    @Test
    void theLocalNamesDependOnTheAddressAndTheToken() throws Exception {
        Set<String> local = Set.of("localhost", "127.0.0.1", "[::1]");
        assertEquals(local, Gate.localHosts(ServerConfig.DEFAULT, InetAddress.getByName("127.0.0.1")));
        assertEquals(local, Gate.localHosts(ServerConfig.DEFAULT.withAddress("::1", 0),
                InetAddress.getByName("::1")));
        assertEquals(Set.of("localhost", "127.0.0.1", "[::1]", "127.0.0.2"),
                Gate.localHosts(ServerConfig.DEFAULT.withAddress("127.0.0.2", 0), InetAddress.getByName("127.0.0.2")));
        assertEquals(Set.of("localhost", "127.0.0.1", "[::1]", "[0:0:0:0:0:0:0:1]"), Gate.localHosts(
                ServerConfig.DEFAULT.withAddress("0:0:0:0:0:0:0:1", 0), InetAddress.getByName("::1")));
        // Any Host: on every address, or on another one, which is that of a proxy; or with a token.
        assertNull(Gate.localHosts(ServerConfig.DEFAULT.withAddress("0.0.0.0", 0), InetAddress.getByName("0.0.0.0")));
        assertNull(Gate.localHosts(ServerConfig.DEFAULT.withAddress("192.0.2.1", 0),
                InetAddress.getByName("192.0.2.1")));
        assertNull(Gate.localHosts(ServerConfig.DEFAULT.withToken("s3cret"), InetAddress.getByName("127.0.0.1")));
    }

    @Test
    void theEditorCanBeTurnedOff() throws Exception {
        start(ServerConfig.DEFAULT.withRoutes(false, true, true, true));

        error(get("/"), 404, HttpError.NOT_FOUND);
        error(get("/editor"), 404, HttpError.NOT_FOUND);
        error(get("/editor/templates/hello"), 404, HttpError.NOT_FOUND);
        error(get("/editor/documents/hello"), 404, HttpError.NOT_FOUND);
        error(get("/assets/editor.js"), 404, HttpError.NOT_FOUND);
        // The fonts are only for the previews of the editor.
        error(get("/fonts"), 404, HttpError.NOT_FOUND);
        error(get("/fonts/" + "0".repeat(64)), 404, HttpError.NOT_FOUND);
        assertEquals(200, get("/schema").statusCode());
        server.close();
        start(ServerConfig.DEFAULT.withRoutes(false, true, true, true).withToken("s3cret"));

        error(get("/"), 401, HttpError.UNAUTHORIZED);
    }

    @Test
    void theParametersAreChecked() throws Exception {
        start(ServerConfig.DEFAULT);

        error(get("/print/templates/hello?format=docx"), 400, HttpError.PARAMETER_NOT_VALID);
        error(get("/print/templates/hello?debug=yes"), 400, HttpError.PARAMETER_NOT_VALID);
        error(get("/print/templates/hello?format=pdf&format=svg"), 400, HttpError.PARAMETER_NOT_VALID);
        error(get("/print/templates/hello?page=1"), 400, HttpError.PARAMETER_NOT_VALID);
        error(get("/fonts?all=true"), 400, HttpError.PARAMETER_NOT_VALID);
        assertEquals(200, get("/print/templates/hello?format=svg&debug=true").statusCode());
    }

    @Test
    void aRouteOrAMethodThatDoesNotExist() throws Exception {
        start(ServerConfig.DEFAULT);

        error(get("/pdf"), 404, HttpError.NOT_FOUND);
        error(get("/fonts/a/b"), 404, HttpError.NOT_FOUND);
        HttpResponse<byte[]> response = get("/render");
        error(response, 405, HttpError.METHOD_NOT_ALLOWED);
        assertEquals("POST", response.headers().firstValue("Allow").orElse(null));
        error(post("/templates", ""), 405, HttpError.METHOD_NOT_ALLOWED);
    }

    @Test
    void withATokenEveryRouteButHealthAsksForIt() throws Exception {
        start(ServerConfig.DEFAULT.withToken("s3cret"));

        assertEquals(200, get("/health").statusCode());
        HttpResponse<byte[]> without = get("/print/templates/hello");
        error(without, 401, HttpError.UNAUTHORIZED);
        assertEquals("Bearer", without.headers().firstValue("WWW-Authenticate").orElse(null));
        error(get("/missing"), 401, HttpError.UNAUTHORIZED);
        error(CLIENT.send(request("/fonts").header("Authorization", "Bearer s3cre").GET().build(),
                BodyHandlers.ofByteArray()), 401, HttpError.UNAUTHORIZED);
        assertEquals(200, CLIENT.send(request("/fonts").header("Authorization", "Bearer s3cret").GET().build(),
                BodyHandlers.ofByteArray()).statusCode());
        assertEquals(200, CLIENT.send(request("/fonts").header("Authorization", "bearer s3cret").GET().build(),
                BodyHandlers.ofByteArray()).statusCode());
    }

    @Test
    void renderTemplatesAndDocumentsCanBeTurnedOff() throws Exception {
        start(ServerConfig.DEFAULT.withRoutes(true, false, false, false));

        error(post("/render", render(DOCUMENT, Map.of())), 404, HttpError.NOT_FOUND);
        error(get("/templates"), 404, HttpError.NOT_FOUND);
        error(get("/templates/hello"), 404, HttpError.NOT_FOUND);
        error(get("/documents"), 404, HttpError.NOT_FOUND);
        error(get("/documents/hello"), 404, HttpError.NOT_FOUND);
        // /print is always on.
        assertEquals(200, get("/print/templates/hello").statusCode());
        assertEquals(200, get("/print/documents/hello").statusCode());
    }

    @Test
    void aBodyLargerThanTheLimitIsA413() throws Exception {
        start(ServerConfig.DEFAULT.withLimits(100, Duration.ofSeconds(30), 4));

        error(post("/render", render(DOCUMENT, Map.of())), 413, HttpError.BODY_TOO_LARGE);
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

    /**
     * A fill ({@code ?format=stxt}) reads no resources, so on a lazy server it does not create the converter: with
     * resources that cannot be read, a fill is answered and a conversion is not.
     */
    @Test
    void aFillDoesNotNeedTheConverterOfALazyServer() throws Exception {
        server = PressServer.startLazily(() -> {
            throw new IllegalStateException("The resources cannot be read");
        }, templates, documents, ServerConfig.DEFAULT.withAddress("127.0.0.1", 0));

        HttpResponse<byte[]> filled = post("/render?format=stxt", render(WITH_SLOTS, Map.of("data", JSON.readTree(SAMPLE))));
        assertEquals(200, filled.statusCode(), () -> text(filled));
        assertTrue(text(filled).contains("Title: Sample"), () -> text(filled));
        HttpResponse<byte[]> print = post("/print/templates/invoices/invoice?format=stxt", "{\"title\": \"Posted\", \"lines\": []}");
        assertEquals(200, print.statusCode(), () -> text(print));
        assertTrue(text(print).contains("Title: Posted"), () -> text(print));
        assertEquals(200, get("/print/templates/invoices/invoice?format=stxt").statusCode());

        error(post("/render", render(DOCUMENT, Map.of())), 500, HttpError.INTERNAL);
        error(get("/fonts"), 500, HttpError.INTERNAL);
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

    // ---- The save

    /** A {@code PUT} with a body of {@code type} and, if not {@code null}, {@code If-Match}. */
    private HttpResponse<byte[]> put(String path, String body, String type, String match) throws Exception {
        HttpRequest.Builder builder = request(path).PUT(BodyPublishers.ofString(body)).header("Content-Type", type);
        if (match != null) {
            builder.header("If-Match", match);
        }
        return CLIENT.send(builder.build(), BodyHandlers.ofByteArray());
    }

    private static String etag(HttpResponse<byte[]> response) {
        return response.headers().firstValue("ETag").orElseThrow();
    }

    @Test
    void theTextsHaveTheirVersionAndSayWhetherTheyAreSaved() throws Exception {
        start(ServerConfig.DEFAULT);

        HttpResponse<byte[]> invoice = get("/templates/invoices/invoice");
        HttpResponse<byte[]> letter = get("/documents/letters/letter");

        assertTrue(etag(invoice).matches("\"[0-9a-f]{64}\""), etag(invoice));
        assertEquals("\"" + templates.get("invoices/invoice").version() + "\"", etag(invoice));
        assertFalse(json(invoice).get("writable").asBoolean());
        assertFalse(json(letter).get("writable").asBoolean());
        // Off by default: the method is not one of the route.
        HttpResponse<byte[]> refused = put("/templates/hello", "{\"document\": \"x\"}", "application/json", etag(invoice));
        error(refused, 405, HttpError.METHOD_NOT_ALLOWED);
        assertEquals("GET", refused.headers().firstValue("Allow").orElse(null));
        assertEquals(DOCUMENT, Files.readString(templates.folder().resolve("hello.stxt")));
    }

    @Test
    void aSaveWritesTheFilesOfItsVersion() throws Exception {
        start(ServerConfig.DEFAULT.withSave(true));
        HttpResponse<byte[]> before = get("/templates/invoices/invoice");
        assertTrue(json(before).get("writable").asBoolean());
        String edited = WITH_SLOTS.replace("Paper: A4", "Paper: A5");

        HttpResponse<byte[]> saved = put("/templates/invoices/invoice",
                JSON.writeValueAsString(Map.of("document", edited, "styles", List.of(Map.of("path", ".styles.stxt",
                        "text", STYLES)))), "application/json; charset=utf-8", etag(before));

        assertEquals(200, saved.statusCode(), () -> text(saved));
        assertEquals("[\"invoices/invoice.stxt\"]", json(saved).get("written").toString());
        assertEquals(edited, json(saved).get("document").asText());
        assertEquals(edited, Files.readString(templates.folder().resolve("invoices/invoice.stxt")));
        assertFalse(etag(saved).equals(etag(before)));
        assertEquals(etag(saved), etag(get("/templates/invoices/invoice")));

        // From the version before, someone else's save would be undone: nothing is written.
        JsonNode stale = error(put("/templates/invoices/invoice", JSON.writeValueAsString(Map.of("document", WITH_SLOTS)),
                "application/json", etag(before)), 409, TemplateException.FILES_CHANGED);
        assertTrue(stale.get("message").asText().contains("invoices/invoice"));
        assertEquals(edited, Files.readString(templates.folder().resolve("invoices/invoice.stxt")));
    }

    @Test
    void aSaveOfADocument() throws Exception {
        start(ServerConfig.DEFAULT.withSave(true));
        String version = etag(get("/documents/letters/letter"));

        HttpResponse<byte[]> saved = put("/documents/letters/letter", JSON.writeValueAsString(Map.of("document",
                DOCUMENT.replace("Hello.", "Saved."))), "application/json", "W/" + version);

        assertEquals(200, saved.statusCode(), () -> text(saved));
        assertTrue(Files.readString(documents.folder().resolve("letters/letter.stxt")).contains("Saved."));
        assertFalse(json(saved).has("data"));
        error(put("/documents/letters/letter", JSON.writeValueAsString(Map.of("document", DOCUMENT, "data", "{}")),
                "application/json", etag(saved)), 400, HttpError.BODY_NOT_VALID);
    }

    @Test
    void aSaveThatIsNotValidWritesNothing() throws Exception {
        start(ServerConfig.DEFAULT.withSave(true).withToken("s3cret"));
        String body = JSON.writeValueAsString(Map.of("document", "changed"));
        String version = "\"" + templates.get("hello").version() + "\"";

        error(put("/templates/hello", body, "application/json", version), 401, HttpError.UNAUTHORIZED);
        server.close();
        start(ServerConfig.DEFAULT.withSave(true));
        error(put("/templates/hello", body, "text/plain", version), 415, HttpError.CONTENT_TYPE_NOT_VALID);
        error(put("/templates/hello", body, "application/json", null), 428, HttpError.VERSION_REQUIRED);
        error(put("/templates/hello", "{\"styles\": []}", "application/json", version), 400, HttpError.BODY_NOT_VALID);
        error(put("/templates/hello", "{\"document\": \"x\", \"colour\": 1}", "application/json", version), 400,
                HttpError.BODY_NOT_VALID);
        error(put("/templates/hello", JSON.writeValueAsString(Map.of("document", "x", "styles",
                List.of(Map.of("path", "../outside.stxt", "text", "x")))), "application/json", version), 400,
                HttpError.BODY_NOT_VALID);
        error(put("/templates/missing", body, "application/json", version), 404, TemplateException.NOT_FOUND);
        assertEquals(DOCUMENT, Files.readString(templates.folder().resolve("hello.stxt")));
    }
}
