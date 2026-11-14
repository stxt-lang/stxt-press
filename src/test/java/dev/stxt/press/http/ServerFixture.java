package dev.stxt.press.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.stxt.pdf.convert.Converter;
import dev.stxt.pdf.resources.Resources;
import dev.stxt.press.templates.Templates;

/**
 * What the tests of the HTTP service share: a templates folder and a documents folder in a temporary folder, a server
 * on any free port of {@code 127.0.0.1} on the fonts of {@code ../stxt-pdf/resources}, closed after each test, the
 * texts of the tests and the requests to it. The tests are in {@link PressServerTest} (the routes of the clients),
 * {@link EditorRoutesTest}, {@link ServerLifecycleTest} and {@link ServerAccessTest}.
 */
abstract class ServerFixture {

    static final Converter SHARED = Converter.builder()
            .resources(new Resources(Path.of("..", "stxt-pdf", "resources"))).build();

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient CLIENT = HttpClient.newHttpClient();

    static final String DOCUMENT = """
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
    static final String WITH_SLOTS = """
            Document (dev.stxt.pdf):
            \tLanguage: en
            \tTitle: <title>
            \tPage:
            \t\tPaper: A4
            \tSection:
            \t\tParagraph: [lines]<text>
            """;

    static final String SAMPLE = """
            {"title": "Sample", "lines": [{"text": "One."}, {"text": "Two."}]}
            """;

    static final String STYLES = """
            Styles (dev.stxt.pdf):
            \tFor element: Document
            \t\tTypography:
            \t\t\tFont: IBM Plex Sans
            \t\t\tSize: 11
            """;

    static final String LETTER_STYLES = """
            Styles (dev.stxt.pdf):
            \tFor element: Paragraph
            \t\tTypography:
            \t\t\tColor: #123456
            """;

    @TempDir
    Path temp;

    Templates templates;
    Templates documents;
    PressServer server;

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

    void start(ServerConfig config) throws IOException {
        start(SHARED, config);
    }

    void start(Converter shared, ServerConfig config) throws IOException {
        server = PressServer.start(shared, templates, documents, config.withAddress("127.0.0.1", 0));
    }

    HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.address().getPort() + path));
    }

    HttpResponse<byte[]> get(String path) throws Exception {
        return CLIENT.send(request(path).GET().build(), BodyHandlers.ofByteArray());
    }

    HttpResponse<byte[]> post(String path, String body) throws Exception {
        return CLIENT.send(request(path).POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofByteArray());
    }

    /** {@return the body of {@code /render} with {@code document}, the styles with the font and {@code more}} */
    static String render(String document, Map<String, Object> more) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("document", document);
        body.put("styles", List.of(STYLES));
        body.putAll(more);
        return JSON.writeValueAsString(body);
    }

    static JsonNode json(HttpResponse<byte[]> response) throws IOException {
        return JSON.readTree(response.body());
    }

    static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    static boolean isPdf(byte[] bytes) {
        return bytes.length > 1000 && new String(Arrays.copyOf(bytes, 5), StandardCharsets.ISO_8859_1).equals("%PDF-");
    }

    /** That {@code response} is an error with {@code status} and {@code code}; {@return its body} */
    static JsonNode error(HttpResponse<byte[]> response, int status, String code) throws IOException {
        assertEquals(status, response.statusCode(), () -> text(response));
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(null));
        // An error carries text of the request: the browser takes it as JSON and nothing else.
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
        JsonNode body = json(response);
        assertEquals(code, body.get("code").asText(), body::toString);
        assertTrue(body.get("message").isTextual());
        return body;
    }
}
