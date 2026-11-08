package dev.stxt.press.http;

import java.util.Map;

/**
 * An error of a request that the HTTP layer finds itself, before or around a job: a route or a parameter that does not
 * exist, a body that is not valid, a missing token, a server that is busy. It is answered with {@link #status} and a
 * JSON body with {@code code} and {@code message}.
 */
final class HttpError extends Exception {

    private static final long serialVersionUID = 1L;

    /** The HTTP status of the response. */
    final int status;
    /** The stable code of the error, in capitals. */
    final String code;
    /** The headers the response carries besides the body ({@code Allow}, {@code Retry-After}). */
    final transient Map<String, String> headers;

    HttpError(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    HttpError(int status, String code, String message, Map<String, String> headers) {
        super(message);
        this.status = status;
        this.code = code;
        this.headers = headers;
    }
}
