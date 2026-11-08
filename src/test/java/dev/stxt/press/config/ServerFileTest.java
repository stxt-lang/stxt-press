package dev.stxt.press.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.stxt.Node;
import dev.stxt.Parser;
import dev.stxt.InlineNode;
import dev.stxt.pdf.model.Check;
import dev.stxt.pdf.model.Limits;
import dev.stxt.pdf.model.PdfA;
import dev.stxt.pdf.model.StxtPdfException;
import dev.stxt.press.Options;
import dev.stxt.press.http.PressServer;
import dev.stxt.press.http.ServerConfig;

/**
 * {@code server.stxt} (ROADMAP-PDF 2.5): where it is looked for, the defaults, the relative paths, every value and the
 * errors with their line, and the server it starts (the token, the routes, the lazy converter), over HTTP.
 */
class ServerFileTest {

    /** The fonts of the engine, an absolute path so that the file can be anywhere. */
    private static final String FONTS = Path.of("..", "stxt-pdf", "resources").toAbsolutePath().normalize()
            .toString();

    @TempDir
    Path dir;

    private Path work;
    private Path config;

    @BeforeEach
    void folders() throws IOException {
        work = Files.createDirectory(dir.resolve("work"));
        config = Files.createDirectory(dir.resolve("config"));
    }

    // ---- Where it is looked for

    @Test
    void theOptionGoesFirstThenTheVariableThenTheWorkingDirectory() throws Exception {
        Path option = write(config.resolve("option.stxt"), "Server (dev.stxt.press):");
        Path variable = write(config.resolve("variable.stxt"), "Server (dev.stxt.press):");
        Path local = write(work.resolve("server.stxt"), "Server (dev.stxt.press):");
        Map<String, String> env = Map.of(ServerFile.ENV, variable.toString());

        assertEquals(Optional.of(option), ServerFile.find(option.toString(), env, work));
        assertEquals(Optional.of(variable), ServerFile.find(null, env, work));
        assertEquals(Optional.of(local), ServerFile.find(null, Map.of(), work));
        Files.delete(local);
        assertEquals(Optional.empty(), ServerFile.find(null, Map.of(), work));
    }

    @Test
    void relativePathsOfTheOptionAndTheVariableAreOfTheWorkingDirectory() throws Exception {
        Path file = write(work.resolve("press.stxt"), "Server (dev.stxt.press):");

        assertEquals(Optional.of(file), ServerFile.find("press.stxt", Map.of(), work));
        assertEquals(Optional.of(file), ServerFile.find(null, Map.of(ServerFile.ENV, "press.stxt"), work));
    }

    @Test
    void aGivenPathThatIsNotAFileIsAnErrorAndNotTheNextOne() throws Exception {
        write(work.resolve("server.stxt"), "Server (dev.stxt.press):");

        ConfigException option = assertThrows(ConfigException.class,
                () -> ServerFile.find("missing.stxt", Map.of(), work));
        ConfigException variable = assertThrows(ConfigException.class,
                () -> ServerFile.find(null, Map.of(ServerFile.ENV, "missing.stxt"), work));
        ConfigException folder = assertThrows(ConfigException.class,
                () -> ServerFile.find(config.toString(), Map.of(), work));
        ConfigException empty = assertThrows(ConfigException.class,
                () -> ServerFile.find(null, Map.of(ServerFile.ENV, ""), work));

        for (ConfigException e : new ConfigException[] {option, variable, folder, empty}) {
            assertEquals(ConfigException.NOT_FOUND, e.code());
            assertEquals(ConfigException.NO_LINE, e.line());
        }
        assertTrue(option.getMessage().contains("--config"), option.getMessage());
        assertTrue(variable.getMessage().contains(ServerFile.ENV), variable.getMessage());
        assertTrue(empty.getMessage().contains("is empty"), empty.getMessage());
    }

    // ---- The defaults

    @Test
    void withoutFileTheServerIsLocalWithoutTokenAndWithoutTemplates() throws Exception {
        ServerFile defaults = ServerFile.load(null, Map.of(), work);

        assertNull(defaults.file());
        assertEquals(ServerConfig.DEFAULT, defaults.http());
        assertEquals("127.0.0.1", defaults.http().host());
        assertNull(defaults.http().token());
        assertEquals(work.resolve("resources").toAbsolutePath().normalize(), defaults.resources().root());
        assertEquals(Limits.DEFAULT, defaults.limits());
        assertFalse(defaults.preloadResources());
        assertFalse(defaults.lazyInit());
        assertNull(defaults.templateFolder());
    }

    @Test
    void anEmptyServerHasTheDefaultsWithTheResourcesNextToTheFile() throws Exception {
        ServerFile file = read("Server (dev.stxt.press):");

        assertEquals(config.resolve("server.stxt"), file.file());
        assertEquals(ServerConfig.DEFAULT, file.http());
        assertEquals(config.resolve("resources").toAbsolutePath().normalize(), file.resources().root());
        assertEquals(Limits.DEFAULT, file.limits());
        assertNull(file.templateFolder());
    }

    // ---- The values

    @Test
    void everyValueIsRead() throws Exception {
        Files.createDirectories(config.resolve("res"));
        Files.createDirectories(config.resolve("templates"));
        ServerFile file = read("""
                Server (dev.stxt.press):
                    Host: 0.0.0.0
                    Port: 9090
                    Token env: PRESS_TOKEN
                    Lazy init: true
                    Preload resources: true
                    Resource folder: res
                    Template folder: templates
                    Routes:
                        Editor: false
                        Render: false
                        Templates: false
                    Limits:
                        Max body size: 1000
                        Timeout: 5
                        Concurrent conversions: 2
                        Max pages: 10
                        Max image pixels: 11
                        Max SVG elements: 12
                        Max SVG depth: 13
                        Max PDF bytes: 14
                    Template options:
                        PDF UA: 1
                        PDF A: A3B
                        Exclude: CONTRAST
                        Exclude: BARCODE_SIZE
                """, Map.of("PRESS_TOKEN", " secret "));

        assertEquals(new ServerConfig("0.0.0.0", 9090, "secret", false, false, false, 1000, Duration.ofSeconds(5), 2,
                new Options(1, PdfA.A3B, false, Set.of(Check.CONTRAST, Check.BARCODE_SIZE))), file.http());
        assertEquals(new Limits(10, 11, 12, 13, 14), file.limits());
        assertTrue(file.lazyInit());
        assertTrue(file.preloadResources());
        assertEquals(config.resolve("res").toAbsolutePath().normalize(), file.resources().root());
        assertEquals(config.resolve("templates").toAbsolutePath().normalize(), file.templateFolder());
    }

    @Test
    void relativePathsAreOfTheFolderOfTheFileAndNotOfTheWorkingDirectory() throws Exception {
        Files.createDirectories(config.resolve("fonts"));
        Files.createDirectories(config.resolve("shared/templates"));
        Files.createDirectories(work.resolve("fonts"));
        Path file = write(config.resolve("server.stxt"), """
                Server (dev.stxt.press):
                    Font folder: fonts
                    Template folder: shared/../shared/templates
                """);

        ServerFile read = ServerFile.load(file.toString(), Map.of(), work);

        assertEquals(config.resolve("fonts").toAbsolutePath().normalize(), read.resources().fontsFolder());
        assertEquals(config.resolve("shared/templates").toAbsolutePath().normalize(), read.templateFolder());
    }

    @Test
    void imageFoldersGoWithAFontFolderEachWithItsPrefix() throws Exception {
        Files.createDirectories(config.resolve("fonts"));
        Files.createDirectories(config.resolve("images"));
        Files.createDirectories(config.resolve("logos"));
        Files.writeString(config.resolve("images/a.svg"), "<svg/>");
        Files.writeString(config.resolve("logos/b.svg"), "<svg/>");
        ServerFile file = read("""
                Server (dev.stxt.press):
                    Font folder: fonts
                    Image folder: images
                    Image folder: logos
                        Prefix: logos
                """);

        assertEquals(config.resolve("fonts").toAbsolutePath().normalize(), file.resources().fontsFolder());
        assertEquals(new TreeSet<>(Set.of("a.svg", "logos/b.svg")), new TreeSet<>(file.resources().imagePaths()));
    }

    @Test
    void theSchemaHasEveryCheckAndEveryPdfAOfTheEngine() throws IOException {
        String schema;
        try (InputStream in = ServerFile.class.getResourceAsStream("/stxt/dev.stxt.press.stxt")) {
            schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        InlineNode root = (InlineNode) new Parser().parse(schema).get(0);

        assertEquals(names(Check.values()), values(root, "exclude"));
        assertEquals(names(PdfA.values()), values(root, "pdf-a"));
    }

    // ---- The errors, with their line

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            node that does not exist         | NODE_NOT_DEFINED_IN_SCHEMA | 2 | '    Colour: red'
            node in a group not its own      | CHILD_NOT_DECLARED       | 3 | '    Routes:\\n        Port: 1'
            value of a boolean               | INVALID_VALUE            | 2 | '    Lazy init: yes'
            two ports                        | TOO_MANY_CHILDREN        | 1 | '    Port: 1\\n    Port: 2'
            port too large                   | CONFIG_VALUE_NOT_VALID   | 2 | '    Port: 65536'
            number too large for a long      | CONFIG_VALUE_NOT_VALID   | 3 | '    Limits:\\n        Timeout: 99999999999999999999'
            limit of 0                       | CONFIG_VALUE_NOT_VALID   | 3 | '    Limits:\\n        Max pages: 0'
            empty host                       | CONFIG_VALUE_NOT_VALID   | 2 | '    Host:'
            PDF UA that does not exist       | INVALID_VALUE            | 3 | '    Template options:\\n        PDF UA: 3'
            PDF UA 2 with PDF A              | CONFIG_CONFLICT          | 4 | '    Template options:\\n        PDF UA: 2\\n        PDF A: A2B'
            repeated Exclude                 | CONFIG_VALUE_NOT_VALID   | 4 | '    Template options:\\n        Exclude: CONTRAST\\n        Exclude: CONTRAST'
            folder that does not exist       | CONFIG_FOLDER_NOT_FOUND  | 2 | '    Template folder: missing'
            file instead of a folder         | CONFIG_FOLDER_NOT_FOUND  | 2 | '    Resource folder: server.stxt'
            resource folder with font folder | CONFIG_CONFLICT          | 3 | '    Resource folder: .\\n    Font folder: .'
            image folder without font folder | CONFIG_CONFLICT          | 2 | '    Image folder: .'
            two image folders without prefix | CONFIG_VALUE_NOT_VALID   | 4 | '    Font folder: .\\n    Image folder: .\\n    Image folder: .'
            prefix that is not valid         | CONFIG_VALUE_NOT_VALID   | 4 | '    Font folder: .\\n    Image folder: .\\n        Prefix: ../up'
            token variable not set           | CONFIG_TOKEN_NOT_SET     | 2 | '    Token env: NOT_SET'
            token variable empty             | CONFIG_TOKEN_NOT_SET     | 2 | '    Token env: EMPTY'
            token variable that is no name   | CONFIG_VALUE_NOT_VALID   | 2 | '    Token env: 1-TOKEN'
            text in block form               | BLOCK_FORM_NOT_ALLOWED   | 2 | '    Host >>\\n        0.0.0.0'
            bad indentation                  | INDENTATION_LEVEL_NOT_VALID | 2 | '        Port: 1'
            """)
    void aFileThatIsNotValidStopsTheStartWithItsLine(String name, String code, int line, String children) {
        String text = "Server (dev.stxt.press):\n" + children.replace("\\n", "\n") + "\n";

        ConfigException e = assertThrows(ConfigException.class, () -> read(text, Map.of("EMPTY", " ")));

        assertEquals(code, e.code(), e.getMessage());
        assertEquals(line, e.line(), e.getMessage());
        assertTrue(e.getMessage().startsWith(config.resolve("server.stxt") + ":" + line + ": "), e.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            empty file             | 1 | ''
            root without namespace | 1 | 'Server:'
            other root             | 1 | 'Port (dev.stxt.press): 8080'
            two roots              | 2 | 'Server (dev.stxt.press):\\nServer (dev.stxt.press):'
            """)
    void theFileHasOneRootServer(String name, int line, String text) {
        ConfigException e = assertThrows(ConfigException.class, () -> read(text.replace("\\n", "\n")));

        assertEquals(ConfigException.ROOT, e.code(), e.getMessage());
        assertEquals(line, e.line(), e.getMessage());
    }

    @Test
    void theTokenIsNotInTheMessages() throws Exception {
        ServerFile file = read("Server (dev.stxt.press):\n    Token env: PRESS_TOKEN\n",
                Map.of("PRESS_TOKEN", "the-secret"));

        assertFalse(file.toString().contains("the-secret"), file.toString());
    }

    @Test
    void aFileThatIsNotUtf8CannotBeRead() throws IOException {
        Files.write(config.resolve("server.stxt"), new byte[] {'S', (byte) 0xff});

        assertThrows(IOException.class, () -> ServerFile.read(config.resolve("server.stxt"), Map.of()));
    }

    // ---- The server it starts

    @Test
    void withTokenEveryRouteButHealthAsksForIt() throws Exception {
        ServerFile file = read(server("    Token env: PRESS_TOKEN\n"), Map.of("PRESS_TOKEN", "secret"));

        try (PressServer server = file.start()) {
            assertEquals(200, get(server, "/health", null).statusCode());
            assertEquals(401, get(server, "/schema", null).statusCode());
            assertEquals(401, get(server, "/schema", "Bearer wrong").statusCode());
            assertEquals(200, get(server, "/schema", "Bearer secret").statusCode());
        }
    }

    @Test
    void withoutTokenNothingIsAskedFor() throws Exception {
        try (PressServer server = read(server("")).start()) {
            assertEquals(200, get(server, "/schema", null).statusCode());
        }
    }

    @Test
    void eachRouteIsSwitchedOffAlone() throws Exception {
        Files.createDirectories(config.resolve("templates"));
        String folder = "    Template folder: templates\n";
        try (PressServer on = read(server(folder)).start();
                PressServer noRender = read(server(folder + "    Routes:\n        Render: false\n")).start();
                PressServer noTemplates = read(server(folder + "    Routes:\n        Templates: false\n")).start();
                PressServer noEditor = read(server(folder + "    Routes:\n        Editor: false\n")).start()) {
            assertEquals(200, get(on, "/", null).statusCode());
            assertEquals(405, get(on, "/render", null).statusCode());
            assertEquals(200, get(on, "/templates", null).statusCode());
            assertEquals(404, get(noEditor, "/", null).statusCode());
            assertEquals(405, get(noEditor, "/render", null).statusCode());
            assertEquals(404, get(noRender, "/render", null).statusCode());
            assertEquals(200, get(noRender, "/templates", null).statusCode());
            assertEquals(405, get(noTemplates, "/render", null).statusCode());
            assertEquals(404, get(noTemplates, "/templates", null).statusCode());
        }
    }

    @Test
    void theTemplatesOfTheFileArePrinted() throws Exception {
        Files.createDirectories(config.resolve("templates"));
        Files.writeString(config.resolve("templates/hello.stxt"), """
                Document (dev.stxt.pdf):
                    Page:
                        Paper: A6
                    Title: Hello
                    Language: en
                    Styles:
                        For element: Document
                            Typography:
                                Font: IBM Plex Sans
                                Size: 11
                    Section:
                        Text: Hello
                """);

        try (PressServer server = read(server("    Template folder: templates\n")).start()) {
            HttpResponse<byte[]> pdf = get(server, "/print/hello", null);

            assertEquals(200, pdf.statusCode(), () -> new String(pdf.body(), StandardCharsets.UTF_8));
            assertEquals("%PDF", new String(pdf.body(), 0, 4, StandardCharsets.US_ASCII));
        }
    }

    @Test
    void aLazyServerStartsWithResourcesThatFailAndTheFirstRequestGetsTheError() throws Exception {
        Files.createDirectories(config.resolve("fonts"));
        Files.writeString(config.resolve("fonts/broken.ttf"), "not a font");
        String broken = "    Preload resources: true\n    Font folder: fonts\n";

        String eager = "Server (dev.stxt.press):\n    Port: 0\n" + broken;

        assertThrows(StxtPdfException.class, () -> read(eager).start());

        try (PressServer server = read(eager + "    Lazy init: true\n").start()) {
            assertEquals(200, get(server, "/health", null).statusCode());
            HttpResponse<byte[]> fonts = get(server, "/fonts", null);
            assertEquals(500, fonts.statusCode());
            assertTrue(new String(fonts.body(), StandardCharsets.UTF_8).contains("\"kind\":\"RESOURCE\""),
                    () -> new String(fonts.body(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void aLazyServerCreatesTheConverterWithTheFirstRequest() throws Exception {
        try (PressServer server = read(server("    Lazy init: true\n    Preload resources: true\n")).start()) {
            HttpResponse<byte[]> fonts = get(server, "/fonts", null);

            assertEquals(200, fonts.statusCode());
            assertTrue(new String(fonts.body(), StandardCharsets.UTF_8).contains("IBM Plex Sans"));
        }
    }

    // ---- Helpers

    /** {@return a server on any free port of 127.0.0.1, with the fonts of the engine and {@code more}} */
    private static String server(String more) {
        return "Server (dev.stxt.press):\n    Port: 0\n    Resource folder: " + FONTS + "\n" + more;
    }

    private ServerFile read(String text) throws Exception {
        return read(text, Map.of());
    }

    private ServerFile read(String text, Map<String, String> env) throws Exception {
        return ServerFile.read(write(config.resolve("server.stxt"), text), env);
    }

    private static Path write(Path file, String text) throws IOException {
        return Files.writeString(file, text);
    }

    private static HttpResponse<byte[]> get(PressServer server, String path, String authorization)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + server.address().getPort() + path));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient().send(request.build(), BodyHandlers.ofByteArray());
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
    }

    /** {@return the values of the ENUM node {@code name} of the schema} */
    private static Set<String> values(InlineNode schema, String name) {
        for (Node node : schema.getChildren("Node")) {
            if (node.getText().equals(name) || canonical(node.getText()).equals(name)) {
                InlineNode values = (InlineNode) ((InlineNode) node).getChild("Values");
                return values.getChildren("Value").stream().map(Node::getText)
                        .collect(Collectors.toCollection(TreeSet::new));
            }
        }
        throw new AssertionError("No node " + name + " in the schema");
    }

    private static String canonical(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
    }
}
