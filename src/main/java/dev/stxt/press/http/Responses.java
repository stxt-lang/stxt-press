package dev.stxt.press.http;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

import dev.stxt.pdf.model.StxtPdfException;
import dev.stxt.pdf.model.StxtPdfException.Kind;
import dev.stxt.press.Press;
import dev.stxt.press.PressException;
import dev.stxt.press.templates.TemplateException;

/**
 * How the service answers: a body sent whole ({@link #send}, {@link #sendJson}), one sent as it is written
 * ({@link Body}) and an error, with the HTTP status of its kind ({@link #fail}).
 */
final class Responses {

    static final String JSON_TYPE = "application/json";
    static final String STXT_TYPE = "text/stxt; charset=utf-8";

    private static final Logger LOG = System.getLogger("dev.stxt.press");

    private Responses() {
    }

    static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, JSON_TYPE, Json.MAPPER.writeValueAsBytes(body));
    }

    static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        secure(exchange);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    /**
     * The headers of every response: that the browser takes the {@code Content-Type} as it is, since an error carries
     * text of the request.
     */
    private static void secure(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    }

    /** Answers {@code e}, an error found before the response started. */
    static void fail(HttpExchange exchange, Exception e) throws IOException {
        // A header set for the response that failed (the Content-Type of a PDF) does not belong to the error.
        exchange.getResponseHeaders().clear();
        Map<String, Object> body = new LinkedHashMap<>();
        int status;
        if (e instanceof HttpError http) {
            status = http.status;
            http.headers.forEach(exchange.getResponseHeaders()::set);
            body.put("code", http.code);
            body.put("message", http.getMessage());
        } else if (e instanceof PressException press) {
            status = status(press.kind());
            if (status == 500) {
                LOG.log(Level.ERROR, "Error of " + exchange.getRequestURI(), e);
            }
            body.put("kind", press.kind().name());
            body.put("code", press.code());
            body.put("line", press.line());
            body.put("source", press.source());
            body.put("message", press.getMessage());
            if (press.filled() != null) {
                body.put("filled", press.filled());
            }
        } else if (e instanceof StxtPdfException engine) {
            // The converter of a lazy server could not be created: its resources.
            status = status(engine.kind());
            LOG.log(status == 500 ? Level.ERROR : Level.WARNING, "Error of " + exchange.getRequestURI(), e);
            body.put("kind", engine.kind().name());
            body.put("code", engine.code());
            body.put("message", engine.getMessage());
        } else if (e instanceof TemplateException template) {
            boolean notFound = template.notFound();
            status = notFound ? 404 : 500;
            if (!notFound) {
                LOG.log(Level.ERROR, "Error of " + exchange.getRequestURI(), e);
            }
            body.put("code", template.code());
            body.put("message", template.getMessage());
        } else {
            LOG.log(Level.ERROR, "Error of " + exchange.getRequestURI(), e);
            status = 500;
            body.put("code", HttpError.INTERNAL);
            body.put("message", "Internal error of the server");
        }
        sendJson(exchange, status, body);
    }

    /** {@return the status of an error of a job of this kind} */
    private static int status(Kind kind) {
        return switch (kind) {
            case INVALID_DOCUMENT -> 422;
            case LIMIT -> 413;
            case CANCELLED -> 503;
            case RESOURCE, INTERNAL -> 500;
        };
    }

    /**
     * The body of a {@code 200} that is sent as it is written, with the status and the headers sent at the first write.
     * {@link Press#pdf} lays out the document before writing anything, so an error of the job is still answered with its
     * status.
     */
    static final class Body extends OutputStream {

        private final HttpExchange exchange;
        private OutputStream out;

        Body(HttpExchange exchange) {
            this.exchange = exchange;
        }

        private OutputStream out() throws IOException {
            if (out == null) {
                secure(exchange);
                exchange.sendResponseHeaders(200, 0);
                out = exchange.getResponseBody();
            }
            return out;
        }

        @Override
        public void write(int b) throws IOException {
            out().write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out().write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            if (out != null) {
                out.flush();
            }
        }

        /** Ends the body of a job that finished, sending the status and the headers if nothing was written. */
        @Override
        public void close() throws IOException {
            out().close();
        }
    }
}
