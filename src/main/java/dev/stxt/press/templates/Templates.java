package dev.stxt.press.templates;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
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
 * The templates folder of the server (ROADMAP-PDF 2.3), or its documents folder ({@link Kind#DOCUMENT}). A template is a
 * {@code <name>.stxt}, and its path is that of its folder and its name without extension:
 * {@code invoices/2026/invoice}. Beside it, and all optional:
 *
 * <ul>
 * <li>{@code <name>.styles.stxt}, its own styles;</li>
 * <li>{@code <name>.json}, its sample data, only in a templates folder;</li>
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
 *
 * <p>A documents folder has the same rules, without sample data: a document is converted as it is, and a
 * {@code <name>.json} beside it is not read. Its errors have their own codes ({@link TemplateException#DOCUMENT_NOT_FOUND},
 * {@link TemplateException#DOCUMENT_LINK_OUTSIDE}).</p>
 *
 * <p>{@link #save} writes the files of a template or a document, under the same rules: only those of its chain, and
 * never through a link that leaves the folder. {@link #create} creates a new one in a folder that exists.</p>
 */
public final class Templates {

    /** The name of the styles file of a folder, and the end of that of a template. */
    public static final String STYLES = ".styles.stxt";

    private static final String DOCUMENT = ".stxt";
    private static final String DATA = ".json";

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*");

    /** What a folder has: templates, with their sample data, or documents, converted as they are. */
    public enum Kind {
        /** The templates of {@code /print}, with their sample data. */
        TEMPLATE("template", "templates", TemplateException.NOT_FOUND, TemplateException.LINK_OUTSIDE,
                TemplateException.EXISTS),
        /** The documents of {@code /documents}, without data. */
        DOCUMENT("document", "documents", TemplateException.DOCUMENT_NOT_FOUND, TemplateException.DOCUMENT_LINK_OUTSIDE,
                TemplateException.DOCUMENT_EXISTS);

        private final String noun;
        private final String folderNoun;
        private final String notFound;
        private final String linkOutside;
        private final String exists;

        Kind(String noun, String folderNoun, String notFound, String linkOutside, String exists) {
            this.noun = noun;
            this.folderNoun = folderNoun;
            this.notFound = notFound;
            this.linkOutside = linkOutside;
            this.exists = exists;
        }

        /** {@return what one of the folder is called in the messages: {@code template} or {@code document}} */
        public String noun() {
            return noun;
        }

        /** {@return the code of a path that is not one of the folder} */
        public String notFound() {
            return notFound;
        }

        /** {@return the error of a path that is not one of the folder, for whoever has none} */
        public TemplateException notFound(String path, String why) {
            return new TemplateException(notFound, TemplateException.Reason.NOT_FOUND, path, "There is no " + noun + " " + path
                    + (why == null ? "" : ": " + why));
        }
    }

    /** The folder, absolute and normalized. */
    private final Path folder;
    /** Whether it has templates or documents. */
    private final Kind kind;
    /** The folder with its links resolved, which every file read has to be inside. */
    private final Path real;

    /**
     * A templates folder.
     *
     * @param folder the templates folder; not {@code null}
     * @throws IOException if it does not exist or is not a folder
     */
    public Templates(Path folder) throws IOException {
        this(folder, Kind.TEMPLATE);
    }

    /**
     * @param folder the folder; not {@code null}
     * @param kind whether it has templates or documents; not {@code null}
     * @throws IOException if it does not exist or is not a folder
     */
    public Templates(Path folder, Kind kind) throws IOException {
        this.kind = Objects.requireNonNull(kind, "kind");
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

    /** {@return the folder, absolute and normalized} */
    public Path folder() {
        return folder;
    }

    /** {@return whether the folder has templates or documents} */
    public Kind kind() {
        return kind;
    }

    /**
     * {@return the template of {@code path}, with its document, its chain of styles and its sample data, read now} In a
     * documents folder, the document, without data.
     *
     * @param path the path of the template, without extension and with {@code /} between segments
     * @throws TemplateException if there is no template at {@code path} ({@link Kind#notFound()}) or a file of it is a
     *         link that leaves the folder ({@link TemplateException#LINK_OUTSIDE},
     *         {@link TemplateException#DOCUMENT_LINK_OUTSIDE})
     * @throws IOException if a file of it cannot be read or is not valid UTF-8, with the path of that file in its
     *         message
     */
    public Template get(String path) throws IOException {
        if (!isPath(path) || !isFile(path + DOCUMENT)) {
            throw kind.notFound(path, null);
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

        String data = kind == Kind.TEMPLATE && isFile(path + DATA) ? read(path + DATA, path) : null;
        return new Template(path, document, styles, data);
    }

    /**
     * {@return the file of each input of the job of {@code template}, by the {@link PressException#source() source} of its
     * errors} The document, each of its styles by its name and, in a templates folder, the sample data,
     * {@code <name>.json}, which may not exist.
     */
    public Map<String, Path> files(Template template) {
        Map<String, Path> files = new LinkedHashMap<>();
        files.put(PressException.DOCUMENT, folder.resolve(template.path() + DOCUMENT));
        for (Job.Styles styles : template.styles()) {
            files.put(styles.name(), folder.resolve(styles.name()));
        }
        if (kind == Kind.TEMPLATE) {
            files.put(PressException.DATA, folder.resolve(template.path() + DATA));
        }
        return files;
    }

    /**
     * The texts a save writes: the document, the styles files by their name (the path {@link #get} gives them) and, of a
     * template, its sample data. A styles file left out, or the data as {@code null}, is not touched.
     *
     * @param document the text of {@code <name>.stxt}; not {@code null}
     * @param styles the texts of the styles files, by their name; not {@code null}
     * @param data the text of {@code <name>.json}, or {@code null} to leave it as it is; always {@code null} for a
     *        document
     */
    public record Change(String document, Map<String, String> styles, String data) {

        public Change {
            Objects.requireNonNull(document, "document");
            styles = Map.copyOf(styles);
        }
    }

    /**
     * What a save did.
     *
     * @param template the template or the document as it is now, read again from its files
     * @param written the files written, by their path in the folder, in the order of the chain; empty if none changed
     */
    public record Saved(Template template, List<String> written) {

        public Saved {
            written = List.copyOf(written);
        }
    }

    /**
     * Writes the files of the template or the document of {@code path}, if they are still those of {@code version}: the
     * {@link Template#version()} the change starts from, so that a save does not undo one made by someone else (or on the
     * disk) since. Only the files whose text changes are written, each one whole (to a file beside it that then takes
     * its place), with the line ends it had: CRLF if it had them, LF otherwise. A file of the chain that does not exist
     * is created only if it is the own styles ({@code <name>.styles.stxt}) or the sample data ({@code <name>.json}) of
     * the template and its text is not blank; the {@code .styles.stxt} of a folder is never created, and no file is
     * deleted. A file that is a link inside the folder is written where the link goes.
     *
     * @param path the path of the template or the document
     * @param version the version the change starts from; not {@code null}
     * @param change the texts to write; not {@code null}
     * @return the template or the document as it is now, and the files written
     * @throws TemplateException if there is no template at {@code path}, if a file of it is a link that leaves the folder,
     *         or if its files are not those of {@code version} ({@link TemplateException#FILES_CHANGED})
     * @throws IllegalArgumentException if {@code change} names a styles file that is not of the chain or its own, or has
     *         data for a document
     * @throws IOException if a file cannot be read or written
     */
    public synchronized Saved save(String path, String version, Change change) throws IOException {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(change, "change");
        Template current = get(path);
        if (!current.version().equals(version)) {
            throw new TemplateException(TemplateException.FILES_CHANGED, TemplateException.Reason.CHANGED, path,
                    "The files of the " + kind.noun + " " + path + " changed since they were read");
        }
        if (change.data() != null && kind != Kind.TEMPLATE) {
            throw new IllegalArgumentException("A document has no data");
        }
        Map<String, String> existing = new LinkedHashMap<>();
        for (Job.Styles styles : current.styles()) {
            existing.put(styles.name(), styles.text());
        }
        String own = path + STYLES;
        for (String name : change.styles().keySet()) {
            if (!existing.containsKey(name) && !name.equals(own)) {
                throw new IllegalArgumentException("The styles file " + name + " is not of the chain of " + path);
            }
        }

        // Everything checked before writing anything: the document, then the chain in order, then the data.
        Map<String, String> targets = new LinkedHashMap<>();
        targets.put(path + DOCUMENT, change.document());
        for (String name : existing.keySet()) {
            if (change.styles().containsKey(name)) {
                targets.put(name, change.styles().get(name));
            }
        }
        if (!existing.containsKey(own) && change.styles().containsKey(own)) {
            targets.put(own, change.styles().get(own));
        }
        if (change.data() != null) {
            targets.put(path + DATA, change.data());
        }
        List<String> written = new ArrayList<>();
        for (Map.Entry<String, String> target : targets.entrySet()) {
            if (write(target.getKey(), target.getValue(), path)) {
                written.add(target.getKey());
            }
        }
        return new Saved(get(path), written);
    }

    /**
     * Creates the template or the document of {@code path}, which must not exist: its {@code <name>.stxt} with the
     * document of {@code change} and, if their text is not blank, its own styles ({@code <name>.styles.stxt}) and, of a
     * template, its sample data ({@code <name>.json}), with LF. Its folder has to exist already: no folder is created,
     * and no other file is written. The {@code .stxt} is created only if no file has its name, as one step, so that two
     * creations at once do not write one over the other.
     *
     * @param path the path of the new template or document
     * @param change the texts to write; its styles can only be the own ones; not {@code null}
     * @return the new template or document, read from its files, and the files written
     * @throws TemplateException if {@code path} is not a valid path or its folder does not exist ({@link Kind#notFound()}),
     *         if there is already one at {@code path} ({@link TemplateException#EXISTS},
     *         {@link TemplateException#DOCUMENT_EXISTS}), or if its folder is a link that leaves the folder
     * @throws IllegalArgumentException if {@code change} has styles that are not the own ones, or data for a document
     * @throws IOException if a file cannot be written
     */
    public synchronized Saved create(String path, Change change) throws IOException {
        Objects.requireNonNull(change, "change");
        if (!isPath(path)) {
            throw kind.notFound(path, "not a valid path");
        }
        if (change.data() != null && kind != Kind.TEMPLATE) {
            throw new IllegalArgumentException("A document has no data");
        }
        String own = path + STYLES;
        for (String name : change.styles().keySet()) {
            if (!name.equals(own)) {
                throw new IllegalArgumentException("A new " + kind.noun + " has only its own styles, " + own
                        + ", not " + name);
            }
        }
        Path file = folder.resolve(path + DOCUMENT);
        Path parent = file.getParent();
        if (!Files.isDirectory(parent)) {
            String above = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
            throw kind.notFound(path, "the folder " + above + " does not exist");
        }
        if (!parent.toRealPath().startsWith(real)) {
            throw linkOutside(path + DOCUMENT, path);
        }
        TemplateException exists = new TemplateException(kind.exists, TemplateException.Reason.EXISTS, path,
                "There is already a " + kind.noun + " " + path);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw exists;
        }
        try {
            Files.writeString(file, lf(change.document()), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            throw exists;
        }
        List<String> written = new ArrayList<>(List.of(path + DOCUMENT));
        if (change.styles().containsKey(own) && write(own, change.styles().get(own), path)) {
            written.add(own);
        }
        if (change.data() != null && write(path + DATA, change.data(), path)) {
            written.add(path + DATA);
        }
        return new Saved(get(path), written);
    }

    /** {@return {@code text} with LF line ends} */
    private static String lf(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * Writes {@code text} in the file {@code relative} if it changes it; {@return whether it was written}. A file that
     * does not exist is created only if the text is not blank.
     */
    private boolean write(String relative, String text, String template) throws IOException {
        Path file = folder.resolve(relative);
        Path parent = file.getParent();
        if (!Files.isDirectory(parent) || !parent.toRealPath().startsWith(real)) {
            throw linkOutside(relative, template);
        }
        Path target = file;
        String before = null;
        if (Files.exists(file)) {
            target = file.toRealPath();
            if (!target.startsWith(real) || !Files.isRegularFile(target)) {
                throw linkOutside(relative, template);
            }
            before = Files.readString(target, StandardCharsets.UTF_8);
        } else if (text.isBlank()) {
            return false;
        }
        String lf = lf(text);
        if (before != null && lf(before).equals(lf)) {
            return false;
        }
        String out = before != null && before.contains("\r\n") ? lf.replace("\n", "\r\n") : lf;
        byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
        AtomicFile.write(target, stream -> stream.write(bytes));
        return true;
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
                    entries.add(new Entry(path, isFile(path + STYLES), kind == Kind.TEMPLATE && isFile(path + DATA)));
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
     * @param data whether it has sample data, a {@code <name>.json}; never in a documents folder
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
            throw linkOutside(relative, template);
        }
        try {
            return FileUtils.readFileContent(file.toFile());
        } catch (IOException e) {
            // The message of the JDK does not say which file: Input length = 1, for one that is not UTF-8.
            throw new IOException(file + " cannot be read, or is not UTF-8: " + e.getMessage(), e);
        }
    }

    private TemplateException linkOutside(String relative, String template) {
        return new TemplateException(kind.linkOutside, TemplateException.Reason.LINK_OUTSIDE, template, "The file "
                + relative + " of the " + kind.noun + " " + template + " is a link that leaves the " + kind.folderNoun
                + " folder");
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
