package dev.stxt.press.templates;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import dev.stxt.press.Job;
import dev.stxt.press.PressException;
import dev.stxt.utils.FileUtils;

/**
 * The templates folder of the server (ROADMAP-PDF 2.3). A template is a {@code <name>.stxt}, and its path is that of its
 * folder and its name without extension: {@code invoices/2026/invoice}. Beside it, and all optional:
 *
 * <ul>
 * <li>{@code <name>.styles.stxt}, its own styles;</li>
 * <li>{@code <name>.json}, its sample data;</li>
 * <li>a {@code .styles.stxt} in any folder from the root to its own, shared by the templates below it.</li>
 * </ul>
 *
 * <p>The styles are chained from the root to the template, in the order the engine takes several styles documents: the
 * nearest to the template wins, and the {@code Styles} of the template wins over all of them.</p>
 *
 * <p>Every segment of a path is {@code [A-Za-z0-9_-]+}, so neither {@code ..}, nor a hidden file, nor a
 * {@code .styles.stxt} or a {@code .json} is a template. A path never leaves the folder, nor through a symbolic link:
 * a file whose real path is outside it is not read. The folder is only read, on every call, so a changed template
 * counts without restarting.</p>
 */
public final class Templates {

    /** The name of the styles file of a folder, and the end of that of a template. */
    public static final String STYLES = ".styles.stxt";

    private static final String DOCUMENT = ".stxt";
    private static final String DATA = ".json";

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*");

    /** The folder, absolute and normalized. */
    private final Path folder;
    /** The folder with its links resolved, which every file read has to be inside. */
    private final Path real;

    /**
     * @param folder the templates folder; not {@code null}
     * @throws IOException if it does not exist or is not a folder
     */
    public Templates(Path folder) throws IOException {
        this.folder = Objects.requireNonNull(folder, "folder").toAbsolutePath().normalize();
        this.real = this.folder.toRealPath();
        if (!Files.isDirectory(real)) {
            throw new NotDirectoryException(this.folder.toString());
        }
    }

    /**
     * {@return whether {@code path} has the form of the path of a template: segments of {@code [A-Za-z0-9_-]+} between
     * {@code /}} It says nothing of whether the template exists.
     */
    public static boolean isPath(String path) {
        return path != null && PATH.matcher(path).matches();
    }

    /** {@return the templates folder, absolute and normalized} */
    public Path folder() {
        return folder;
    }

    /**
     * {@return the template of {@code path}, with its document, its chain of styles and its sample data, read now}
     *
     * @param path the path of the template, without extension and with {@code /} between segments
     * @throws TemplateException if there is no template at {@code path} ({@link TemplateException#NOT_FOUND}) or a file
     *         of it is a link that leaves the folder ({@link TemplateException#LINK_OUTSIDE})
     * @throws IOException if a file of it cannot be read or is not valid UTF-8
     */
    public Template get(String path) throws IOException {
        if (!isPath(path) || !isFile(path + DOCUMENT)) {
            throw new TemplateException(TemplateException.NOT_FOUND, path, "There is no template " + path);
        }
        String document = read(path + DOCUMENT, path);

        List<Job.Styles> styles = new ArrayList<>();
        // The folders from the root to that of the template, then the template.
        String above = "";
        for (String segment : path.split("/")) {
            addStyles(styles, above + STYLES, path);
            above += segment + "/";
        }
        addStyles(styles, path + STYLES, path);

        String data = isFile(path + DATA) ? read(path + DATA, path) : null;
        return new Template(path, document, styles, data);
    }

    /**
     * {@return the file of each input of the job of {@code template}, by the {@link PressException#source() source} of its
     * errors} The document, each of its styles by its name and the sample data, {@code <name>.json}, which may not
     * exist.
     */
    public Map<String, Path> files(Template template) {
        Map<String, Path> files = new LinkedHashMap<>();
        files.put(PressException.DOCUMENT, folder.resolve(template.path() + DOCUMENT));
        for (Job.Styles styles : template.styles()) {
            files.put(styles.name(), folder.resolve(styles.name()));
        }
        files.put(PressException.DATA, folder.resolve(template.path() + DATA));
        return files;
    }

    /**
     * {@return the templates of the folder, ordered by their path} Links to folders are not gone into, and a template
     * that is a link leaving the folder is left out ({@link #get} reports it).
     *
     * @throws IOException if the folder cannot be walked
     */
    public List<Entry> list() throws IOException {
        List<Entry> entries = new ArrayList<>();
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                return dir.equals(folder) || SEGMENT.matcher(dir.getFileName().toString()).matches()
                        ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String name = file.getFileName().toString();
                if (name.endsWith(DOCUMENT)
                        && SEGMENT.matcher(name.substring(0, name.length() - DOCUMENT.length())).matches()
                        && Files.isRegularFile(file) && file.toRealPath().startsWith(real)) {
                    String relative = relative(file);
                    String path = relative.substring(0, relative.length() - DOCUMENT.length());
                    entries.add(new Entry(path, isFile(path + STYLES), isFile(path + DATA)));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        entries.sort(Comparator.comparing(Entry::path));
        return entries;
    }

    /**
     * A template of {@link #list()}.
     *
     * @param path its path, without extension
     * @param styles whether it has its own {@code <name>.styles.stxt}
     * @param data whether it has sample data, a {@code <name>.json}
     */
    public record Entry(String path, boolean styles, boolean data) {
    }

    /** Adds the styles file {@code relative} to the chain, with its path as its name, if it exists. */
    private void addStyles(List<Job.Styles> styles, String relative, String template) throws IOException {
        if (isFile(relative)) {
            styles.add(new Job.Styles(relative, read(relative, template)));
        }
    }

    /** {@return whether {@code relative} is a file of the folder, following links} */
    private boolean isFile(String relative) {
        return Files.isRegularFile(folder.resolve(relative));
    }

    /** {@return the text of the file {@code relative} of the template {@code template}, if it is inside the folder} */
    private String read(String relative, String template) throws IOException {
        Path file = folder.resolve(relative);
        if (!file.toRealPath().startsWith(real)) {
            throw new TemplateException(TemplateException.LINK_OUTSIDE, template, "The file " + relative
                    + " of the template " + template + " is a link that leaves the templates folder");
        }
        return FileUtils.readFileContent(file.toFile());
    }

    /** {@return the path of {@code file} from the folder, with {@code /} between segments} */
    private String relative(Path file) {
        StringBuilder out = new StringBuilder();
        for (Path segment : folder.relativize(file)) {
            out.append(out.length() == 0 ? "" : "/").append(segment);
        }
        return out.toString();
    }
}
