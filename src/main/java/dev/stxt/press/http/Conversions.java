package dev.stxt.press.http;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import com.sun.net.httpserver.HttpExchange;

import dev.stxt.pdf.convert.Converter;
import dev.stxt.pdf.model.Cancellation;
import dev.stxt.press.Job;
import dev.stxt.press.Options;
import dev.stxt.press.Press;
import dev.stxt.press.http.Requests.Format;
import dev.stxt.press.templates.Templates;

/**
 * The conversions of the server: its converter, created by the first request that needs it, and the limit of those
 * that run at once ({@link ServerConfig#concurrentConversions()}). A conversion that does not fit is answered at once
 * with {@code 503} ({@code SERVER_BUSY}): there is no queue.
 */
final class Conversions {

    /** What creates the converter of the server, called once by the first request that needs it. */
    private final Supplier<Converter> converter;
    /** The converter of the server and the {@link Press} on it; {@code null} until a request needs them. */
    private volatile Shared shared;
    /** The conversions that can still start. */
    private final Semaphore permits;
    /** The time each conversion has. */
    private final Duration timeout;

    /** The converter of the server and the {@link Press} on it. */
    private record Shared(Converter converter, Press press) {
    }

    Conversions(Supplier<Converter> converter, int concurrent, Duration timeout) {
        this.converter = converter;
        this.permits = new Semaphore(concurrent);
        this.timeout = timeout;
    }

    /**
     * {@return the converter of the server and its {@link Press}, created by the first call} If creating them fails, the
     * exception goes to that request and the next call tries again.
     */
    private Shared shared() {
        Shared current = shared;
        if (current == null) {
            synchronized (this) {
                current = shared;
                if (current == null) {
                    Converter created = converter.get();
                    current = new Shared(created, new Press(created));
                    shared = current;
                }
            }
        }
        return current;
    }

    /** {@return the permits of the conversions that can still start, for the tests} */
    Semaphore permits() {
        return permits;
    }

    /** {@return the converter of the server, created if no request has needed it yet} */
    Converter converter() {
        return shared().converter();
    }

    /**
     * Warms the server up: creates its converter, which reads the whole resources folder, and converts its templates
     * and its documents ({@link WarmUp}).
     *
     * @param templates the templates folder, or {@code null}
     * @param documents the documents folder, or {@code null}
     * @param options the options of the templates and the documents
     * @throws dev.stxt.pdf.model.StxtPdfException if the converter cannot be created: its resources
     */
    WarmUp.Result warmUp(Templates templates, Templates documents, Options options) {
        Shared current = shared();
        String font = current.converter().resources().fonts().families().stream().findFirst().orElse(null);
        return WarmUp.run(current.press(), templates, documents, font, options, timeout);
    }

    /** Converts {@code job} and answers with it in {@code format}; {@code name} names the PDF, if not {@code null}. */
    void respond(HttpExchange exchange, Job job, Format format, String name) throws Exception {
        if (format == Format.STXT) {
            // Without the converter: a fill reads no resources, so it does not create that of a lazy server.
            String filled = job.data() == null ? job.document() : Press.fill(job.document(), job.data());
            Responses.send(exchange, 200, Responses.STXT_TYPE, filled.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!permits.tryAcquire()) {
            throw new HttpError(503, HttpError.SERVER_BUSY, "The server is converting as many documents as it can at "
                    + "once", Map.of("Retry-After", "1"));
        }
        try {
            Cancellation cancellation = Cancellation.timeout(timeout);
            if (format == Format.SVG) {
                List<String> pages = shared().press().svg(job, cancellation);
                Responses.sendJson(exchange, 200, Map.of("pages", pages));
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/pdf");
                if (name != null) {
                    exchange.getResponseHeaders().set("Content-Disposition", "inline; filename=\"" + name + ".pdf\"");
                }
                // Not closed if the job fails: closing would end the body as if the PDF were whole.
                Responses.Body body = new Responses.Body(exchange);
                shared().press().pdf(job, body, cancellation);
                body.close();
            }
        } finally {
            permits.release();
        }
    }
}
