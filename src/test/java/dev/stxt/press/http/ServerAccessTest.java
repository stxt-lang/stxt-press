package dev.stxt.press.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Who a server of the test ({@link ServerFixture}) answers: the {@code Host} of a local server without token, the token,
 * the routes that are off, the methods and the parameters.
 */
class ServerAccessTest extends ServerFixture {

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
            assertEquals(200, raw("GET /files/templates HTTP/1.1", "Host: " + host).status(), host);
        }
        for (String host : List.of("evil.example:" + port, "evil.example", "localhost.evil.example",
                "127.0.0.1.nip.io:" + port, "localhost.", "localhost:80:80", "[::1", "[::2]:" + port)) {
            for (String path : List.of("/files/templates", "/files/templates/hello", "/health", "/", "/editor", "/print/hello")) {
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
        assertEquals(401, raw("GET /files/templates HTTP/1.1", "Host: evil.example").status());
        assertEquals(200, raw("GET /files/templates HTTP/1.1", "Host: evil.example", "Authorization: Bearer s3cret").status());
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
        start(ServerConfig.DEFAULT.withRoutes(false, true, true));

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
        start(ServerConfig.DEFAULT.withRoutes(false, true, true).withToken("s3cret"));

        error(get("/"), 401, HttpError.UNAUTHORIZED);
    }

    @Test
    void theParametersAreChecked() throws Exception {
        start(ServerConfig.DEFAULT);

        error(get("/print/hello?format=docx"), 400, HttpError.PARAMETER_NOT_VALID);
        error(get("/print/hello?debug=yes"), 400, HttpError.PARAMETER_NOT_VALID);
        error(get("/print/hello?format=pdf&format=svg"), 400, HttpError.PARAMETER_NOT_VALID);
        error(get("/print/hello?page=1"), 400, HttpError.PARAMETER_NOT_VALID);
        error(get("/fonts?all=true"), 400, HttpError.PARAMETER_NOT_VALID);
        assertEquals(200, get("/print/hello?format=svg&debug=true").statusCode());
    }

    @Test
    void aRouteOrAMethodThatDoesNotExist() throws Exception {
        start(ServerConfig.DEFAULT);

        error(get("/pdf"), 404, HttpError.NOT_FOUND);
        error(get("/fonts/a/b"), 404, HttpError.NOT_FOUND);
        HttpResponse<byte[]> response = get("/render");
        error(response, 405, HttpError.METHOD_NOT_ALLOWED);
        assertEquals("POST", response.headers().firstValue("Allow").orElse(null));
        error(post("/files/templates", ""), 405, HttpError.METHOD_NOT_ALLOWED);
    }

    @Test
    void withATokenEveryRouteButHealthAsksForIt() throws Exception {
        start(ServerConfig.DEFAULT.withToken("s3cret"));

        assertEquals(200, get("/health").statusCode());
        HttpResponse<byte[]> without = get("/print/hello");
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
    void renderAndTheFilesCanBeTurnedOff() throws Exception {
        start(ServerConfig.DEFAULT.withRoutes(true, false, false));

        error(post("/render", render(DOCUMENT, Map.of())), 404, HttpError.NOT_FOUND);
        error(get("/files/templates"), 404, HttpError.NOT_FOUND);
        error(get("/files/templates/hello"), 404, HttpError.NOT_FOUND);
        error(get("/files/documents"), 404, HttpError.NOT_FOUND);
        error(get("/files/documents/hello"), 404, HttpError.NOT_FOUND);
        // /print and /documents are always on.
        assertEquals(200, get("/print/hello").statusCode());
        assertEquals(200, get("/documents/hello").statusCode());
    }
}
