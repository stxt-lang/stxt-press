package dev.stxt.press.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;

/**
 * The files of the index and the editor, from the jar: the two pages ({@code index.html}, {@code editor.html}) and
 * those of {@code /assets/}. The pages carry a {@code Content-Security-Policy} that lets them load only what the
 * server serves.
 */
final class WebFiles {

    /** Where the build of {@code web/} leaves the files of the index and the editor, in the jar. */
    private static final String WEB = "/editor/";
    /** The name of a file of the editor: no folders, no hidden files, with an extension. */
    private static final Pattern EDITOR_FILE = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9]+)+");
    /** The types of the files of the editor, by extension. */
    private static final Map<String, String> EDITOR_TYPES = Map.of("html", "text/html; charset=utf-8", "js",
            "text/javascript; charset=utf-8", "css", "text/css; charset=utf-8", "svg", "image/svg+xml", "json",
            Responses.JSON_TYPE, "woff2", "font/woff2", "png", "image/png", "txt", "text/plain; charset=utf-8");
    /**
     * What the page of the editor may load: only its own files and the API of the server. The styles of CodeMirror
     * are inline, and the PDF is shown in a frame of a {@code blob:} URL, made by the page from the answer of the API.
     */
    private static final String EDITOR_POLICY = "default-src 'self'; img-src 'self' blob: data:; frame-src blob:; "
            + "style-src 'self' 'unsafe-inline'; object-src 'none'; base-uri 'none'; form-action 'none'; "
            + "frame-ancestors 'none'";

    private WebFiles() {
    }

    /** Answers with {@code file}, a page or one of {@code /assets/}. */
    static void send(HttpExchange exchange, String file) throws IOException, HttpError {
        if (!EDITOR_FILE.matcher(file).matches()) {
            throw new HttpError(404, HttpError.NOT_FOUND, "There is no file " + file + " in the assets");
        }
        byte[] content;
        try (InputStream in = WebFiles.class.getResourceAsStream(WEB + file)) {
            if (in == null) {
                throw new HttpError(404, HttpError.NOT_FOUND, file.endsWith(".html")
                        ? "The server was built without the editor" : "There is no file " + file + " in the assets");
            }
            content = in.readAllBytes();
        }
        String type = EDITOR_TYPES.getOrDefault(file.substring(file.lastIndexOf('.') + 1), "application/octet-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        if (type.startsWith("text/html")) {
            exchange.getResponseHeaders().set("Content-Security-Policy", EDITOR_POLICY);
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        }
        Responses.send(exchange, 200, type, content);
    }
}
