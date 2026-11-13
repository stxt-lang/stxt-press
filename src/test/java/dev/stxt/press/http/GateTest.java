package dev.stxt.press.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import dev.stxt.pdf.convert.Converter;
import dev.stxt.pdf.resources.Resources;

/**
 * The {@link Gate} on an exchange of the test, without a server: what happens to an error after the response has
 * started, which over a real connection cannot be caused at will.
 */
class GateTest {

    private static final Converter SHARED = Converter.builder()
            .resources(new Resources(Path.of("..", "stxt-pdf", "resources"))).build();

    private static final String DOCUMENT = """
            Document (dev.stxt.pdf):
            \tLanguage: en
            \tTitle: Test
            \tPage:
            \t\tPaper: A4
            \tSection:
            \t\tParagraph: Hello.
            """;

    private static final String STYLES = """
            Styles (dev.stxt.pdf):
            \tFor element: Document
            \t\tTypography:
            \t\t\tFont: IBM Plex Sans
            \t\t\tSize: 11
            """;

    private final ServerConfig config = ServerConfig.DEFAULT;
    private final Conversions conversions = new Conversions(() -> SHARED, config.concurrentConversions(),
            config.timeout());
    private final Gate gate = new Gate(new Routes(conversions, null, null, config), null);

    /**
     * A PDF that fails once its status is sent (the client cuts the connection) is not answered with an error, which
     * would go after a part of the PDF, nor ended as if it were whole: the exception goes to the server, which drops
     * the connection.
     */
    @Test
    void aPdfThatFailsHalfwayDropsTheConnection() throws Exception {
        TestExchange exchange = new TestExchange("POST", "/render", render(DOCUMENT), true);

        assertThrows(IOException.class, () -> gate.handle(exchange));

        assertEquals(List.of(200), exchange.statuses, "only the status of the PDF, and no error after it");
        assertFalse(exchange.closed, "not ended as if the PDF were whole");
        assertEquals(0, gate.active());
        assertEquals(config.concurrentConversions(), conversions.permits().availablePermits());
    }

    /** The other side: an error found before the response started is answered with its status, and ended. */
    @Test
    void anErrorBeforeTheResponseIsAnswered() throws Exception {
        TestExchange exchange = new TestExchange("POST", "/render", render("Document (dev.stxt.pdf):\n\tColour: red\n"),
                false);

        gate.handle(exchange);

        assertEquals(List.of(422), exchange.statuses);
        assertTrue(exchange.closed);
        JsonNode body = Json.MAPPER.readTree(exchange.written.toByteArray());
        assertEquals("NODE_NOT_DEFINED_IN_SCHEMA", body.get("code").asText(), body::toString);
        assertEquals(0, gate.active());
    }

    private static byte[] render(String document) throws IOException {
        return Json.MAPPER.writeValueAsBytes(Map.of("document", document, "styles", List.of(STYLES)));
    }

    /** An exchange of the test: a request with its body, and a response body that fails at its first write or not. */
    private static final class TestExchange extends HttpExchange {

        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final String method;
        private final URI uri;
        private final InputStream body;
        private final boolean cut;
        /** The statuses sent, in order: more than one would be a second response on the same exchange. */
        final List<Integer> statuses = new ArrayList<>();
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        boolean closed;

        TestExchange(String method, String path, byte[] body, boolean cut) {
            this.method = method;
            this.uri = URI.create(path);
            this.body = new ByteArrayInputStream(body);
            this.cut = cut;
            requestHeaders.set("Content-Type", "application/json");
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return uri;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public InputStream getRequestBody() {
            return body;
        }

        @Override
        public OutputStream getResponseBody() {
            if (!cut) {
                return written;
            }
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    write(new byte[] {(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    throw new IOException("The client closed the connection");
                }
            };
        }

        @Override
        public void sendResponseHeaders(int status, long length) {
            statuses.add(status);
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        public int getResponseCode() {
            return statuses.isEmpty() ? -1 : statuses.get(statuses.size() - 1);
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {
        }

        @Override
        public void setStreams(InputStream in, OutputStream out) {
            throw new UnsupportedOperationException();
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }
}
