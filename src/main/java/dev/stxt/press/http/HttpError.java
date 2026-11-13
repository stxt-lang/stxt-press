package dev.stxt.press.http;

import java.util.Map;

/**
 * An error of a request that the HTTP layer finds itself, before or around a job: a route or a parameter that does not
 * exist, a body that is not valid, a missing token, a server that is busy. It is answered with {@link #status} and a
 * JSON body with {@code code} and {@code message}.
 */
final class HttpError extends Exception {

    private static final long serialVersionUID = 1L;

    static final String NOT_FOUND = "NOT_FOUND";
    static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    static final String UNAUTHORIZED = "UNAUTHORIZED";
    static final String PARAMETER_NOT_VALID = "PARAMETER_NOT_VALID";
    static final String BODY_NOT_VALID = "BODY_NOT_VALID";
    static final String BODY_TOO_LARGE = "BODY_TOO_LARGE";
    static final String SERVER_BUSY = "SERVER_BUSY";
    static final String SERVER_STARTING = "SERVER_STARTING";
    static final String HOST_NOT_ALLOWED = "HOST_NOT_ALLOWED";
    /** A save without the JSON body it takes ({@code 415}). */
    static final String CONTENT_TYPE_NOT_VALID = "CONTENT_TYPE_NOT_VALID";
    /** A save without {@code If-Match}, the version it starts from ({@code 428}). */
    static final String VERSION_REQUIRED = "VERSION_REQUIRED";
    /** The code of an error that is not of the request: answered {@code 500}, without details. */
    static final String INTERNAL = "INTERNAL";

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
