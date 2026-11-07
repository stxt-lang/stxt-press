package dev.stxt.press.templates;

import java.util.List;
import java.util.Objects;

import dev.stxt.press.Job;
import dev.stxt.press.Options;

/**
 * A template of the server, read from its folder: its document, its chain of styles and its sample data.
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
