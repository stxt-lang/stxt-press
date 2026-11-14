package dev.stxt.press.http;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import dev.stxt.pdf.model.Cancellation;
import dev.stxt.press.Job;
import dev.stxt.press.Options;
import dev.stxt.press.Press;
import dev.stxt.press.PressException;
import dev.stxt.press.templates.Template;
import dev.stxt.press.templates.TemplateException;
import dev.stxt.press.templates.Templates;

/**
 * The conversions of the warm up of a server ({@code Warm up} of {@code server.stxt}): every template of the templates
 * folder with its sample data and every document of the documents folder, to PDF and to SVG, and the output thrown
 * away; without templates or documents, a small document in the first font family of the server. They read
 * the fonts and the images the documents use and run once the code of the engine, so that the first request is as fast
 * as the next ones.
 */
final class WarmUp {

    /** The document of a server without templates or documents, with the font family to write it in. */
    private static final String DOCUMENT = """
            Document (dev.stxt.pdf):
                Language: en
                Title: Warm up
                Styles:
                    For element: Document
                        Typography:
                            Font: %s
                            Size: 11
                Page:
                    Paper: A4
                Section:
                    Paragraph: Warm up.
            """;

    /**
     * What a warm up did.
     *
     * @param converted how many documents were converted
     * @param failures the documents that could not be converted, each with its error
     * @param elapsed how long it took
     */
    record Result(int converted, List<Failure> failures, Duration elapsed) {
    }

    /**
     * A document that could not be converted.
     *
     * @param kind whether it is a template or a document of the documents folder; {@link Templates.Kind#TEMPLATE} for
     *        the document of a server without templates or documents
     * @param path the path of the template or the document, or {@code null} for the document of a server without
     *        templates or documents, or for a folder that cannot be read
     * @param withData whether the template has sample data: without them, a template with Slots cannot be converted,
     *        and that is no error of the template; {@code true} for a document, which has no Slots to fill
     * @param error what the error says, with its code and its line
     * @param unexpected the exception of an error that no job expects (a failure of the engine with this document), for
     *        its stack trace in the log; {@code null} for the others, whose message says it all
     */
    record Failure(Templates.Kind kind, String path, boolean withData, String error, Throwable unexpected) {

        /**
         * {@return whether it is a warning in the log, and not only information} Only the failure of a template without
         * sample data is information, since a template with Slots needs the data of the request; an unexpected error
         * is always a warning.
         */
        boolean warning() {
            return withData || unexpected != null;
        }
    }

    /** What converts a job in the warm up, throwing its output away. */
    @FunctionalInterface
    interface Conversion {
        void convert(Job job) throws IOException;
    }

    /** What a warm up tries for one document: reading it and converting it. */
    @FunctionalInterface
    private interface Step {
        void run() throws IOException;
    }

    private WarmUp() {
    }

    /**
     * Converts every template of {@code templates} and every document of {@code documents}, or the small document if
     * there are none and the server has fonts, to PDF and to SVG.
     *
     * @param press the core of the server, on its shared converter
     * @param templates the templates folder, or {@code null}
     * @param documents the documents folder, or {@code null}
     * @param font the font family of the small document, or {@code null} if the server has no fonts
     * @param options the options of the templates and the documents
     * @param timeout the time each conversion has
     * @return what it did
     */
    static Result run(Press press, Templates templates, Templates documents, String font, Options options,
            Duration timeout) {
        return run(job -> {
            press.pdf(job, OutputStream.nullOutputStream(), Cancellation.timeout(timeout));
            press.svg(job, Cancellation.timeout(timeout));
        }, templates, documents, font, options);
    }

    /**
     * Like {@link #run(Press, Templates, Templates, String, Options, Duration)}, with {@code conversion} converting each
     * job: for the tests, which need errors the engine does not give at will.
     */
    static Result run(Conversion conversion, Templates templates, Templates documents, String font,
            Options options) {
        long start = System.nanoTime();
        List<Failure> failures = new ArrayList<>();
        int converted = 0;
        boolean none = true;
        for (Templates folder : new Templates[] {templates, documents}) {
            if (folder == null) {
                continue;
            }
            List<Templates.Entry> entries;
            try {
                entries = folder.list();
            } catch (IOException e) {
                failures.add(new Failure(folder.kind(), null, true, "The " + folder.kind().noun()
                        + "s folder cannot be read: " + e.getMessage(), null));
                continue;
            }
            none &= entries.isEmpty();
            for (Templates.Entry entry : entries) {
                Failure failure = attempt(folder.kind(), entry.path(),
                        folder.kind() == Templates.Kind.DOCUMENT || entry.data(),
                        () -> conversion.convert(folder.get(entry.path()).job(options)));
                if (failure == null) {
                    converted++;
                } else {
                    failures.add(failure);
                }
            }
        }
        if (none && font != null) {
            Failure failure = attempt(Templates.Kind.TEMPLATE, null, true, () -> conversion
                    .convert(new Job(DOCUMENT.formatted(font), List.of(), null, Options.DEFAULT)));
            if (failure == null) {
                converted++;
            } else {
                failures.add(failure);
            }
        }
        return new Result(converted, List.copyOf(failures), Duration.ofNanos(System.nanoTime() - start));
    }

    /** Runs {@code step} for one document; {@return its failure, or {@code null} if it was converted} */
    private static Failure attempt(Templates.Kind kind, String path, boolean withData, Step step) {
        String error;
        try {
            step.run();
            return null;
        } catch (PressException e) {
            error = e.code() + (e.line() > 0 ? " (line " + e.line() + " of " + e.source() + ")" : "") + ": "
                    + e.getMessage();
        } catch (TemplateException e) {
            error = e.code() + ": " + e.getMessage();
        } catch (IOException e) {
            // Templates.get says which file in its message.
            error = e.getMessage();
        } catch (RuntimeException e) {
            // An error no job expects: a failure of the engine with this document. Over HTTP it is a 500 and the server
            // goes on, so here it does not stop the start either.
            return new Failure(kind, path, withData, "Unexpected error: " + e, e);
        }
        return new Failure(kind, path, withData, error, null);
    }
}
