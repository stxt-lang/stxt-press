package dev.stxt.press.config;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import dev.stxt.InlineNode;
import dev.stxt.Node;
import dev.stxt.Parser;
import dev.stxt.exceptions.ParseException;
import dev.stxt.exceptions.STXTException;
import dev.stxt.pdf.convert.Converter;
import dev.stxt.pdf.model.Check;
import dev.stxt.pdf.model.Limits;
import dev.stxt.pdf.model.PdfA;
import dev.stxt.pdf.resources.Resources;
import dev.stxt.press.Options;
import dev.stxt.press.http.PressServer;
import dev.stxt.press.http.ServerConfig;
import dev.stxt.press.templates.Templates;
import dev.stxt.schema.SchemaProviderMemory;
import dev.stxt.schema.SchemaValidator;
import dev.stxt.utils.FileUtils;

/**
 * The configuration of a server (ROADMAP-PDF 2.5): what {@code server.stxt} says, or the defaults without it. It is
 * read and checked whole when the server starts, so that a node that does not exist, a value that is not valid, a
 * folder that is not there or a token that is not set stops the start with its code and its line
 * ({@link ConfigException}), and a change in the file needs a restart.
 *
 * <pre>
 * Server (dev.stxt.press):
 *     Host: 0.0.0.0
 *     Port: 8080
 *     Token env: STXT_PRESS_TOKEN
 *     Resource folder: resources
 *     Template folder: templates
 *     Routes:
 *         Render: false
 *     Limits:
 *         Timeout: 20
 *     Template options:
 *         PDF UA: 2
 * </pre>
 *
 * <p>The file is validated with the schema {@code dev.stxt.press} of the jar ({@code /stxt/dev.stxt.press.stxt}), by
 * {@code stxt-core}. Relative paths are relative to the folder of the file, so that a server moves with its
 * configuration. Without a file, the server listens only on {@code 127.0.0.1:8080}, without token, with the resources
 * of {@code resources} in the working directory and without templates.</p>
 */
public final class ServerFile {

    /** The name of the file that is looked for in the working directory. */
    public static final String FILE_NAME = "server.stxt";
    /** The environment variable with the path of the file. */
    public static final String ENV = "STXT_PRESS_CONFIG";
    /** The namespace of the file, the one its schema declares. */
    public static final String NAMESPACE = "dev.stxt.press";

    private static final String SCHEMA = "/stxt/" + NAMESPACE + ".stxt";
    private static final SchemaProviderMemory PROVIDER = loadSchema();
    /** The name of an environment variable, as POSIX shells take it. */
    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Logger LOG = System.getLogger("dev.stxt.press");

    private final Path file;
    private final ServerConfig http;
    private final Resources resources;
    private final Limits limits;
    private final boolean preloadResources;
    private final boolean lazyInit;
    private final Path templateFolder;

    private ServerFile(Path file, ServerConfig http, Resources resources, Limits limits, boolean preloadResources,
            boolean lazyInit, Path templateFolder) {
        this.file = file;
        this.http = http;
        this.resources = resources;
        this.limits = limits;
        this.preloadResources = preloadResources;
        this.lazyInit = lazyInit;
        this.templateFolder = templateFolder;
    }

    /**
     * Finds the configuration file. The first of these that is given is the only one used: the path of {@code option}
     * ({@code --config}), that of the variable {@link #ENV}, and {@link #FILE_NAME} in the working directory. A path that
     * {@code option} or the variable gives and is not a file is an error, not a reason to look at the next one.
     *
     * @param option the path of {@code --config}, or {@code null} if it was not given
     * @param env the environment variables; not {@code null}
     * @param workingDirectory what relative paths are relative to; not {@code null}
     * @return the file, or empty if none was given and there is no {@link #FILE_NAME} in the working directory
     * @throws ConfigException {@link ConfigException#NOT_FOUND} if the given path is not a file
     */
    public static Optional<Path> find(String option, Map<String, String> env, Path workingDirectory)
            throws ConfigException {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        if (option != null) {
            return Optional.of(given(option, "--config", workingDirectory));
        }
        String variable = env.get(ENV);
        if (variable != null) {
            return Optional.of(given(variable, ENV, workingDirectory));
        }
        Path local = workingDirectory.resolve(FILE_NAME);
        return Files.isRegularFile(local) ? Optional.of(local) : Optional.empty();
    }

    private static Path given(String path, String from, Path workingDirectory) throws ConfigException {
        Path file;
        try {
            file = workingDirectory.resolve(path);
        } catch (InvalidPathException e) {
            throw new ConfigException(ConfigException.NOT_FOUND, Path.of(""), ConfigException.NO_LINE, from
                    + " is not a path: " + path);
        }
        if (path.isEmpty() || !Files.isRegularFile(file)) {
            throw new ConfigException(ConfigException.NOT_FOUND, file, ConfigException.NO_LINE, path.isEmpty()
                    ? from + " is empty" : "The configuration file of " + from + " does not exist or is not a file");
        }
        return file;
    }

    /**
     * The configuration of a server started from {@code workingDirectory}: that of the file {@link #find} finds, read
     * with {@link #read}, or {@link #defaults} if there is none.
     *
     * @param option the path of {@code --config}, or {@code null} if it was not given
     * @param env the environment variables, with the token; not {@code null}
     * @param workingDirectory the working directory; not {@code null}
     * @return the configuration
     * @throws ConfigException if the file cannot be found or is not valid
     * @throws IOException if the file cannot be read, or is not UTF-8
     */
    public static ServerFile load(String option, Map<String, String> env, Path workingDirectory)
            throws ConfigException, IOException {
        Optional<Path> file = find(option, env, workingDirectory);
        return file.isPresent() ? read(file.get(), env) : defaults(workingDirectory);
    }

    /**
     * The configuration of a server without file: {@link ServerConfig#DEFAULT} (only {@code 127.0.0.1:8080}, without
     * token), the folder {@code resources} of {@code workingDirectory}, the limits of the engine and no templates.
     *
     * @param workingDirectory what the folder of the resources is relative to; not {@code null}
     * @return the configuration
     */
    public static ServerFile defaults(Path workingDirectory) {
        return new ServerFile(null, ServerConfig.DEFAULT,
                new Resources(workingDirectory.resolve(Resources.DEFAULT_ROOT)), Limits.DEFAULT, false, false, null);
    }

    /**
     * Reads a configuration file.
     *
     * @param file the file, {@code server.stxt}; not {@code null}
     * @param env the environment variables, where {@code Token env} is looked up; not {@code null}
     * @return the configuration
     * @throws ConfigException if the file is not valid: with the code of {@code stxt-core} for its syntax and its
     *         schema, or one of {@link ConfigException}
     * @throws IOException if the file cannot be read, or is not UTF-8
     */
    public static ServerFile read(Path file, Map<String, String> env) throws ConfigException, IOException {
        Objects.requireNonNull(env, "env");
        String text = FileUtils.readFileContent(file.toFile());
        List<Node> roots;
        try {
            Parser parser = new Parser();
            parser.registerValidator(new SchemaValidator(PROVIDER));
            roots = parser.parse(text);
        } catch (ParseException e) {
            throw new ConfigException(e.getCode(), file, Math.max(e.getLine(), ConfigException.NO_LINE),
                    e.getMessage(), e);
        } catch (STXTException e) {
            throw new ConfigException(e.getCode(), file, ConfigException.NO_LINE, e.getMessage(), e);
        }
        if (roots.size() != 1 || !NAMESPACE.equals(roots.get(0).getNamespace())
                || !roots.get(0).getCanonicalName().equals("server") || !(roots.get(0) instanceof InlineNode)) {
            int line = roots.size() > 1 ? roots.get(1).getLine() : roots.isEmpty() ? 1 : roots.get(0).getLine();
            throw new ConfigException(ConfigException.ROOT, file, line, "The file must have exactly one root node, "
                    + "Server (" + NAMESPACE + "):");
        }
        return new Reader(file, env).read((InlineNode) roots.get(0));
    }

    /** {@return the file it was read from, or {@code null} if these are the defaults} */
    public Path file() {
        return file;
    }

    /** {@return what the HTTP service takes: address, token, routes, limits of the requests and template options} */
    public ServerConfig http() {
        return http;
    }

    /** {@return the resources of the shared converter, which read nothing until a conversion asks} */
    public Resources resources() {
        return resources;
    }

    /** {@return the limits of the engine} */
    public Limits limits() {
        return limits;
    }

    /** {@return whether the whole resources folder is read when the converter is created} */
    public boolean preloadResources() {
        return preloadResources;
    }

    /** {@return whether the converter is created by the first request that needs it instead of at the start} */
    public boolean lazyInit() {
        return lazyInit;
    }

    /** {@return the templates folder, or {@code null} if the server has none} */
    public Path templateFolder() {
        return templateFolder;
    }

    /**
     * {@return the shared converter of the server: its resources and its limits}
     *
     * @throws dev.stxt.pdf.resources.ResourceException with {@link #preloadResources()}, if the fonts cannot be read
     */
    public Converter converter() {
        return Converter.builder().resources(resources).limits(limits).preloadResources(preloadResources).build();
    }

    /**
     * Starts the server of this configuration: with its converter created now or, with {@link #lazyInit()}, by the first
     * request that needs it. Without token, it says so in the log.
     *
     * @return the server, already answering
     * @throws IOException if it cannot listen on its address, or the templates folder cannot be read
     * @throws dev.stxt.pdf.resources.ResourceException without {@link #lazyInit()} and with
     *         {@link #preloadResources()}, if the fonts cannot be read
     */
    public PressServer start() throws IOException {
        Templates templates = templateFolder == null ? null : new Templates(templateFolder);
        PressServer server = lazyInit ? PressServer.startLazily(this::converter, templates, http)
                : PressServer.start(converter(), templates, http);
        if (http.token() == null) {
            LOG.log(Level.WARNING, "The server has no token: every route answers without one (Token env in "
                    + FILE_NAME + ")");
        }
        return server;
    }

    @Override
    public String toString() {
        return "ServerFile[file=" + file + ", http=" + http + ", limits=" + limits + ", preloadResources="
                + preloadResources + ", lazyInit=" + lazyInit + ", templateFolder=" + templateFolder + "]";
    }

    private static SchemaProviderMemory loadSchema() {
        try (InputStream in = ServerFile.class.getResourceAsStream(SCHEMA)) {
            if (in == null) {
                throw new IllegalStateException("Schema " + SCHEMA + " not found in the classpath");
            }
            SchemaProviderMemory provider = new SchemaProviderMemory();
            provider.addSchema(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return provider;
        } catch (IOException e) {
            throw new IllegalStateException("Schema " + SCHEMA + " cannot be read", e);
        }
    }

    /** The reading of a file the schema already validated: the values it does not check, and the defaults. */
    private static final class Reader {

        private final Path file;
        private final Path folder;
        private final Map<String, String> env;

        Reader(Path file, Map<String, String> env) {
            this.file = file;
            this.folder = file.toAbsolutePath().normalize().getParent();
            this.env = env;
        }

        ServerFile read(InlineNode server) throws ConfigException {
            ServerConfig http = ServerConfig.DEFAULT;
            String host = text(server, "Host", http.host());
            int port = (int) number(server, "Port", http.port(), 0, 65535);
            http = http.withAddress(host, port).withToken(token(server));

            InlineNode routes = group(server, "Routes");
            if (routes != null) {
                http = http.withRoutes(bool(routes, "Render", http.render()),
                        bool(routes, "Templates", http.templates()));
            }

            Limits limits = Limits.DEFAULT;
            InlineNode group = group(server, "Limits");
            if (group != null) {
                http = http.withLimits(number(group, "Max body size", http.maxBodySize(), 1, Long.MAX_VALUE),
                        Duration.ofSeconds(number(group, "Timeout", http.timeout().toSeconds(), 1,
                                Long.MAX_VALUE / 1_000_000_000L)),
                        (int) number(group, "Concurrent conversions", http.concurrentConversions(), 1,
                                Integer.MAX_VALUE));
                limits = new Limits((int) number(group, "Max pages", limits.maxPages(), 1, Integer.MAX_VALUE),
                        number(group, "Max image pixels", limits.maxImagePixels(), 1, Long.MAX_VALUE),
                        (int) number(group, "Max SVG elements", limits.maxSvgElements(), 1, Integer.MAX_VALUE),
                        (int) number(group, "Max SVG depth", limits.maxSvgDepth(), 1, Integer.MAX_VALUE),
                        number(group, "Max PDF bytes", limits.maxPdfBytes(), 1, Long.MAX_VALUE));
            }

            InlineNode options = group(server, "Template options");
            if (options != null) {
                http = http.withTemplateOptions(options(options));
            }

            Node templates = server.getChild("Template folder");
            return new ServerFile(file, http, resources(server), limits,
                    bool(server, "Preload resources", false), bool(server, "Lazy init", false),
                    templates == null ? null : folder(templates));
        }

        /** {@return the token of the variable {@code Token env} names, or {@code null} without {@code Token env}} */
        private String token(InlineNode server) throws ConfigException {
            Node node = server.getChild("Token env");
            if (node == null) {
                return null;
            }
            String name = node.getText();
            if (!VARIABLE.matcher(name).matches()) {
                throw error(ConfigException.VALUE_NOT_VALID, node, "Token env is not the name of an environment "
                        + "variable: " + name);
            }
            String token = env.get(name);
            if (token == null || token.isBlank()) {
                // The token is never in the message, only the name of its variable.
                throw error(ConfigException.TOKEN_NOT_SET, node, "The variable " + name + " of Token env is "
                        + (token == null ? "not set" : "empty") + ": without it the server would start without token");
            }
            return token.strip();
        }

        private Resources resources(InlineNode server) throws ConfigException {
            Node root = server.getChild("Resource folder");
            Node fonts = server.getChild("Font folder");
            List<Node> images = server.getChildren("Image folder");
            if (root != null && (fonts != null || !images.isEmpty())) {
                Node second = fonts != null ? fonts : images.get(0);
                throw error(ConfigException.CONFLICT, second, second.getName() + " does not go with Resource "
                        + "folder, which has the fonts in fonts/ and the images in images/");
            }
            if (fonts == null && images.isEmpty()) {
                return new Resources(root == null ? folder.resolve(Resources.DEFAULT_ROOT) : folder(root));
            }
            if (fonts == null) {
                throw error(ConfigException.CONFLICT, images.get(0), "Image folder without Font folder: the server "
                        + "would have no fonts");
            }
            Resources.Builder builder = Resources.builder().fonts(folder(fonts));
            for (Node node : images) {
                Node prefix = ((InlineNode) node).getChild("Prefix");
                try {
                    if (prefix == null) {
                        builder.images(folder(node));
                    } else {
                        builder.images(prefix.getText(), folder(node));
                    }
                } catch (IllegalArgumentException e) {
                    throw error(ConfigException.VALUE_NOT_VALID, prefix == null ? node : prefix, e.getMessage());
                }
            }
            return builder.build();
        }

        private Options options(InlineNode group) throws ConfigException {
            Options options = Options.DEFAULT;
            Node ua = group.getChild("PDF UA");
            if (ua != null) {
                options = options.withPdfUa(Integer.parseInt(ua.getText()));
            }
            Node a = group.getChild("PDF A");
            if (a != null) {
                if (options.pdfUa() == 2) {
                    throw error(ConfigException.CONFLICT, a, "PDF/UA-2 is a PDF 2.0 file and PDF/A a PDF 1.7 one: a "
                            + "PDF cannot comply with both");
                }
                options = options.withPdfA(PdfA.valueOf(a.getText()));
            }
            Set<Check> excluded = EnumSet.noneOf(Check.class);
            for (Node node : group.getChildren("Exclude")) {
                if (!excluded.add(Check.valueOf(node.getText()))) {
                    throw error(ConfigException.VALUE_NOT_VALID, node, "Repeated Exclude: " + node.getText());
                }
            }
            return options.withExcluded(excluded);
        }

        /** {@return the folder of {@code node}, relative to the folder of the file, which has to exist} */
        private Path folder(Node node) throws ConfigException {
            String value = node.getText();
            Path path;
            try {
                path = value.isEmpty() ? null : folder.resolve(value).normalize();
            } catch (InvalidPathException e) {
                path = null;
            }
            if (path == null) {
                throw error(ConfigException.VALUE_NOT_VALID, node, node.getName() + " is not a path: " + value);
            }
            if (!Files.isDirectory(path)) {
                throw error(ConfigException.FOLDER_NOT_FOUND, node, "The folder " + path + " of " + node.getName()
                        + " does not exist or is not a folder");
            }
            return path;
        }

        private static InlineNode group(InlineNode parent, String name) {
            return (InlineNode) parent.getChild(name);
        }

        private String text(InlineNode parent, String name, String otherwise) throws ConfigException {
            Node node = parent.getChild(name);
            if (node == null) {
                return otherwise;
            }
            if (node.getText().isEmpty()) {
                throw error(ConfigException.VALUE_NOT_VALID, node, name + " is empty");
            }
            return node.getText();
        }

        private static boolean bool(InlineNode parent, String name, boolean otherwise) {
            Node node = parent.getChild(name);
            return node == null ? otherwise : Boolean.parseBoolean(node.getText());
        }

        /** {@return the natural number of the child {@code name}, from {@code min} to {@code max}} */
        private long number(InlineNode parent, String name, long otherwise, long min, long max)
                throws ConfigException {
            Node node = parent.getChild(name);
            if (node == null) {
                return otherwise;
            }
            String range = max == Long.MAX_VALUE ? "at least " + min : "from " + min + " to " + max;
            long value;
            try {
                value = Long.parseLong(node.getText());
            } catch (NumberFormatException e) {
                // The schema accepts only digits: it is a number too large for a long.
                throw error(ConfigException.VALUE_NOT_VALID, node, name + " is " + node.getText() + " and has to be "
                        + range);
            }
            if (value < min || value > max) {
                throw error(ConfigException.VALUE_NOT_VALID, node, name + " is " + node.getText() + " and has to be "
                        + range);
            }
            return value;
        }

        private ConfigException error(String code, Node node, String message) {
            return new ConfigException(code, file, node.getLine(), message);
        }
    }
}
