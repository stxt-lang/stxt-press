package dev.stxt.press.http;

import java.time.Duration;
import java.util.Objects;

import dev.stxt.press.Options;

/**
 * What the HTTP service takes from the configuration of a server: where it listens, the token, which routes are on,
 * its limits and the options of the templates and the documents. {@code server.stxt} (ROADMAP-PDF 2.5) gives it;
 * {@link #DEFAULT} is the one of a server without that file, made to try it out locally.
 *
 * @param host the address it listens on: {@code 127.0.0.1} by default, so that opening it to the network needs a
 *        configuration; not {@code null}
 * @param port the port, or 0 for any free one
 * @param token the token every route but {@code /health} asks for ({@code Authorization: Bearer <token>}), or
 *        {@code null} for none
 * @param editor whether the index ({@code GET /}) and the editor ({@code GET /editor},
 *        {@code /editor/templates/{path}} and {@code /editor/documents/{path}}) are on, with their files under
 *        {@code /assets/} and {@code /fonts}, which only the previews of the editor use
 * @param render whether {@code POST /render}, which converts any document, is on
 * @param templates whether {@code /templates}, which shows the texts and the sample data of the templates, is on
 * @param documents whether {@code /documents}, which shows the texts of the documents, is on
 * @param maxBodySize the largest body of a request, in bytes; at least 1
 * @param timeout the time a conversion has; positive
 * @param concurrentConversions how many PDF or SVG conversions run at once: one more is answered with {@code 503};
 *        at least 1
 * @param templateOptions the options of the engine for every template and every document of {@code /print}; not
 *        {@code null}
 */
public record ServerConfig(String host, int port, String token, boolean editor, boolean render, boolean templates,
        boolean documents, long maxBodySize, Duration timeout, int concurrentConversions, Options templateOptions) {

    /** The configuration of a server without {@code server.stxt}: local, without token, with every route on. */
    public static final ServerConfig DEFAULT = new ServerConfig("127.0.0.1", 8080, null, true, true, true, true,
            2 * 1024 * 1024, Duration.ofSeconds(30), 4, Options.DEFAULT);

    public ServerConfig {
        Objects.requireNonNull(host, "host");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port out of range: " + port);
        }
        if (token != null && token.isBlank()) {
            throw new IllegalArgumentException("Empty token");
        }
        if (maxBodySize < 1) {
            throw new IllegalArgumentException("Max body size below 1: " + maxBodySize);
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout not positive: " + timeout);
        }
        if (concurrentConversions < 1) {
            throw new IllegalArgumentException("Concurrent conversions below 1: " + concurrentConversions);
        }
        Objects.requireNonNull(templateOptions, "templateOptions");
    }

    /** {@return this configuration listening on {@code host} and {@code port}} */
    public ServerConfig withAddress(String host, int port) {
        return new ServerConfig(host, port, token, editor, render, templates, documents, maxBodySize, timeout,
                concurrentConversions, templateOptions);
    }

    /** {@return this configuration with {@code token}, or without token with {@code null}} */
    public ServerConfig withToken(String token) {
        return new ServerConfig(host, port, token, editor, render, templates, documents, maxBodySize, timeout,
                concurrentConversions, templateOptions);
    }

    /** {@return this configuration with the editor, {@code /render}, {@code /templates} and {@code /documents} on or off} */
    public ServerConfig withRoutes(boolean editor, boolean render, boolean templates, boolean documents) {
        return new ServerConfig(host, port, token, editor, render, templates, documents, maxBodySize, timeout,
                concurrentConversions, templateOptions);
    }

    /** {@return this configuration with these limits} */
    public ServerConfig withLimits(long maxBodySize, Duration timeout, int concurrentConversions) {
        return new ServerConfig(host, port, token, editor, render, templates, documents, maxBodySize, timeout,
                concurrentConversions, templateOptions);
    }

    /** {@return this configuration with {@code templateOptions} for every template and every document} */
    public ServerConfig withTemplateOptions(Options templateOptions) {
        return new ServerConfig(host, port, token, editor, render, templates, documents, maxBodySize, timeout,
                concurrentConversions, templateOptions);
    }

    /** {@return the record without the token, so that it is not written in a log} */
    @Override
    public String toString() {
        return "ServerConfig[host=" + host + ", port=" + port + ", token=" + (token == null ? "none" : "set")
                + ", editor=" + editor + ", render=" + render + ", templates=" + templates + ", documents="
                + documents + ", maxBodySize=" + maxBodySize + ", timeout=" + timeout + ", concurrentConversions="
                + concurrentConversions + ", templateOptions=" + templateOptions + "]";
    }
}
