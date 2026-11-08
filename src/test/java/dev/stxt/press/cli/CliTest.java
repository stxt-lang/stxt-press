package dev.stxt.press.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.stxt.press.http.PressServer;

/**
 * The command line (ROADMAP-PDF 2.8), on {@link Cli} with its streams in memory: each command, where its result goes,
 * the errors of a job with their file and line, and the exit codes.
 */
class CliTest {

    private static final Path RESOURCES = Path.of("..", "stxt-pdf", "resources").toAbsolutePath().normalize();
    private static final Path LETTER = Path.of("..", "stxt-pdf", "test", "examples", "02-letter").toAbsolutePath()
            .normalize();
    private static final Path TEMPLATES = Path.of("templates").toAbsolutePath();

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private byte[] stdin = new byte[0];

    @BeforeEach
    void config() throws IOException {
        // The server.stxt of the working directory: the fonts of the engine.
        Files.writeString(dir.resolve("server.stxt"), "Server (dev.stxt.press):\n    Resource folder: " + RESOURCES
                + "\n");
    }

    private int run(String... args) {
        return cli().run(args);
    }

    private Cli cli() {
        return new Cli(new ByteArrayInputStream(stdin), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of(), dir);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    // ---- render

    @Test
    void renderWritesThePdfOfTheEngine() throws Exception {
        int code = run("render", LETTER.resolve("en/input.stxt").toString(), "--styles",
                LETTER.resolve("styles.stxt").toString(), "-o", "letter.pdf");

        assertEquals(Cli.OK, code, this::err);
        assertArrayEquals(Files.readAllBytes(LETTER.resolve("en/output.pdf")),
                Files.readAllBytes(dir.resolve("letter.pdf")));
        assertEquals("", out());
    }

    @Test
    void renderReadsStdinAndWritesStdout() throws Exception {
        stdin = Files.readAllBytes(LETTER.resolve("en/input.stxt"));

        int code = run("render", "-", "--styles", LETTER.resolve("styles.stxt").toString(), "--output", "-");

        assertEquals(Cli.OK, code, this::err);
        assertArrayEquals(Files.readAllBytes(LETTER.resolve("en/output.pdf")), out.toByteArray());
    }

    @Test
    void anErrorOfTheDocumentSaysItsFileAndLineAndLeavesTheOutputAsItWas() throws Exception {
        Files.writeString(dir.resolve("broken.stxt"), "Document (dev.stxt.pdf):\n    Title: T\n    Colour: red\n");
        Files.writeString(dir.resolve("out.pdf"), "before");

        int code = run("render", "broken.stxt", "-o", "out.pdf");

        assertEquals(Cli.FAILED, code);
        assertTrue(err().startsWith("stxt-press: broken.stxt:3: "), err());
        assertEquals("before", Files.readString(dir.resolve("out.pdf")));
        try (var files = Files.list(dir)) {
            assertEquals(List.of("broken.stxt", "out.pdf", "server.stxt"),
                    files.map(f -> f.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void anErrorOfAStylesDocumentSaysWhichFile() throws Exception {
        Files.writeString(dir.resolve("bad.styles.stxt"), "Styles (dev.stxt.pdf):\n    For element: Nothing\n");

        int code = run("render", LETTER.resolve("en/input.stxt").toString(), "--styles",
                LETTER.resolve("styles.stxt").toString(), "--styles", "bad.styles.stxt", "-o", "x.pdf");

        assertEquals(Cli.FAILED, code);
        assertTrue(err().startsWith("stxt-press: bad.styles.stxt:2: "), err());
    }

    // ---- print

    @Test
    void printConvertsATemplateWithItsSampleData() throws Exception {
        int code = run("print", "invoices/invoice", "--templates", TEMPLATES.toString(), "-o", "invoice.pdf");

        assertEquals(Cli.OK, code, this::err);
        assertTrue(new String(Files.readAllBytes(dir.resolve("invoice.pdf")), 0, 5, StandardCharsets.ISO_8859_1)
                .equals("%PDF-"));
    }

    @Test
    void printTakesTheTemplatesFolderOfServerStxt() throws Exception {
        Files.writeString(dir.resolve("press.stxt"), "Server (dev.stxt.press):\n    Resource folder: " + RESOURCES
                + "\n    Template folder: " + TEMPLATES + "\n");

        assertEquals(Cli.OK, run("print", "invoices/invoice", "--config", "press.stxt", "-o", "-"), this::err);
        assertTrue(out().startsWith("%PDF-"));
    }

    @Test
    void anErrorOfTheDataOfATemplateIsOfTheLineOfItsSlot() throws Exception {
        Files.writeString(dir.resolve("data.json"), "{\"number\": \"1\"}");

        int code = run("print", "invoices/invoice", "--templates", TEMPLATES.toString(), "--data", "data.json", "-o",
                "x.pdf");

        assertEquals(Cli.FAILED, code);
        assertTrue(err().startsWith("stxt-press: " + TEMPLATES.resolve("invoices/invoice.stxt") + ":"), err());
        assertTrue(err().contains("SLOT_NO_DATA"), err());
    }

    @Test
    void printWithoutTemplatesFolderOrWithATemplateThatDoesNotExist() {
        assertEquals(Cli.USAGE, run("print", "invoices/invoice", "-o", "x.pdf"));
        assertTrue(err().contains("No templates folder"), err());
        assertEquals(Cli.USAGE, run("print", "nothing", "--templates", TEMPLATES.toString(), "-o", "x.pdf"));
        assertTrue(err().contains("TEMPLATE_NOT_FOUND"), err());
    }

    // ---- fill

    @Test
    void fillWritesTheFilledDocumentToStdout() {
        int code = run("fill", TEMPLATES.resolve("invoices/invoice.stxt").toString(), "--data",
                TEMPLATES.resolve("invoices/invoice.json").toString());

        assertEquals(Cli.OK, code, this::err);
        assertTrue(out().contains("Title: Invoice 2025-0147"), out());
        assertTrue(out().contains("Hotel Mirador del Valle"), out());
    }

    @Test
    void fillWithDataThatAreNotJsonIsAnErrorOfTheirLine() throws Exception {
        Files.writeString(dir.resolve("data.json"), "{\n  \"a\": \n}");

        int code = run("fill", TEMPLATES.resolve("invoices/invoice.stxt").toString(), "--data", "data.json");

        assertEquals(Cli.FAILED, code);
        assertTrue(err().startsWith("stxt-press: data.json:3: JSON_NOT_VALID: "), err());
    }

    // ---- serve

    @Test
    void serveStartsTheServerOfServerStxt() throws Exception {
        Files.writeString(dir.resolve("server.stxt"), "Server (dev.stxt.press):\n    Port: 0\n    Resource folder: "
                + RESOURCES + "\n");

        try (PressServer server = cli().serve(List.of())) {
            int status = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + server.address().getPort() + "/health")).build(), BodyHandlers.discarding()).statusCode();

            assertEquals(200, status);
            assertTrue(out().startsWith("stxt-press listening on http://127.0.0.1:"), out());
        }
    }

    @Test
    void aServerStxtThatIsNotValidIsAnErrorOfTheInvocation() throws Exception {
        Files.writeString(dir.resolve("server.stxt"), "Server (dev.stxt.press):\n    Port: 70000\n");

        assertEquals(Cli.USAGE, run("serve"));
        assertTrue(err().startsWith("stxt-press: CONFIG_VALUE_NOT_VALID: "), err());
        assertTrue(err().contains("server.stxt:2: "), err());
    }

    // ---- The invocation

    @Test
    void helpAndVersion() {
        assertEquals(Cli.OK, run("--help"));
        assertTrue(out().startsWith("Usage: java -jar stxt-press.jar"));
        assertEquals(Cli.OK, run("-v"));
        assertTrue(out().contains("stxt-press "), out());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            no command                 | ''
            unknown command            | pdf
            render without document    | render -o x.pdf
            render without output      | render a.stxt
            two documents              | render a.stxt b.stxt -o x.pdf
            unknown option             | render a.stxt -o x.pdf --bogus
            short unknown option       | render a.stxt -o x.pdf -x
            option without value       | render a.stxt -o
            repeated option            | render a.stxt -o x.pdf -o y.pdf
            file that does not exist   | render missing.stxt -o x.pdf
            PDF/UA 3                   | render a.stxt -o x.pdf --pdf-ua 3
            unknown PDF/A              | render a.stxt -o x.pdf --pdf-a A1B
            PDF/UA 2 with a PDF/A      | render a.stxt -o x.pdf --pdf-ua 2 --pdf-a A2B
            unknown check              | render a.stxt -o x.pdf --exclude SPELLING
            stdin twice                | render - --styles - -o x.pdf
            output folder missing      | render a.stxt -o nowhere/x.pdf
            fill without data          | fill a.stxt
            serve with an argument     | serve now
            config that does not exist | serve --config missing.stxt
            """)
    void anInvocationThatIsNotValidIsExit2(String name, String line) throws IOException {
        Files.writeString(dir.resolve("a.stxt"), "Document (dev.stxt.pdf):\n");
        Files.writeString(dir.resolve("b.stxt"), "Document (dev.stxt.pdf):\n");

        int code = run(line.isEmpty() ? new String[0] : line.split(" "));

        assertEquals(Cli.USAGE, code, this::err);
        assertTrue(err().startsWith("stxt-press: "), err());
        assertEquals("", out());
    }
}
