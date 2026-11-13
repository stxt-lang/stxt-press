package dev.stxt.press.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.stxt.pdf.convert.Converter;
import dev.stxt.pdf.resources.Resources;

/**
 * The examples of {@code stxt-pdf} ({@code ../stxt-pdf/test/examples/NN-name/<language>/}) through {@code POST /render},
 * with their {@code styles.stxt} as the separate styles: the PDF has to be byte for byte their {@code output.pdf}, which
 * the engine writes the same every time. It checks that the service converts as the engine does, without changing the
 * document, the styles or the options on the way.
 */
class ExamplesThroughRenderTest {

    private static final Path EXAMPLES = Path.of("..", "stxt-pdf", "test", "examples");

    private static PressServer server;

    @BeforeAll
    static void start() throws IOException {
        Converter shared = Converter.builder().resources(new Resources(Path.of("..", "stxt-pdf", "resources"))).build();
        server = PressServer.start(shared, null, null, ServerConfig.DEFAULT.withAddress("127.0.0.1", 0));
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @TestFactory
    Stream<DynamicTest> everyExampleIsItsOutputPdf() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        try (Stream<Path> examples = Files.list(EXAMPLES)) {
            for (Path example : examples.filter(Files::isDirectory).sorted().toList()) {
                try (Stream<Path> languages = Files.list(example)) {
                    for (Path language : languages.filter(Files::isDirectory).sorted().toList()) {
                        tests.add(DynamicTest.dynamicTest(example.getFileName() + "/" + language.getFileName(),
                                () -> check(language)));
                    }
                }
            }
        }
        assertFalse(tests.isEmpty(), "no examples in " + EXAMPLES.toAbsolutePath());
        return tests.stream();
    }

    private static void check(Path dir) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("document", Files.readString(dir.resolve("input.stxt")));
        Path styles = dir.getParent().resolve("styles.stxt");
        if (Files.exists(styles)) {
            body.put("styles", List.of(Files.readString(styles)));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.address().getPort()
                + "/render")).POST(BodyPublishers.ofString(new ObjectMapper().writeValueAsString(body))).build();

        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode(), () -> new String(response.body(), StandardCharsets.UTF_8));
        assertArrayEquals(Files.readAllBytes(dir.resolve("output.pdf")), response.body(),
                dir + ": the PDF of /render is not output.pdf");
    }
}
