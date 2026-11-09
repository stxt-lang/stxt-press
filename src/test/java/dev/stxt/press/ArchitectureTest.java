package dev.stxt.press;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Dependencies between the packages of {@code dev.stxt.press}, read from the source code: the {@code import} statements
 * and the qualified names outside comments and strings, as the {@code ArchitectureTest} of {@code stxt-pdf} does. The
 * core is the package {@code dev.stxt.press} itself ({@link #CORE}), and each other package counts with its
 * subpackages.
 *
 * <p>They go one way: {@code cli} → {@code config} → {@code http} → {@code templates} → the core. The core knows nothing
 * of HTTP, templates or files, so it can be used without the service.</p>
 */
class ArchitectureTest {

    private static final Path SOURCES = Path.of("src", "main", "java", "dev", "stxt", "press");
    /** The name the package {@code dev.stxt.press} has here: the core, without HTTP. */
    private static final String CORE = "core";
    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\".*?\"\"\"", Pattern.DOTALL);
    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);
    private static final Pattern STRING = Pattern.compile("\"(?:\\\\.|[^\"\\\\\\n])*\"");
    /** A name of the project: of a package of it (group 1), or of a class of the core or all of it (group 2). */
    private static final Pattern REFERENCE = Pattern.compile("\\bdev\\.stxt\\.press\\.(?:([a-z][a-z0-9]*)\\.|([A-Z*]))");

    /**
     * What each package may use: today's dependencies, written by hand. A new one is not an accident: it is decided and
     * recorded here. {@code config} uses {@code http} because {@code ServerFile} gives the {@code ServerConfig} and
     * starts the server.
     */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            CORE, Set.of(),
            "templates", Set.of(CORE),
            "http", Set.of(CORE, "templates"),
            "config", Set.of(CORE, "templates", "http"),
            "cli", Set.of(CORE, "templates", "http", "config"));

    /** The libraries that only one package may use, by the start of their names: the HTTP layer is {@code http}. */
    private static final Map<String, String> ONLY_IN = Map.of(
            "com.fasterxml.jackson", "http",
            "com.sun.net.httpserver", "http");

    /** {@return the package of a source file: {@link #CORE} for those of {@code dev.stxt.press} itself} */
    private static String packageOf(Path file) {
        Path relative = SOURCES.relativize(file);
        return relative.getNameCount() < 2 ? CORE : relative.getName(0).toString();
    }

    /** {@return the source files, by package} */
    private static Map<String, List<Path>> sources() {
        Map<String, List<Path>> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            walk.filter(f -> f.toString().endsWith(".java"))
                    .forEach(f -> files.computeIfAbsent(packageOf(f), k -> new ArrayList<>()).add(f));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    /** {@return package → packages of the project it depends on} */
    private static Map<String, Set<String>> dependencies() {
        Map<String, Set<String>> graph = new TreeMap<>();
        sources().forEach((from, files) -> {
            Set<String> to = graph.computeIfAbsent(from, k -> new TreeSet<>());
            for (Path file : files) {
                Matcher m = REFERENCE.matcher(code(file));
                while (m.find()) {
                    String used = m.group(1) != null ? m.group(1) : CORE;
                    if (!used.equals(from)) {
                        to.add(used);
                    }
                }
            }
        });
        return graph;
    }

    /** {@return the code of a file without comments, strings or text blocks} */
    private static String code(Path file) {
        try {
            String code = TEXT_BLOCK.matcher(Files.readString(file)).replaceAll("\"\"");
            return STRING.matcher(COMMENT.matcher(code).replaceAll(" ")).replaceAll("\"\"");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void readsEveryPackage() {
        assertEquals(ALLOWED.keySet(), dependencies().keySet());
    }

    /** The test sees the dependencies that there are: if it read nothing, every other test would pass. */
    @Test
    void seesTheDependenciesOfToday() {
        Map<String, Set<String>> graph = dependencies();
        assertEquals(Set.of(CORE), graph.get("templates"));
        assertEquals(ALLOWED.get("cli"), graph.get("cli"));
    }

    @Test
    void noCyclesBetweenPackages() {
        Map<String, Set<String>> graph = dependencies();
        for (String start : graph.keySet()) {
            List<String> cycle = cycle(graph, start, new ArrayList<>(List.of(start)));
            assertTrue(cycle.isEmpty(), () -> "Package cycle: " + String.join(" -> ", cycle));
        }
    }

    /** {@return a path from {@code path} that returns to its first package, or empty if there is none} */
    private static List<String> cycle(Map<String, Set<String>> graph, String current, List<String> path) {
        for (String next : graph.getOrDefault(current, Set.of())) {
            if (next.equals(path.get(0))) {
                List<String> cycle = new ArrayList<>(path);
                cycle.add(next);
                return cycle;
            }
            if (!path.contains(next)) {
                path.add(next);
                List<String> found = cycle(graph, next, path);
                if (!found.isEmpty()) {
                    return found;
                }
                path.remove(path.size() - 1);
            }
        }
        return List.of();
    }

    /** The core is the jobs and their errors: it uses the engine, and nothing of the service. */
    @Test
    void theCoreDependsOnNothingOfTheService() {
        assertEquals(Set.of(), dependencies().get(CORE));
    }

    /** No package depends on another one it is not allowed to use ({@link #ALLOWED}). */
    @Test
    void everyDependencyIsAllowed() {
        dependencies().forEach((from, to) -> {
            Set<String> extra = new TreeSet<>(to);
            extra.removeAll(ALLOWED.get(from));
            assertTrue(extra.isEmpty(), () -> from + " depends on " + extra + ", which is not allowed");
        });
    }

    /** Jackson and the {@code HttpServer} of the JDK are of the HTTP layer only ({@link #ONLY_IN}). */
    @Test
    void theHttpLibrariesAreOnlyInHttp() {
        List<String> found = new ArrayList<>();
        sources().forEach((from, files) -> {
            for (Path file : files) {
                String code = code(file);
                ONLY_IN.forEach((library, only) -> {
                    if (!from.equals(only) && Pattern.compile("\\b" + Pattern.quote(library) + "\\b").matcher(code).find()) {
                        found.add(SOURCES.relativize(file) + " uses " + library);
                    }
                });
            }
        });
        assertEquals(List.of(), found);
    }
}
