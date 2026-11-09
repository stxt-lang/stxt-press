package dev.stxt.press.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
import java.util.concurrent.CountDownLatch;

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

        // One page for the blank document and for every template, which exists or not: that is not told without token.
        for (String url : List.of("/editor", "/editor/hello", "/editor/catalogs/catalog", "/editor/missing/one",
                "/editor/hello?mode=document", "/editor/hello?mode=template", "/editor?mode=document")) {
            HttpResponse<byte[]> page = get(url);
            assertEquals(200, page.statusCode(), url);
            String policy = page.headers().firstValue("Content-Security-Policy").orElse("");
            assertTrue(policy.startsWith("default-src 'self'"), policy);
            // The PDF is shown in a frame of a blob: URL; nothing else is framed.
            assertTrue(policy.contains("; frame-src blob:;"), policy);
            assertTrue(text(page).contains("/assets/editor.js"), url);
        }
        for (String url : List.of("/editor/", "/editor/a//b", "/editor/a/", "/editor/a.b", "/editor/..%2Fx",
                "/editor/a%2Fb", "/editor/editor.js")) {
            error(get(url), 404, Routes.NOT_FOUND);
        }
        error(get("/editor/hello?mode=pdf"), 400, Routes.PARAMETER_NOT_VALID);
        error(get("/editor/hello?format=pdf"), 400, Routes.PARAMETER_NOT_VALID);

        HttpResponse<byte[]> script = get("/assets/editor.js");
        assertEquals(200, script.statusCode());
        assertEquals("text/javascript; charset=utf-8", script.headers().firstValue("Content-Type").orElse(null));
        assertEquals(200, get("/assets/index.js").statusCode());
        assertEquals(200, get("/assets/editor.css").statusCode());
        error(get("/assets/missing.js"), 404, Routes.NOT_FOUND);
        error(get("/assets/.hidden"), 404, Routes.NOT_FOUND);
        error(get("/assets/..%2Fstxt%2Fdev.stxt.press.stxt"), 404, Routes.NOT_FOUND);
        error(get("/assets/"), 404, Routes.NOT_FOUND);
        // A page has one URL.
        error(get("/assets/index.html"), 404, Routes.NOT_FOUND);
        error(get("/assets/editor.html"), 404, Routes.NOT_FOUND);

        error(post("/", ""), 405, Routes.METHOD_NOT_ALLOWED);
        error(post("/editor/hello", ""), 405, Routes.METHOD_NOT_ALLOWED);
        error(get("/schema"), 401, Routes.UNAUTHORIZED);
        error(get("/templates/hello"), 401, Routes.UNAUTHORIZED);
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
        }, templates, ServerConfig.DEFAULT.withAddress("127.0.0.1", 0));
        PressServer warming = server;
        Thread warmUp = new Thread(warming::warmUp);
        warmUp.start();

        HttpResponse<byte[]> health = get("/health");
        error(health, 503, Routes.SERVER_STARTING);
        assertEquals("1", health.headers().firstValue("Retry-After").orElse(null));
        error(get("/print/hello"), 503, Routes.SERVER_STARTING);
        error(get("/"), 503, Routes.SERVER_STARTING);

        release.countDown();
        warmUp.join(60_000);
        assertFalse(warmUp.isAlive());
        assertEquals(200, get("/health").statusCode());
        assertEquals(200, get("/print/hello").statusCode());
    }

    /**
     * The warm up converts every template with its sample data; one that fails is reported, and that of a template
     * without sample data says so, since a template with Slots needs the data of the request.
     */
    @Test
    void theWarmUpConvertsEveryTemplate() throws Exception {
        Path folder = templates.folder();
        Files.writeString(folder.resolve("broken.stxt"), "Document (dev.stxt.pdf):\n\tColour: red\n");
        Files.writeString(folder.resolve("broken.json"), "{}");
        // A template without sample data: its failure is only information.
        Files.writeString(folder.resolve("plain.stxt"), "Document (dev.stxt.pdf):\n\tColour: red\n");

        WarmUp.Result result = WarmUp.run(new Press(SHARED), templates, "IBM Plex Sans", Options.DEFAULT, Duration.ofSeconds(30));

        assertEquals(2, result.converted(), result::toString);
        assertEquals(List.of("broken", "plain"), result.failures().stream().map(WarmUp.Failure::path).toList());
        assertTrue(result.failures().get(0).withData());
        assertFalse(result.failures().get(1).withData());
        assertTrue(result.failures().get(0).error().startsWith("NODE_NOT_DEFINED_IN_SCHEMA"), result::toString);

        WarmUp.Result none = WarmUp.run(new Press(SHARED), null, "IBM Plex Sans", Options.DEFAULT, Duration.ofSeconds(30));
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
            while (closing.routes().active() == 0 && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertEquals(1, closing.routes().active());

            started = System.nanoTime();
            Thread close = new Thread(() -> closing.close(Duration.ofSeconds(30)));
            close.start();
            HttpResponse<byte[]> refused = get("/health");
            while (refused.statusCode() == 200 && System.nanoTime() < until) {
                refused = get("/health");
            }
            error(refused, 503, Routes.SERVER_BUSY);
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
            while (server.routes().active() < 4 && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertEquals(4, server.routes().active(), "every thread waits for a body");
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

    @Test
    void theEditorCanBeTurnedOff() throws Exception {
        start(ServerConfig.DEFAULT.withRoutes(false, true, true));

        error(get("/"), 404, Routes.NOT_FOUND);
        error(get("/editor"), 404, Routes.NOT_FOUND);
        error(get("/editor/hello"), 404, Routes.NOT_FOUND);
        error(get("/assets/editor.js"), 404, Routes.NOT_FOUND);
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
