package dev.stxt.press.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
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
import dev.stxt.press.templates.TemplateException;
import dev.stxt.press.templates.Templates;

/**
 * The routes of the service (ROADMAP-PDF 2.4), behind the {@link Gate} of the server.
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
 * <tr><td>{@code GET /}</td><td>The index: the templates and the routes, without token</td></tr>
 * <tr><td>{@code GET /editor}, {@code GET /editor/{path}}</td><td>The web editor, blank or on a template, without
 * token</td></tr>
 * <tr><td>{@code GET /assets/{file}}</td><td>The scripts and styles of the index and the editor, without token</td></tr>
 * </table>
 *
 * <p>{@code /print} and {@code /render} answer in the format of {@code ?format=}: {@code pdf} (by default),
 * {@code svg} ({@code {"pages": [...]}}, with {@code &debug=true} for the outlines of the boxes) or {@code stxt} (the
 * filled document). Every error is a JSON body with its {@code code} and its {@code message}; that of a job has also
 * {@code kind}, {@code line}, {@code source} and, if it is of the filled document, {@code filled}.</p>
 */
final class Routes {

    private static final List<String> GET = List.of("GET");
    private static final List<String> POST = List.of("POST");
    private static final List<String> GET_POST = List.of("GET", "POST");
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
     * @param converter what creates the converter of the server, called once by the first request that needs it
     */
    Routes(Supplier<Converter> converter, Templates templates, ServerConfig config) {
        this.conversions = new Conversions(converter, config.concurrentConversions(), config.timeout());
        this.templates = templates;
        this.config = config;
        this.schema = DocumentSchema.text().getBytes(StandardCharsets.UTF_8);
        boolean editor = config.editor();
        this.table = List.of(
                new Route("/health", Match.EXACT, Access.OPEN, true, GET, NONE,
                        (exchange, rest, parameters) -> Responses.sendJson(exchange, 200, health())),
                // The pages without token: their files have nothing secret, and a browser cannot send the token when
                // it opens a URL. They ask for it when the API answers 401.
                new Route("/", Match.EXACT, Access.OPEN, editor, GET, NONE,
                        (exchange, rest, parameters) -> WebFiles.send(exchange, "index.html")),
                new Route("/editor", Match.EXACT, Access.OPEN, editor, GET, Set.of("mode"),
                        (exchange, rest, parameters) -> editor(exchange, null, parameters)),
                new Route("/editor/", Match.PREFIX, Access.OPEN, editor, GET, Set.of("mode"), Routes::editor),
                new Route("/assets/", Match.PREFIX, Access.OPEN, editor, GET, NONE,
                        (exchange, rest, parameters) -> asset(exchange, rest)),
                new Route("/print/", Match.PREFIX, Access.TOKEN, true, GET_POST, CONVERT_PARAMETERS, this::print),
                new Route("/render", Match.EXACT, Access.TOKEN, config.render(), POST, CONVERT_PARAMETERS,
                        (exchange, rest, parameters) -> render(exchange, parameters)),
                new Route("/templates", Match.EXACT, Access.TOKEN, config.templates(), GET, NONE,
                        (exchange, rest, parameters) -> list(exchange)),
                new Route("/templates/", Match.PREFIX, Access.TOKEN, config.templates(), GET, NONE,
                        (exchange, rest, parameters) -> template(exchange, rest)),
                new Route("/schema", Match.EXACT, Access.TOKEN, true, GET, NONE,
                        (exchange, rest, parameters) -> Responses.send(exchange, 200, Responses.STXT_TYPE, schema)),
                new Route("/fonts", Match.EXACT, Access.TOKEN, editor, GET, NONE,
                        (exchange, rest, parameters) -> fonts(exchange)),
                new Route("/fonts/", Match.PREFIX, Access.TOKEN, editor, GET, NONE,
                        (exchange, rest, parameters) -> font(exchange, rest)));
    }

    /**
     * Warms the server up: creates its converter, which reads the whole resources folder, and converts its templates
     * ({@link WarmUp}).
     *
     * @throws dev.stxt.pdf.model.StxtPdfException if the converter cannot be created: its resources
     */
    WarmUp.Result warmUp() {
        return conversions.warmUp(templates, config.templateOptions());
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
     * {@code GET /editor} ({@code path} {@code null}) and {@code GET /editor/{path}}: the page is the same for every
     * template, and whether it exists is not looked at, since that would tell it without token. The page asks
     * {@code /templates/{path}} for it, with the token.
     */
    private static void editor(HttpExchange exchange, String path, Map<String, String> parameters)
            throws IOException, HttpError {
        Requests.mode(parameters);
        if (path != null && !Templates.isPath(path)) {
            throw new HttpError(404, HttpError.NOT_FOUND, "There is no route /editor/" + path);
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

    /** {@code GET} and {@code POST /print/{path}}. */
    private void print(HttpExchange exchange, String path, Map<String, String> parameters) throws Exception {
        Format format = Requests.format(parameters);
        Template template = template(path);
        Options options = Requests.debug(parameters) ? config.templateOptions().withDebug(true)
                : config.templateOptions();
        Job job = exchange.getRequestMethod().equals("POST")
                ? template.job(options, Requests.text(Requests.body(exchange, config.maxBodySize())))
                : template.job(options);
        conversions.respond(exchange, job, format, template.name());
    }

    /** {@code POST /render}. */
    private void render(HttpExchange exchange, Map<String, String> parameters) throws Exception {
        Format format = Requests.format(parameters);
        Job job = RenderBody.job(Requests.body(exchange, config.maxBodySize()), Requests.debug(parameters));
        conversions.respond(exchange, job, format, null);
    }

    /** {@code GET /templates}. */
    private void list(HttpExchange exchange) throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Templates.Entry entry : templates == null ? List.<Templates.Entry>of() : templates.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", entry.path());
            item.put("styles", entry.styles());
            item.put("data", entry.data());
            list.add(item);
        }
        Responses.sendJson(exchange, 200, Map.of("templates", list));
    }

    /** {@code GET /templates/{path}}: the texts of a template, its data as the text of its file. */
    private void template(HttpExchange exchange, String path) throws Exception {
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
        Responses.sendJson(exchange, 200, body);
    }

    private Template template(String path) throws IOException, HttpError {
        if (templates == null) {
            throw new HttpError(404, TemplateException.NOT_FOUND, "There is no template " + path
                    + ": the server has no templates folder");
        }
        return templates.get(path);
    }
}
