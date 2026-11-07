package dev.stxt.press;

import java.util.List;
import java.util.Objects;

/**
 * What a request asks to convert: a layout document ({@code dev.stxt.pdf}), with or without Slots, its separate styles
 * documents, the data that fills its Slots and the options.
 *
 * @param document the STXT text of the layout document, with or without Slots; not {@code null}
 * @param styles the separate styles documents, in the order they apply: the later one wins, and the {@code Styles} of
 *        the document wins over all of them; not {@code null}, may be empty
 * @param data the JSON text that fills the Slots of the document before converting it, or {@code null} to convert it as
 *        it is
 * @param options the options of the engine for this job; not {@code null}
 */
public record Job(String document, List<Styles> styles, String data, Options options) {

    /**
     * A separate styles document and the name it has in the errors ({@link PressException#source()}): the place in the
     * request ({@code styles[1]}) or the path of its file in a template.
     *
     * @param name the name of the styles document in the errors; not {@code null}
     * @param text its STXT text; not {@code null}
     */
    public record Styles(String name, String text) {

        public Styles {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(text, "text");
        }
    }

    public Job {
        Objects.requireNonNull(document, "document");
        styles = List.copyOf(styles);
        Objects.requireNonNull(options, "options");
    }

    /** {@return a job that converts {@code document} as it is, without separate styles and with the default options} */
    public static Job of(String document) {
        return new Job(document, List.of(), null, Options.DEFAULT);
    }

    /** {@return this job with {@code styles} as its separate styles documents, instead of the ones it had} */
    public Job withStyles(List<Styles> styles) {
        return new Job(document, styles, data, options);
    }

    /** {@return this job with {@code data} as the JSON that fills its Slots, or without filling with {@code null}} */
    public Job withData(String data) {
        return new Job(document, styles, data, options);
    }

    /** {@return this job with {@code options}} */
    public Job withOptions(Options options) {
        return new Job(document, styles, data, options);
    }
}
