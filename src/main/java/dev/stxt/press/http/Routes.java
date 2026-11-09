package dev.stxt.press.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import dev.stxt.pdf.convert.Converter;
import dev.stxt.pdf.model.Cancellation;
import dev.stxt.pdf.model.FontStyle;
import dev.stxt.pdf.model.StxtPdfException;
import dev.stxt.pdf.model.StxtPdfException.Kind;
import dev.stxt.pdf.resources.FontFile;
import dev.stxt.pdf.resources.FontLibrary;
import dev.stxt.pdf.tree.DocumentSchema;
import dev.stxt.press.Job;
import dev.stxt.press.Options;
import dev.stxt.press.Press;
import dev.stxt.press.PressException;
import dev.stxt.press.templates.Template;
import dev.stxt.press.templates.TemplateException;
import dev.stxt.press.templates.Templates;

/**
 * The routes of the service (ROADMAP-PDF 2.4), on one handler for the whole server.
 *
 * <table>
 * <caption>Routes</caption>
 * <tr><td>{@code GET /print/{path}}</td><td>The template filled with its sample data</td></tr>
 * <tr><td>{@code POST /print/{path}}</td><td>The template filled with the body, the JSON data as they are</td></tr>
 * <tr><td>{@code POST /render}</td><td>Any document ({@link RenderBody})</td></tr>
 * <tr><td>{@code GET /templates}</td><td>The templates, with what files each one has</td></tr>
 * <tr><td>{@code GET /templates/{path}}</td><td>The texts of a template</td></tr>
 * <tr><td>{@code GET /schema}</td><td>The schema {@code dev.stxt.pdf} of the engine</td></tr>
 * <tr><td>{@code GET /fonts}</td><td>The font families of the server, and its faces with their files, for the editor</td></tr>
 * <tr><td>{@code GET /fonts/{id}}</td><td>The file of a face, by its SHA-256, for the editor</td></tr>
 * <tr><td>{@code GET /health}</td><td>That the service is alive, and its versions, without token</td></tr>
 * <tr><td>{@code GET /}, {@code GET /editor/{file}}</td><td>The web editor and its files, without token</td></tr>
 * </table>
 *
 * <p>{@code /print} and {@code /render} answer in the format of {@code ?format=}: {@code pdf} (by default),
 * {@code svg} ({@code {"pages": [...]}}, with {@code &debug=true} for the outlines of the boxes) or {@code stxt} (the
 * filled document). Every error is a JSON body with its {@code code} and its {@code message}; that of a job has also
 * {@code kind}, {@code line}, {@code source} and, if it is of the filled document, {@code filled}.</p>
 */
final class Routes implements HttpHandler {

    static final String NOT_FOUND = "NOT_FOUND";
    static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    static final String UNAUTHORIZED = "UNAUTHORIZED";
    static final String PARAMETER_NOT_VALID = "PARAMETER_NOT_VALID";
    static final String BODY_NOT_VALID = "BODY_NOT_VALID";
    static final String BODY_TOO_LARGE = "BODY_TOO_LARGE";
    static final String SERVER_BUSY = "SERVER_BUSY";
    static final String INTERNAL = "INTERNAL";

    private static final Logger LOG = System.getLogger("dev.stxt.press");

    private static final String JSON_TYPE = "application/json";
    private static final String STXT_TYPE = "text/stxt; charset=utf-8";
    private static final Set<String> CONVERT_PARAMETERS = Set.of("format", "debug");
    /** The id of a font: the SHA-256 of its file, in lowercase hexadecimal. */
    private static final Pattern FONT_ID = Pattern.compile("[0-9a-f]{64}");
    /** Where the build of {@code web/} leaves the files of the editor, in the jar. */
    private static final String EDITOR = "/editor/";
    /** The name of a file of the editor: no folders, no hidden files, with an extension. */
    private static final Pattern EDITOR_FILE = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9]+)+");
    /** The types of the files of the editor, by extension. */
    private static final Map<String, String> EDITOR_TYPES = Map.of("html", "text/html; charset=utf-8", "js",
            "text/javascript; charset=utf-8", "css", "text/css; charset=utf-8", "svg", "image/svg+xml", "json",
            JSON_TYPE, "woff2", "font/woff2");
    /**
     * What the page of the editor may load: only its own files and the API of the server. The styles of CodeMirror
     * are inline, and the previews are images of {@code blob:} URLs.
     */
    private static final String EDITOR_POLICY = "default-src 'self'; img-src 'self' blob: data:; "
            + "style-src 'self' 'unsafe-inline'; object-src 'none'; base-uri 'none'; form-action 'none'; "
            + "frame-ancestors 'none'";

    /** The formats of {@code ?format=}. */
    private enum Format {
        PDF, SVG, STXT
    }

    /** What creates the converter of the server, called once by the first request that needs it. */
    private final Supplier<Converter> converter;
    /** The converter of the server and the {@link Press} on it; {@code null} until a request needs them. */
    private volatile Shared shared;
    /** The templates folder, or {@code null} if the server has none. */
    private final Templates templates;
    private final ServerConfig config;
    /** The text of the schema of the engine, read once. */
    private final byte[] schema;
    /** The conversions that can still start. */
    private final Semaphore conversions;

    /** The converter of the server and the {@link Press} on it. */
    private record Shared(Converter converter, Press press) {
    }

    Routes(Supplier<Converter> converter, Templates templates, ServerConfig config) {
        this.converter = converter;
        this.templates = templates;
        this.config = config;
        this.conversions = new Semaphore(config.concurrentConversions());
        this.schema = DocumentSchema.text().getBytes(StandardCharsets.UTF_8);
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

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (Exception e) {
            if (exchange.getResponseCode() != -1) {
                // The response had started (a PDF being written): the exception drops the connection, so that the
                // client does not take a part of the PDF for the whole.
                LOG.log(Level.WARNING, "The response to " + exchange.getRequestURI() + " could not be finished", e);
                throw e instanceof IOException io ? io : new IOException(e);
            }
            fail(exchange, e);
        }
        exchange.close();
    }

    private void route(HttpExchange exchange) throws Exception {
        String path = exchange.getRequestURI().getRawPath();
        if (path.equals("/health")) {
            method(exchange, "GET");
            parameters(exchange, Set.of());
            sendJson(exchange, 200, health());
            return;
        }
        if (config.editor() && (path.equals("/") || path.startsWith(EDITOR))) {
            // Without token: the files of the page have nothing secret, and a browser cannot send the token when it
            // opens a URL. The page asks for it when the API answers 401.
            method(exchange, "GET");
            parameters(exchange, Set.of());
            editor(exchange, path.equals("/") ? "index.html" : path.substring(EDITOR.length()));
            return;
        }
        if (!authorized(exchange)) {
            throw new HttpError(401, UNAUTHORIZED, "The request has no valid token (Authorization: Bearer)",
                    Map.of("WWW-Authenticate", "Bearer"));
        }
        if (path.startsWith("/print/")) {
            print(exchange, path.substring("/print/".length()));
        } else if (path.equals("/render") && config.render()) {
            render(exchange);
        } else if (path.equals("/templates") && config.templates()) {
            list(exchange);
        } else if (path.startsWith("/templates/") && config.templates()) {
            template(exchange, path.substring("/templates/".length()));
        } else if (path.equals("/schema")) {
            method(exchange, "GET");
            parameters(exchange, Set.of());
            send(exchange, 200, STXT_TYPE, schema);
        } else if (path.equals("/fonts") && config.editor()) {
            method(exchange, "GET");
            parameters(exchange, Set.of());
            fonts(exchange);
        } else if (path.startsWith("/fonts/") && config.editor()) {
            method(exchange, "GET");
            parameters(exchange, Set.of());
            font(exchange, path.substring("/fonts/".length()));
        } else {
            throw new HttpError(404, NOT_FOUND, "There is no route " + path);
        }
    }

    /**
     * {@code GET /health}: that the service answers, and the versions of the service and of the engine, so that whoever
     * keeps it can tell what is deployed. The version of the service is {@code null} outside its jar.
     */
    private static Map<String, Object> health() {
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("stxt-press", Press.version());
        versions.put("stxt-pdf", Converter.version());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("versions", versions);
        return body;
    }

    /**
     * {@code GET /fonts}: the families, and each face (family, weight, style) with the SHA-256 of its file, which
     * {@code /fonts/{id}} gives. The editor registers them in the page to show the SVG previews with the fonts of the
     * server, which an SVG names and does not carry. Only with the editor on: no other client needs the files of the
     * fonts (a PDF carries them), and a server should not hand them out if it does not have to.
     */
    private void fonts(HttpExchange exchange) throws IOException {
        FontLibrary library = shared().converter().resources().fonts();
        List<Map<String, Object>> faces = new ArrayList<>();
        for (FontFile file : library.all()) {
            Map<String, Object> face = new LinkedHashMap<>();
            face.put("family", file.face().family());
            face.put("weight", file.face().weight().value());
            face.put("style", file.face().style() == FontStyle.ITALIC ? "italic" : "normal");
            face.put("id", file.digest());
            faces.add(face);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("families", List.copyOf(library.families()));
        body.put("faces", faces);
        sendJson(exchange, 200, body);
    }

    /** {@code GET /fonts/{id}}: the file of a face, by the SHA-256 of {@code /fonts}. */
    private void font(HttpExchange exchange, String id) throws IOException, HttpError {
        if (FONT_ID.matcher(id).matches()) {
            for (FontFile file : shared().converter().resources().fonts().all()) {
                if (file.digest().equals(id)) {
                    byte[] content;
                    try (InputStream in = file.content()) {
                        content = in.readAllBytes();
                    }
                    // The id is the content: the file of an id never changes.
                    exchange.getResponseHeaders().set("Cache-Control", "private, max-age=31536000, immutable");
                    send(exchange, 200, file.cff() ? "font/otf" : "font/ttf", content);
                    return;
                }
            }
        }
        throw new HttpError(404, NOT_FOUND, "There is no font " + id);
    }

    /** {@code GET /} and {@code GET /editor/{file}}: a file of the editor, from the jar. */
    private static void editor(HttpExchange exchange, String file) throws IOException, HttpError {
        if (!EDITOR_FILE.matcher(file).matches()) {
            throw new HttpError(404, NOT_FOUND, "There is no file " + file + " in the editor");
        }
        byte[] content;
        try (InputStream in = Routes.class.getResourceAsStream(EDITOR + file)) {
            if (in == null) {
                throw new HttpError(404, NOT_FOUND, file.equals("index.html")
                        ? "The server was built without the editor" : "There is no file " + file + " in the editor");
            }
            content = in.readAllBytes();
        }
        String type = EDITOR_TYPES.getOrDefault(file.substring(file.lastIndexOf('.') + 1), "application/octet-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (type.startsWith("text/html")) {
            exchange.getResponseHeaders().set("Content-Security-Policy", EDITOR_POLICY);
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        }
        send(exchange, 200, type, content);
    }

    /** {@code GET} and {@code POST /print/{path}}. */
    private void print(HttpExchange exchange, String path) throws Exception {
        method(exchange, "GET", "POST");
        Map<String, String> parameters = parameters(exchange, CONVERT_PARAMETERS);
        Format format = format(parameters);
        Template template = template(path);
        Options options = debug(parameters) ? config.templateOptions().withDebug(true) : config.templateOptions();
        Job job = exchange.getRequestMethod().equals("POST")
                ? template.job(options, text(body(exchange)))
                : template.job(options);
        respond(exchange, job, format, template.name());
    }

    /** {@code POST /render}. */
    private void render(HttpExchange exchange) throws Exception {
        method(exchange, "POST");
        Map<String, String> parameters = parameters(exchange, CONVERT_PARAMETERS);
        Format format = format(parameters);
        Job job = RenderBody.job(body(exchange), debug(parameters));
        respond(exchange, job, format, null);
    }

    /** {@code GET /templates}. */
    private void list(HttpExchange exchange) throws Exception {
        method(exchange, "GET");
        parameters(exchange, Set.of());
        List<Map<String, Object>> list = new ArrayList<>();
        for (Templates.Entry entry : templates == null ? List.<Templates.Entry>of() : templates.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", entry.path());
            item.put("styles", entry.styles());
            item.put("data", entry.data());
            list.add(item);
        }
        sendJson(exchange, 200, Map.of("templates", list));
    }

    /** {@code GET /templates/{path}}: the texts of a template, its data as the text of its file. */
    private void template(HttpExchange exchange, String path) throws Exception {
        method(exchange, "GET");
        parameters(exchange, Set.of());
        Template template = template(path);
        List<Map<String, Object>> styles = new ArrayList<>();
        for (Job.Styles item : template.styles()) {
            styles.add(Map.of("path", item.name(), "text", item.text()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", template.path());
        body.put("document", template.document());
        body.put("styles", styles);
        body.put("data", template.data());
        sendJson(exchange, 200, body);
    }

    private Template template(String path) throws IOException, HttpError {
        if (templates == null) {
            throw new HttpError(404, TemplateException.NOT_FOUND, "There is no template " + path
                    + ": the server has no templates folder");
        }
        return templates.get(path);
    }

    /** Converts {@code job} and answers with it in {@code format}; {@code name} names the PDF, if not {@code null}. */
    private void respond(HttpExchange exchange, Job job, Format format, String name) throws Exception {
        if (format == Format.STXT) {
            String filled = job.data() == null ? job.document() : shared().press().fill(job.document(), job.data());
            send(exchange, 200, STXT_TYPE, filled.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!conversions.tryAcquire()) {
            throw new HttpError(503, SERVER_BUSY, "The server is converting as many documents as it can at once",
                    Map.of("Retry-After", "1"));
        }
        try {
            Cancellation cancellation = Cancellation.timeout(config.timeout());
            if (format == Format.SVG) {
                List<String> pages = shared().press().svg(job, cancellation);
                sendJson(exchange, 200, Map.of("pages", pages));
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/pdf");
                if (name != null) {
                    exchange.getResponseHeaders().set("Content-Disposition", "inline; filename=\"" + name + ".pdf\"");
                }
                // Not closed if the job fails: closing would end the body as if the PDF were whole.
                ResponseBody body = new ResponseBody(exchange);
                shared().press().pdf(job, body, cancellation);
                body.close();
            }
        } finally {
            conversions.release();
        }
    }

    /** Answers {@code e}, an error found before the response started. */
    private static void fail(HttpExchange exchange, Exception e) throws IOException {
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
            boolean notFound = template.code().equals(TemplateException.NOT_FOUND);
            status = notFound ? 404 : 500;
            if (!notFound) {
                LOG.log(Level.ERROR, "Error of " + exchange.getRequestURI(), e);
            }
            body.put("code", template.code());
            body.put("message", template.getMessage());
        } else {
            LOG.log(Level.ERROR, "Error of " + exchange.getRequestURI(), e);
            status = 500;
            body.put("code", INTERNAL);
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

    /** {@return whether the request has the token of the server, if it has one} */
    private boolean authorized(HttpExchange exchange) {
        if (config.token() == null) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String scheme = "Bearer ";
        if (header == null || !header.regionMatches(true, 0, scheme, 0, scheme.length())) {
            return false;
        }
        // In constant time, so that the time of the answer does not tell how much of the token is right.
        return MessageDigest.isEqual(header.substring(scheme.length()).getBytes(StandardCharsets.UTF_8),
                config.token().getBytes(StandardCharsets.UTF_8));
    }

    /** That the method of the request is one of {@code allowed}. */
    private static void method(HttpExchange exchange, String... allowed) throws HttpError {
        for (String method : allowed) {
            if (method.equals(exchange.getRequestMethod())) {
                return;
            }
        }
        throw new HttpError(405, METHOD_NOT_ALLOWED, "The method " + exchange.getRequestMethod() + " is not one of "
                + String.join(", ", allowed), Map.of("Allow", String.join(", ", allowed)));
    }

    /** {@return the parameters of the query, which have to be some of {@code allowed}, each once} */
    private static Map<String, String> parameters(HttpExchange exchange, Set<String> allowed) throws HttpError {
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
                throw new HttpError(400, PARAMETER_NOT_VALID, "The query is not valid: " + e.getMessage());
            }
            if (!allowed.contains(key)) {
                throw new HttpError(400, PARAMETER_NOT_VALID, "Unknown parameter " + key
                        + (allowed.isEmpty() ? ": the route has none" : " (" + String.join(", ", allowed.stream()
                                .sorted().toList()) + ")"));
            }
            if (parameters.put(key, value) != null) {
                throw new HttpError(400, PARAMETER_NOT_VALID, "Repeated parameter " + key);
            }
        }
        return parameters;
    }

    private static Format format(Map<String, String> parameters) throws HttpError {
        String format = parameters.getOrDefault("format", "pdf");
        return switch (format) {
            case "pdf" -> Format.PDF;
            case "svg" -> Format.SVG;
            case "stxt" -> Format.STXT;
            default -> throw new HttpError(400, PARAMETER_NOT_VALID, "Unknown format " + format + " (pdf, svg, stxt)");
        };
    }

    private static boolean debug(Map<String, String> parameters) throws HttpError {
        String debug = parameters.getOrDefault("debug", "false");
        return switch (debug) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new HttpError(400, PARAMETER_NOT_VALID, "debug is " + debug + ", not true or false");
        };
    }

    /** {@return the body of the request, if it is not larger than the limit of the server} */
    private byte[] body(HttpExchange exchange) throws IOException, HttpError {
        long max = config.maxBodySize();
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try {
                if (Long.parseLong(length.trim()) > max) {
                    throw tooLarge(max);
                }
            } catch (NumberFormatException e) {
                throw new HttpError(400, BODY_NOT_VALID, "Content-Length is not a number: " + length);
            }
        }
        // Without Content-Length (chunked), the limit is checked as it is read.
        byte[] body = exchange.getRequestBody().readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE - 8));
        if (body.length > max) {
            throw tooLarge(max);
        }
        return body;
    }

    private static HttpError tooLarge(long max) {
        return new HttpError(413, BODY_TOO_LARGE, "The body is larger than the " + max + " bytes the server accepts");
    }

    /** {@return {@code body} as UTF-8 text, strictly decoded} */
    private static String text(byte[] body) throws HttpError {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            throw new HttpError(400, BODY_NOT_VALID, "The body is not UTF-8 text");
        }
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, JSON_TYPE, RenderBody.JSON.writeValueAsBytes(body));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    /**
     * The body of a {@code 200} that is sent as it is written, with the status and the headers sent at the first write.
     * {@link Press#pdf} lays out the document before writing anything, so an error of the job is still answered with its
     * status.
     */
    private static final class ResponseBody extends OutputStream {

        private final HttpExchange exchange;
        private OutputStream out;

        ResponseBody(HttpExchange exchange) {
            this.exchange = exchange;
        }

        private OutputStream out() throws IOException {
            if (out == null) {
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
