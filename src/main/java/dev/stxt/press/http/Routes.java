package dev.stxt.press.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;

import dev.stxt.pdf.convert.Converter;
import dev.stxt.pdf.model.FontStyle;
import dev.stxt.pdf.resources.FontFile;
import dev.stxt.pdf.resources.FontLibrary;
import dev.stxt.pdf.tree.DocumentSchema;
import dev.stxt.press.Job;
import dev.stxt.press.Options;
import dev.stxt.press.Press;
import dev.stxt.press.http.Requests.Format;
import dev.stxt.press.templates.Template;
import dev.stxt.press.templates.Templates;

/**
 * The routes of the service (ROADMAP-PDF 2.4), behind the {@link Gate} of the server.
 *
 * <table>
 * <caption>Routes</caption>
 * <tr><td>{@code GET /print/{path}}</td><td>The template filled with its sample data</td></tr>
 * <tr><td>{@code POST /print/{path}}</td><td>The template filled with the body, the JSON data as they are</td></tr>
 * <tr><td>{@code GET /documents/{path}}</td><td>A document of the documents folder, as it is</td></tr>
 * <tr><td>{@code POST /render}</td><td>Any document ({@link RenderBody})</td></tr>
 * <tr><td>{@code GET /files/templates}</td><td>The templates, with what files each one has, for the editor</td></tr>
 * <tr><td>{@code GET /files/templates/{path}}</td><td>The texts of a template, for the editor</td></tr>
 * <tr><td>{@code GET /files/documents}</td><td>The documents, with what files each one has, for the editor</td></tr>
 * <tr><td>{@code GET /files/documents/{path}}</td><td>The texts of a document, for the editor</td></tr>
 * <tr><td>{@code PUT /files/templates/{path}}, {@code PUT /files/documents/{path}}</td><td>Saves the texts of a
 * template or a document in its files, with {@code Save} on</td></tr>
 * <tr><td>{@code GET /schema}</td><td>The schema {@code dev.stxt.pdf} of the engine</td></tr>
 * <tr><td>{@code GET /fonts}</td><td>The font families of the server, and its faces with their files, for the editor</td></tr>
 * <tr><td>{@code GET /fonts/{id}}</td><td>The file of a face, by its SHA-256, for the editor</td></tr>
 * <tr><td>{@code GET /health}</td><td>That the service is alive, and its versions, without token</td></tr>
 * <tr><td>{@code GET /}</td><td>The index: the templates, the documents and the routes, without token</td></tr>
 * <tr><td>{@code GET /editor}, {@code GET /editor/templates/{path}}, {@code GET /editor/documents/{path}}</td><td>The
 * web editor, blank, on a template or on a document, without token</td></tr>
 * <tr><td>{@code GET /assets/{file}}</td><td>The scripts and styles of the index and the editor, without token</td></tr>
 * </table>
 *
 * <p>{@code /print}, {@code /documents} and {@code /render}, the routes of the clients, answer in the format of
 * {@code ?format=}: {@code pdf} (by default), {@code svg} ({@code {"pages": [...]}}, with {@code &debug=true} for the
 * outlines of the boxes) or {@code stxt} (the filled document). The routes under {@code /files/} are those of the
 * editor. Every error is a JSON body with its {@code code} and its {@code message}; that of a job has also
 * {@code kind}, {@code line}, {@code source} and, if it is of the filled document, {@code filled}.</p>
 */
final class Routes {

    private static final List<String> GET = List.of("GET");
    private static final List<String> POST = List.of("POST");
    private static final List<String> GET_POST = List.of("GET", "POST");
    private static final List<String> GET_PUT = List.of("GET", "PUT");
    private static final Set<String> NONE = Set.of();
    private static final Set<String> CONVERT_PARAMETERS = Set.of("format", "debug");
    /** The id of a font: the SHA-256 of its file, in lowercase hexadecimal. */
    private static final Pattern FONT_ID = Pattern.compile("[0-9a-f]{64}");

    /** Whether a route is a path, or every path that starts with it (and its handler gets the rest). */
    private enum Match {
        EXACT, PREFIX
    }

    /** Whether a route answers without the token of the server. */
    private enum Access {
        OPEN, TOKEN
    }

    /** What answers a route, once its method and its parameters are checked. */
    @FunctionalInterface
    private interface Handler {
        /**
         * @param rest the path after that of a {@link Match#PREFIX} route, not decoded; empty for an exact one
         */
        void handle(HttpExchange exchange, String rest, Map<String, String> parameters) throws Exception;
    }

    /**
     * A route of the table.
     *
     * @param on whether the configuration has it on: a route that is off is {@code 404}, as one that does not exist
     */
    private record Route(String path, Match match, Access access, boolean on, List<String> methods,
            Set<String> parameters, Handler handler) {

        boolean matches(String requested) {
            return match == Match.EXACT ? requested.equals(path) : requested.startsWith(path);
        }
    }

    /** The converter of the server and the conversions that run at once. */
    private final Conversions conversions;
    /** The templates folder, or {@code null} if the server has none. */
    private final Templates templates;
    /** The documents folder, or {@code null} if the server has none. */
    private final Templates documents;
    private final ServerConfig config;
    /** The text of the schema of the engine, read once. */
    private final byte[] schema;
    /**
     * The routes, in the order they are looked at. Those without token go first: a request for any other path needs the
     * token before it is known whether its route exists, so that without it the server does not tell which routes it
     * has ({@code 401}, not {@code 404}).
     */
    private final List<Route> table;

    /**
     * @param conversions the converter of the server and the conversions that run at once
     * @param templates the templates folder, or {@code null} if the server has none
     * @param documents the documents folder, or {@code null} if the server has none
     */
    Routes(Conversions conversions, Templates templates, Templates documents, ServerConfig config) {
        this.conversions = conversions;
        this.templates = templates;
        this.documents = documents;
        this.config = config;
        this.schema = DocumentSchema.text().getBytes(StandardCharsets.UTF_8);
        boolean editor = config.editor();
        // The texts of a template or a document are saved with PUT only with Save on: without it, PUT is a 405.
        List<String> texts = config.save() ? GET_PUT : GET;
        this.table = List.of(
                new Route("/health", Match.EXACT, Access.OPEN, true, GET, NONE,
                        (exchange, rest, parameters) -> Responses.sendJson(exchange, 200, health())),
                // The pages without token: their files have nothing secret, and a browser cannot send the token when
                // it opens a URL. They ask for it when the API answers 401.
                new Route("/", Match.EXACT, Access.OPEN, editor, GET, NONE,
                        (exchange, rest, parameters) -> WebFiles.send(exchange, "index.html")),
                new Route("/editor", Match.EXACT, Access.OPEN, editor, GET, NONE,
                        (exchange, rest, parameters) -> editor(exchange, null)),
                new Route("/editor/templates/", Match.PREFIX, Access.OPEN, editor, GET, NONE,
                        (exchange, rest, parameters) -> editor(exchange, rest)),
                new Route("/editor/documents/", Match.PREFIX, Access.OPEN, editor, GET, NONE,
                        (exchange, rest, parameters) -> editor(exchange, rest)),
                new Route("/assets/", Match.PREFIX, Access.OPEN, editor, GET, NONE,
                        (exchange, rest, parameters) -> asset(exchange, rest)),
                // The routes of the clients: short, and never off.
                new Route("/print/", Match.PREFIX, Access.TOKEN, true, GET_POST, CONVERT_PARAMETERS, this::print),
                new Route("/documents/", Match.PREFIX, Access.TOKEN, true, GET, CONVERT_PARAMETERS,
                        this::printDocument),
                new Route("/render", Match.EXACT, Access.TOKEN, config.render(), POST, CONVERT_PARAMETERS,
                        (exchange, rest, parameters) -> render(exchange, parameters)),
                // The routes of the editor: the files of the templates and the documents, which Files turns off.
                new Route("/files/templates", Match.EXACT, Access.TOKEN, config.files(), GET, NONE,
                        (exchange, rest, parameters) -> list(exchange, templates, Templates.Kind.TEMPLATE)),
                new Route("/files/templates/", Match.PREFIX, Access.TOKEN, config.files(), texts, NONE,
                        (exchange, rest, parameters) -> texts(exchange, templates, Templates.Kind.TEMPLATE, rest)),
                new Route("/files/documents", Match.EXACT, Access.TOKEN, config.files(), GET, NONE,
                        (exchange, rest, parameters) -> list(exchange, documents, Templates.Kind.DOCUMENT)),
                new Route("/files/documents/", Match.PREFIX, Access.TOKEN, config.files(), texts, NONE,
                        (exchange, rest, parameters) -> texts(exchange, documents, Templates.Kind.DOCUMENT, rest)),
                new Route("/schema", Match.EXACT, Access.TOKEN, true, GET, NONE,
                        (exchange, rest, parameters) -> Responses.send(exchange, 200, Responses.STXT_TYPE, schema)),
                new Route("/fonts", Match.EXACT, Access.TOKEN, editor, GET, NONE,
                        (exchange, rest, parameters) -> fonts(exchange)),
                new Route("/fonts/", Match.PREFIX, Access.TOKEN, editor, GET, NONE,
                        (exchange, rest, parameters) -> font(exchange, rest)));
    }

    /** Answers the request with the first route of {@link #table} that it matches and that is on. */
    void route(HttpExchange exchange) throws Exception {
        String path = exchange.getRequestURI().getRawPath();
        Route found = null;
        for (Route route : table) {
            if (route.on() && route.matches(path)) {
                found = route;
                break;
            }
        }
        if ((found == null || found.access() == Access.TOKEN) && !Requests.authorized(exchange, config.token())) {
            throw new HttpError(401, HttpError.UNAUTHORIZED, "The request has no valid token (Authorization: Bearer)",
                    Map.of("WWW-Authenticate", "Bearer"));
        }
        if (found == null) {
            throw new HttpError(404, HttpError.NOT_FOUND, "There is no route " + path);
        }
        Requests.method(exchange, found.methods());
        Map<String, String> parameters = Requests.parameters(exchange, found.parameters());
        found.handler().handle(exchange, found.match() == Match.PREFIX ? path.substring(found.path().length()) : "",
                parameters);
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
     * {@code GET /editor} ({@code path} {@code null}), {@code GET /editor/templates/{path}} and
     * {@code GET /editor/documents/{path}}: the page is the same for every template and document, and whether it exists
     * is not looked at, since that would tell it without token. The page asks {@code /files/templates/{path}} or
     * {@code /files/documents/{path}} for it, with the token.
     */
    private static void editor(HttpExchange exchange, String path) throws IOException, HttpError {
        if (path != null && !Templates.isPath(path)) {
            throw new HttpError(404, HttpError.NOT_FOUND, "There is no route " + exchange.getRequestURI().getRawPath());
        }
        WebFiles.send(exchange, "editor.html");
    }

    /** {@code GET /assets/{file}}. */
    private static void asset(HttpExchange exchange, String file) throws IOException, HttpError {
        // A page has one URL: / or /editor.
        if (file.endsWith(".html")) {
            throw new HttpError(404, HttpError.NOT_FOUND, "There is no file " + file + " in the assets");
        }
        WebFiles.send(exchange, file);
    }

    /**
     * {@code GET /fonts}: the families, and each face (family, weight, style) with the SHA-256 of its file, which
     * {@code /fonts/{id}} gives. The editor registers them in the page to show the SVG previews with the fonts of the
     * server, which an SVG names and does not carry. Only with the editor on: no other client needs the files of the
     * fonts (a PDF carries them), and a server should not hand them out if it does not have to.
     */
    private void fonts(HttpExchange exchange) throws IOException {
        FontLibrary library = conversions.converter().resources().fonts();
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
        Responses.sendJson(exchange, 200, body);
    }

    /** {@code GET /fonts/{id}}: the file of a face, by the SHA-256 of {@code /fonts}. */
    private void font(HttpExchange exchange, String id) throws IOException, HttpError {
        if (FONT_ID.matcher(id).matches()) {
            for (FontFile file : conversions.converter().resources().fonts().all()) {
                if (file.digest().equals(id)) {
                    byte[] content;
                    try (InputStream in = file.content()) {
                        content = in.readAllBytes();
                    }
                    // The id is the content: the file of an id never changes.
                    exchange.getResponseHeaders().set("Cache-Control", "private, max-age=31536000, immutable");
                    Responses.send(exchange, 200, file.cff() ? "font/otf" : "font/ttf", content);
                    return;
                }
            }
        }
        throw new HttpError(404, HttpError.NOT_FOUND, "There is no font " + id);
    }

    /** {@code GET} and {@code POST /print/{path}}: a template. */
    private void print(HttpExchange exchange, String path, Map<String, String> parameters) throws Exception {
        Format format = Requests.format(parameters);
        Template template = get(templates, Templates.Kind.TEMPLATE, path);
        Options options = options(parameters);
        Job job = exchange.getRequestMethod().equals("POST")
                ? template.job(options, Requests.text(Requests.body(exchange, config.maxBodySize())))
                : template.job(options);
        conversions.respond(exchange, job, format, template.name());
    }

    /** {@code GET /documents/{path}}: the document as it is, with its chain of styles. */
    private void printDocument(HttpExchange exchange, String path, Map<String, String> parameters) throws Exception {
        Format format = Requests.format(parameters);
        Template document = get(documents, Templates.Kind.DOCUMENT, path);
        conversions.respond(exchange, document.job(options(parameters)), format, document.name());
    }

    /** {@return the options of the templates and the documents, with the outlines if the request asks for them} */
    private Options options(Map<String, String> parameters) throws HttpError {
        return Requests.debug(parameters) ? config.templateOptions().withDebug(true) : config.templateOptions();
    }

    /** {@code POST /render}. */
    private void render(HttpExchange exchange, Map<String, String> parameters) throws Exception {
        Format format = Requests.format(parameters);
        Job job = RenderBody.job(Requests.body(exchange, config.maxBodySize()), Requests.debug(parameters));
        conversions.respond(exchange, job, format, null);
    }

    /**
     * {@code GET /files/templates} and {@code GET /files/documents}: the paths, each with whether it has its own styles
     * and, a template, sample data; and whether new ones can be created ({@code writable}, with {@code Save} on).
     */
    private void list(HttpExchange exchange, Templates folder, Templates.Kind kind) throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Templates.Entry entry : folder == null ? List.<Templates.Entry>of() : folder.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", entry.path());
            item.put("styles", entry.styles());
            if (kind == Templates.Kind.TEMPLATE) {
                item.put("data", entry.data());
            }
            list.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(kind.noun() + "s", list);
        body.put("writable", config.save() && folder != null);
        Responses.sendJson(exchange, 200, body);
    }

    /**
     * {@code GET /files/templates/{path}} and {@code GET /files/documents/{path}}: the texts of a template, its data as
     * the text of its file, or of a document, without data; with its version as {@code ETag}, and whether it can be
     * saved ({@code writable}). With {@code PUT}, the save ({@link #save}).
     */
    private void texts(HttpExchange exchange, Templates folder, Templates.Kind kind, String path) throws Exception {
        if (exchange.getRequestMethod().equals("PUT")) {
            save(exchange, folder, kind, path);
            return;
        }
        sendTexts(exchange, 200, kind, get(folder, kind, path), null);
    }

    /**
     * {@code PUT /files/templates/{path}} and {@code PUT /files/documents/{path}}, with {@code Save} on: writes the
     * files of the body ({@link SaveBody}) if they are still those of the version of {@code If-Match}, the {@code ETag}
     * of the {@code GET} they come from. It answers as the {@code GET}, with the new version, and the files it wrote
     * ({@code written}). With {@code If-None-Match: *} instead, it creates a new one ({@link Templates#create}), which
     * must not exist ({@code 412}), and answers {@code 201}. A JSON body is required, so that a form of another site
     * cannot send one without the preflight of CORS, which the server does not answer.
     */
    private void save(HttpExchange exchange, Templates folder, Templates.Kind kind, String path) throws Exception {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith(Responses.JSON_TYPE)) {
            throw new HttpError(415, HttpError.CONTENT_TYPE_NOT_VALID, "A save takes a body of type "
                    + Responses.JSON_TYPE);
        }
        String noneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        boolean create = noneMatch != null && noneMatch.trim().equals("*");
        String match = exchange.getRequestHeaders().getFirst("If-Match");
        if (!create && (match == null || match.isBlank())) {
            throw new HttpError(428, HttpError.VERSION_REQUIRED, "A save takes If-Match with the ETag of the GET of the "
                    + kind.noun() + ", so that it does not undo a change made since, or If-None-Match: * to create one");
        }
        Templates.Change change = SaveBody.change(Requests.body(exchange, config.maxBodySize()), kind);
        if (folder == null) {
            throw kind.notFound(path, "the server has no " + kind.noun() + "s folder");
        }
        Templates.Saved saved;
        try {
            saved = create ? folder.create(path, change) : folder.save(path, unquote(match), change);
        } catch (IllegalArgumentException e) {
            throw new HttpError(400, HttpError.BODY_NOT_VALID, e.getMessage());
        }
        sendTexts(exchange, create ? 201 : 200, kind, saved.template(), saved.written());
    }

    /** {@return the version of an {@code If-Match}: without its quotes and without a weak {@code W/}} */
    private static String unquote(String match) {
        String value = match.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    /** Answers with the texts of {@code template}, its version and, after a save, the files written. */
    private void sendTexts(HttpExchange exchange, int status, Templates.Kind kind, Template template,
            List<String> written) throws IOException {
        List<Map<String, Object>> styles = new ArrayList<>();
        for (Job.Styles item : template.styles()) {
            styles.add(Map.of("path", item.name(), "text", item.text()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", template.path());
        body.put("document", template.document());
        body.put("styles", styles);
        if (kind == Templates.Kind.TEMPLATE) {
            body.put("data", template.data());
        }
        body.put("writable", config.save());
        if (written != null) {
            body.put("written", written);
        }
        exchange.getResponseHeaders().set("ETag", "\"" + template.version() + "\"");
        Responses.sendJson(exchange, status, body);
    }

    /** {@return the template or the document of {@code path} in {@code folder}, which may be {@code null}} */
    private static Template get(Templates folder, Templates.Kind kind, String path) throws IOException {
        if (folder == null) {
            throw kind.notFound(path, "the server has no " + kind.noun() + "s folder");
        }
        return folder.get(path);
    }
}
