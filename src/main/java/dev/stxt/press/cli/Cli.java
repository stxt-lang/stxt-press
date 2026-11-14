package dev.stxt.press.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import dev.stxt.pdf.model.Check;
import dev.stxt.pdf.model.PdfA;
import dev.stxt.pdf.model.StxtPdfException;
import dev.stxt.press.Job;
import dev.stxt.press.Options;
import dev.stxt.press.Press;
import dev.stxt.press.PressException;
import dev.stxt.press.config.ConfigException;
import dev.stxt.press.config.ServerFile;
import dev.stxt.press.http.PressServer;
import dev.stxt.press.templates.AtomicFile;
import dev.stxt.press.templates.Template;
import dev.stxt.press.templates.TemplateException;
import dev.stxt.press.templates.Templates;
import dev.stxt.utils.FileUtils;

/**
 * The command line of the service (ROADMAP-PDF 2.8): {@code start}, {@code render}, {@code print}, {@code documents}
 * and {@code fill}, with the names of the HTTP routes. It is the face of the service on the command line, not a CLI of the ecosystem:
 * {@code stxt-cli} has nothing of PDF.
 *
 * <p>Exit codes, those of {@code stxt-cli}: {@link #OK}, {@link #FAILED} if the job failed (an error of the document,
 * its styles or its data, a resource, a limit) and {@link #USAGE} if the invocation is not valid (an option, a file
 * that does not exist, a {@code server.stxt} that is not valid). The result goes to stdout or to {@code --output};
 * the messages, to stderr.</p>
 */
final class Cli {

    static final int OK = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;

    static final String HELP = """
            Usage: java -jar stxt-press.jar <command> [options]

            Commands:
              start                      Starts the HTTP service
              render <document>          Converts a layout document to PDF
              print <template>           Converts a template of the templates folder to PDF
              documents <document>       Converts a document of the documents folder to PDF
              fill <document>            Fills the Slots of a document with JSON data

            Options of start:
              --config <file>            The server.stxt (by default STXT_PRESS_CONFIG, or ./server.stxt)

            Options of render:
              -o, --output <file>        Where the PDF is written; - for stdout (required)
              --styles <file>            A separate styles document; one --styles for each, in order
              --data <file>              The JSON data that fill the Slots of the document
              --pdf-ua <1|2>             The part of PDF/UA (1 by default)
              --pdf-a <A2B|A2A|A3B|A3A>  The PDF/A the PDF complies with besides
              --exclude <check>          A check that is not made: CONTRAST, PDF_IMAGE_FONTS,
                                         PDF_IMAGE_ANNOTATIONS, BARCODE_SIZE; one --exclude for each
              --debug                    Paints the outlines of the boxes
              --config <file>            The server.stxt with the resources folder and the limits

            Options of print:
              -o, --output <file>        Where the PDF is written; - for stdout (required)
              --data <file>              The JSON data, instead of the sample data of the template
              --templates <folder>       The templates folder, instead of the Template folder of server.stxt
              --debug                    Paints the outlines of the boxes
              --config <file>            The server.stxt with the folders, the limits and the template options

            Options of documents:
              -o, --output <file>        Where the PDF is written; - for stdout (required)
              --documents <folder>       The documents folder, instead of the Document folder of server.stxt
              --debug                    Paints the outlines of the boxes
              --config <file>            The server.stxt with the folders, the limits and the template options

            Options of fill:
              --data <file>              The JSON data (required)
              -o, --output <file>        Where the filled document is written (stdout by default)

            A <document> or a <file> of --data or --styles can be - for stdin.

              -h, --help                 Shows this help
              -v, --version              Shows the version
            """;

    private final InputStream in;
    private final PrintStream out;
    private final PrintStream err;
    private final Map<String, String> env;
    private final Path workingDirectory;
    private boolean stdinRead;

    Cli(InputStream in, PrintStream out, PrintStream err, Map<String, String> env, Path workingDirectory) {
        this.in = in;
        this.out = out;
        this.err = err;
        this.env = env;
        this.workingDirectory = workingDirectory;
    }

    /** Runs a command and {@return its exit code}; {@code start} returns only when the process is stopped. */
    int run(String... args) {
        List<String> rest = List.of(args).subList(Math.min(1, args.length), args.length);
        String command = args.length == 0 ? "" : args[0];
        try {
            switch (command) {
                case "-h", "--help" -> out.print(HELP);
                case "-v", "--version" -> out.println("stxt-press " + version());
                case "start" -> startUntilStopped(rest);
                case "render" -> render(rest);
                case "print" -> print(rest, Templates.Kind.TEMPLATE);
                case "documents" -> print(rest, Templates.Kind.DOCUMENT);
                case "fill" -> fill(rest);
                default -> throw new UsageException(command.isEmpty() ? "A command is missing"
                        : "Unknown command " + command);
            }
            return OK;
        } catch (UsageException e) {
            err.println("stxt-press: " + e.getMessage());
            err.println("Run java -jar stxt-press.jar --help for the commands and their options.");
            return USAGE;
        } catch (ConfigException e) {
            err.println("stxt-press: " + e.code() + ": " + e.getMessage());
            return USAGE;
        } catch (Failure e) {
            err.println("stxt-press: " + e.getMessage());
            return FAILED;
        }
    }

    // ---- The commands

    /** {@code start}: starts the server of the configuration and waits until the process is stopped. */
    private void startUntilStopped(List<String> args) throws UsageException, ConfigException, Failure {
        ServerFile config = startConfig(args);
        // Before the first server of the JVM, which is when the JDK reads them: a slow client cannot keep a thread.
        PressServer.limitTimes(config.http().timeout());
        PressServer server = startServer(config);
        // SIGTERM (docker stop) and Ctrl+C: the conversions in progress have the time of a conversion to finish, and
        // without any it stops at once.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close(config.http().timeout());
            out.println("stxt-press stopped");
            out.flush();
        }, "stxt-press-shutdown"));
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * {@code start} without waiting: {@return the server, already answering} Without the time limits of
     * {@link #startUntilStopped}, which are of the JVM: for the tests.
     */
    PressServer start(List<String> args) throws UsageException, ConfigException, Failure {
        return startServer(startConfig(args));
    }

    /** {@return the configuration of {@code start}: its only option is {@code --config}} */
    private ServerFile startConfig(List<String> args) throws UsageException, ConfigException {
        return config(Arguments.parse(args, Set.of(), Set.of("--config"), Set.of()).none());
    }

    private PressServer startServer(ServerFile config) throws Failure {
        PressServer server;
        try {
            server = config.start();
        } catch (IOException | StxtPdfException e) {
            throw new Failure("The server cannot start: " + e.getMessage());
        }
        out.println("stxt-press listening on http://" + config.http().host() + ":" + server.address().getPort()
                + "/" + (config.file() == null ? " (without server.stxt)" : " (" + config.file() + ")"));
        out.flush();
        return server;
    }

    /** {@code render}: a layout document, its styles and its data, to PDF. */
    private void render(List<String> args) throws UsageException, ConfigException, Failure {
        Arguments a = Arguments.parse(args, Set.of("--debug"),
                Set.of("--output", "--data", "--config", "--pdf-ua", "--pdf-a"), Set.of("--styles", "--exclude"));
        String document = a.one("<document>");
        String output = a.required("--output");
        Options options;
        try {
            options = Options.DEFAULT.withPdfUa(pdfUa(a.value("--pdf-ua"))).withPdfA(pdfA(a.value("--pdf-a")))
                    .withExcluded(excluded(a.values("--exclude"))).withDebug(a.flag("--debug"));
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
        ServerFile config = config(a.value("--config"));

        Map<String, String> files = new LinkedHashMap<>();
        files.put(PressException.DOCUMENT, document);
        List<Job.Styles> styles = new ArrayList<>();
        for (String path : a.values("--styles")) {
            // Named by their path, so that an error of one says which file it is.
            styles.add(new Job.Styles(path, read(path)));
            files.put(path, path);
        }
        String data = a.value("--data");
        if (data != null) {
            files.put(PressException.DATA, data);
        }
        Job job = new Job(read(document), styles, data == null ? null : read(data), options);
        writePdf(new Press(config.converter()), job, output, files);
    }

    /**
     * {@code print}: a template of the templates folder, with its sample data or those of {@code --data}, to PDF; and
     * {@code documents}: a document of the documents folder, as it is, without data. Both with the chain of styles of
     * their folder and the template options of {@code server.stxt}, as {@code /print/{path}} and
     * {@code /documents/{path}}.
     */
    private void print(List<String> args, Templates.Kind kind) throws UsageException, ConfigException, Failure {
        boolean template = kind == Templates.Kind.TEMPLATE;
        String folderOption = template ? "--templates" : "--documents";
        Arguments a = Arguments.parse(args, Set.of("--debug"), template
                ? Set.of("--output", "--data", "--config", folderOption)
                : Set.of("--output", "--config", folderOption), Set.of());
        String path = a.one("<" + kind.noun() + ">");
        String output = a.required("--output");
        ServerFile config = config(a.value("--config"));
        Path folder = a.value(folderOption) != null ? resolve(a.value(folderOption))
                : template ? config.templateFolder() : config.documentFolder();
        if (folder == null) {
            throw new UsageException("No " + kind.noun() + "s folder: " + folderOption + ", or "
                    + (template ? "Template" : "Document") + " folder in server.stxt");
        }
        Templates templates;
        try {
            templates = new Templates(folder, kind);
        } catch (IOException e) {
            throw new UsageException("The " + kind.noun() + "s folder cannot be read: " + e.getMessage());
        }
        Template entry;
        try {
            entry = templates.get(path);
        } catch (TemplateException e) {
            throw new UsageException(e.code() + ": " + e.getMessage());
        } catch (IOException e) {
            // A file of the template or the document that cannot be read or is not UTF-8: the job failed, as with
            // render. The message says which file.
            throw new Failure(e.getMessage());
        }
        Options options = config.http().templateOptions().withDebug(a.flag("--debug"));
        String data = template ? a.value("--data") : null;
        Job job = data == null ? entry.job(options) : entry.job(options, read(data));

        Map<String, String> files = new LinkedHashMap<>();
        templates.files(entry).forEach((source, file) -> files.put(source, file.toString()));
        if (data != null) {
            files.put(PressException.DATA, data);
        }
        writePdf(new Press(config.converter()), job, output, files);
    }

    /** {@code fill}: the document with its Slots filled, as STXT text. */
    private void fill(List<String> args) throws UsageException, Failure {
        Arguments a = Arguments.parse(args, Set.of(), Set.of("--output", "--data"), Set.of());
        String document = a.one("<document>");
        String data = a.required("--data");
        String text = read(document);
        String json = read(data);
        String filled;
        try {
            filled = Press.fill(text, json);
        } catch (PressException e) {
            throw failure(e, Map.of(PressException.DOCUMENT, document, PressException.DATA, data));
        }
        byte[] bytes = filled.getBytes(StandardCharsets.UTF_8);
        write(a.value("--output") == null ? "-" : a.value("--output"), target -> target.write(bytes));
    }

    // ---- Reading and writing

    private ServerFile config(String option) throws ConfigException, UsageException {
        try {
            return ServerFile.load(option, env, workingDirectory);
        } catch (ConfigException e) {
            throw e;
        } catch (IOException e) {
            throw new UsageException("The configuration cannot be read: " + e.getMessage());
        }
    }

    private void writePdf(Press press, Job job, String output, Map<String, String> files)
            throws UsageException, Failure {
        try {
            write(output, target -> press.pdf(job, target));
        } catch (PressException e) {
            throw failure(e, files);
        }
    }

    /** {@return the text of {@code path}, or of stdin with {@code -}: strict UTF-8, without a BOM} */
    private String read(String path) throws UsageException, Failure {
        if (path.equals("-")) {
            if (stdinRead) {
                throw new UsageException("Only one input can be - (stdin)");
            }
            stdinRead = true;
            try {
                return utf8(in.readAllBytes(), "stdin");
            } catch (IOException e) {
                throw new Failure("stdin cannot be read: " + e.getMessage());
            }
        }
        Path file = resolve(path);
        if (!Files.isRegularFile(file)) {
            throw new UsageException("There is no file " + path);
        }
        try {
            return FileUtils.readFileContent(file.toFile());
        } catch (IOException e) {
            throw new Failure(path + " cannot be read, or is not UTF-8: " + e.getMessage());
        }
    }

    private static String utf8(byte[] bytes, String name) throws Failure {
        try {
            return FileUtils.removeUTF8BOM(Press.text(bytes));
        } catch (CharacterCodingException e) {
            throw new Failure(name + " is not UTF-8 text");
        }
    }

    /**
     * Writes the result to {@code output}, or to stdout with {@code -}. A file is written whole or not at all
     * ({@link AtomicFile}), so that a job that fails leaves the file that was there, if any, as it was.
     */
    private void write(String output, AtomicFile.Content content) throws UsageException, Failure {
        if (output.equals("-")) {
            try {
                content.writeTo(out);
                out.flush();
            } catch (IOException e) {
                throw new Failure("stdout cannot be written: " + e.getMessage());
            }
            return;
        }
        Path target = resolve(output).toAbsolutePath();
        if (!Files.isDirectory(target.getParent()) || Files.isDirectory(target)) {
            throw new UsageException("The output " + output + " cannot be written: "
                    + (Files.isDirectory(target) ? "it is a folder" : "its folder does not exist"));
        }
        try {
            AtomicFile.write(target, content);
        } catch (IOException e) {
            throw new Failure(output + " cannot be written: " + e.getMessage());
        }
    }

    private Path resolve(String path) throws UsageException {
        try {
            return workingDirectory.resolve(path);
        } catch (InvalidPathException e) {
            throw new UsageException("Not a path: " + path);
        }
    }

    // ---- The errors

    /**
     * {@return the error of a job as {@code file:line: CODE: message}} {@code files} gives the file of each
     * {@code source}; an error of the filled document has the line of the filled text, which {@code fill} shows.
     */
    private static Failure failure(PressException e, Map<String, String> files) {
        String hint = PressException.FILLED.equals(e.source())
                ? System.lineSeparator() + "  (stxt-press fill writes the filled document)" : "";
        return new Failure(e.describe(files) + hint);
    }

    private static int pdfUa(String value) throws UsageException {
        if (value == null) {
            return Options.DEFAULT.pdfUa();
        }
        return switch (value) {
            case "1" -> 1;
            case "2" -> 2;
            default -> throw new UsageException("--pdf-ua is 1 or 2, not " + value);
        };
    }

    private static PdfA pdfA(String value) throws UsageException {
        return value == null ? null : constant(PdfA.class, value, "--pdf-a");
    }

    private static Set<Check> excluded(List<String> values) throws UsageException {
        Set<Check> checks = EnumSet.noneOf(Check.class);
        for (String value : values) {
            checks.add(constant(Check.class, value, "--exclude"));
        }
        return checks;
    }

    private static <E extends Enum<E>> E constant(Class<E> type, String value, String option) throws UsageException {
        E constant = Options.named(type, value);
        if (constant == null) {
            throw new UsageException(option + " is one of " + Options.names(type) + ", not " + value);
        }
        return constant;
    }

    private static String version() {
        String version = Press.version();
        return version == null ? "(development)" : version;
    }

    /** An invocation that is not valid: exit code {@link #USAGE}. */
    static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    /** A job that failed: exit code {@link #FAILED}. */
    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;

        Failure(String message) {
            super(message);
        }
    }

    /** The options and the arguments of a command. */
    private static final class Arguments {

        private final List<String> positional = new ArrayList<>();
        private final Map<String, List<String>> values = new LinkedHashMap<>();

        /**
         * @param flags the options without value
         * @param single the options with one value, given at most once
         * @param multiple the options with a value, given any number of times
         */
        static Arguments parse(List<String> args, Set<String> flags, Set<String> single, Set<String> multiple)
                throws UsageException {
            Arguments a = new Arguments();
            for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i);
                if (arg.equals("-") || !arg.startsWith("-")) {
                    a.positional.add(arg);
                    continue;
                }
                String name = arg.equals("-o") ? "--output" : arg;
                if (flags.contains(name)) {
                    a.add(name, "true", false);
                } else if (single.contains(name) || multiple.contains(name)) {
                    if (i + 1 == args.size()) {
                        throw new UsageException(arg + " needs a value");
                    }
                    a.add(name, args.get(++i), multiple.contains(name));
                } else {
                    throw new UsageException("Unknown option " + arg);
                }
            }
            return a;
        }

        private void add(String name, String value, boolean repeatable) throws UsageException {
            List<String> list = values.computeIfAbsent(name, n -> new ArrayList<>());
            if (!repeatable && !list.isEmpty()) {
                throw new UsageException("Repeated option " + name);
            }
            list.add(value);
        }

        /** {@return the only argument, {@code name} in the messages} */
        String one(String name) throws UsageException {
            if (positional.size() != 1) {
                throw new UsageException(positional.isEmpty() ? name + " is missing"
                        : "One " + name + " only, and there are " + positional.size());
            }
            return positional.get(0);
        }

        /** {@return the value of {@code --config}}, when the command has no arguments */
        String none() throws UsageException {
            if (!positional.isEmpty()) {
                throw new UsageException("Unexpected argument " + positional.get(0));
            }
            return value("--config");
        }

        String value(String name) {
            List<String> list = values.get(name);
            return list == null ? null : list.get(0);
        }

        String required(String name) throws UsageException {
            String value = value(name);
            if (value == null) {
                throw new UsageException(name + " is required");
            }
            return value;
        }

        List<String> values(String name) {
            return values.getOrDefault(name, List.of());
        }

        boolean flag(String name) {
            return values.containsKey(name);
        }
    }
}
