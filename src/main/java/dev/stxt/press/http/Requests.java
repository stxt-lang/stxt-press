package dev.stxt.press.http;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sun.net.httpserver.HttpExchange;

import dev.stxt.press.Press;

/**
 * What the service reads of a request: its method, its query, its token and its body, each with the {@link HttpError}
 * of a request that does not fit.
 */
final class Requests {

    /** The formats of {@code ?format=}. */
    enum Format {
        PDF, SVG, STXT
    }

    private Requests() {
    }

    /** That the method of the request is one of {@code allowed}. */
    static void method(HttpExchange exchange, List<String> allowed) throws HttpError {
        if (!allowed.contains(exchange.getRequestMethod())) {
            throw new HttpError(405, HttpError.METHOD_NOT_ALLOWED, "The method " + exchange.getRequestMethod()
                    + " is not one of " + String.join(", ", allowed), Map.of("Allow", String.join(", ", allowed)));
        }
    }

    /** {@return the parameters of the query, which have to be some of {@code allowed}, each once} */
    static Map<String, String> parameters(HttpExchange exchange, Set<String> allowed) throws HttpError {
        Map<String, String> parameters = new LinkedHashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) {
            return parameters;
        }
        for (String part : query.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int equals = part.indexOf('=');
            String key;
            String value;
            try {
                key = URLDecoder.decode(equals < 0 ? part : part.substring(0, equals), StandardCharsets.UTF_8);
                value = equals < 0 ? "" : URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw notValid("The query is not valid: " + e.getMessage());
            }
            if (!allowed.contains(key)) {
                throw notValid("Unknown parameter " + key + (allowed.isEmpty() ? ": the route has none"
                        : " (" + String.join(", ", allowed.stream().sorted().toList()) + ")"));
            }
            if (parameters.put(key, value) != null) {
                throw notValid("Repeated parameter " + key);
            }
        }
        return parameters;
    }

    /** {@return {@code ?format=} of a conversion, {@code pdf} by default} */
    static Format format(Map<String, String> parameters) throws HttpError {
        String format = parameters.getOrDefault("format", "pdf");
        return switch (format) {
            case "pdf" -> Format.PDF;
            case "svg" -> Format.SVG;
            case "stxt" -> Format.STXT;
            default -> throw notValid("Unknown format " + format + " (pdf, svg, stxt)");
        };
    }

    /** {@return {@code ?debug=} of a conversion, {@code false} by default} */
    static boolean debug(Map<String, String> parameters) throws HttpError {
        String debug = parameters.getOrDefault("debug", "false");
        return switch (debug) {
            case "true" -> true;
            case "false" -> false;
            default -> throw notValid("debug is " + debug + ", not true or false");
        };
    }

    /** {@return whether the request has {@code token}, if it is not {@code null}} */
    static boolean authorized(HttpExchange exchange, String token) {
        if (token == null) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String scheme = "Bearer ";
        if (header == null || !header.regionMatches(true, 0, scheme, 0, scheme.length())) {
            return false;
        }
        // In constant time, so that the time of the answer does not tell how much of the token is right.
        return MessageDigest.isEqual(header.substring(scheme.length()).getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    /** {@return the body of the request, if it is not larger than {@code max} bytes} */
    static byte[] body(HttpExchange exchange, long max) throws HttpError {
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try {
                if (Long.parseLong(length.trim()) > max) {
                    throw tooLarge(max);
                }
            } catch (NumberFormatException e) {
                throw new HttpError(400, HttpError.BODY_NOT_VALID, "Content-Length is not a number: " + length);
            }
        }
        // Without Content-Length (chunked), the limit is checked as it is read.
        byte[] body;
        try {
            body = exchange.getRequestBody().readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE - 8));
        } catch (IOException e) {
            // The connection was closed before the whole body came: by the client, or by the server when the request
            // ran out of time (PressServer.limitTimes). A fault of the request, not of the server, so not for the log.
            throw new HttpError(400, HttpError.BODY_NOT_VALID, "The body did not arrive whole");
        }
        if (body.length > max) {
            throw tooLarge(max);
        }
        return body;
    }

    /** {@return {@code body} as UTF-8 text, strictly decoded} */
    static String text(byte[] body) throws HttpError {
        try {
            return Press.text(body);
        } catch (CharacterCodingException e) {
            throw new HttpError(400, HttpError.BODY_NOT_VALID, "The body is not UTF-8 text");
        }
    }

    private static HttpError tooLarge(long max) {
        return new HttpError(413, HttpError.BODY_TOO_LARGE, "The body is larger than the " + max
                + " bytes the server accepts");
    }

    private static HttpError notValid(String message) {
        return new HttpError(400, HttpError.PARAMETER_NOT_VALID, message);
    }
}
