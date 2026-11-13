package dev.stxt.press.templates;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import dev.stxt.press.Job;
import dev.stxt.press.Options;

/**
 * A template of the server, read from its folder: its document, its chain of styles and its sample data. A document of
 * the documents folder is one without data ({@link Templates.Kind#DOCUMENT}).
 *
 * @param path the path of the template in the templates folder, without extension ({@code invoices/invoice}); not
 *        {@code null}
 * @param document the STXT text of its {@code <name>.stxt}, with or without Slots; not {@code null}
 * @param styles its chain of styles, from the root of the templates folder to its {@code <name>.styles.stxt}, each with
 *        the path of its file as its name ({@code .styles.stxt}, {@code invoices/.styles.stxt}); not {@code null}, may
 *        be empty
 * @param data the JSON text of its {@code <name>.json}, or {@code null} if it has none
 */
public record Template(String path, String document, List<Job.Styles> styles, String data) {

    public Template {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(document, "document");
        styles = List.copyOf(styles);
    }

    /**
     * {@return the version of its files: the SHA-256, in lowercase hexadecimal, of the name and the text of each one, in
     * the order of the chain} Any change in a file, a styles file that appears or goes, or sample data that appear or
     * go, gives another version. {@link Templates#save} takes it to know that the files are still those that were read.
     */
    public String version() {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java has SHA-256", e);
        }
        add(digest, path + ".stxt", document);
        for (Job.Styles item : styles) {
            add(digest, item.name(), item.text());
        }
        if (data != null) {
            add(digest, path + ".json", data);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Adds a file to the digest: its name and its text, each one ended by a zero, which no text has. */
    private static void add(MessageDigest digest, String name, String text) {
        digest.update(name.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(text.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    /** {@return the name of the template: the last segment of its path, which names its PDF} */
    public String name() {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * {@return the job of a {@code GET}: the template filled with its sample data, or converted as it is without them}
     *
     * @param options the options of the templates of the server; not {@code null}
     */
    public Job job(Options options) {
        return new Job(document, styles, data, options);
    }

    /**
     * {@return the job of a {@code POST}: the template filled with {@code data}, which replace its sample data whole}
     *
     * @param options the options of the templates of the server; not {@code null}
     * @param data the JSON text of the request; not {@code null}
     */
    public Job job(Options options, String data) {
        return new Job(document, styles, Objects.requireNonNull(data, "data"), options);
    }
}
