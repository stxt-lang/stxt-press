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
 * The conversions of the warm up of a server ({@code Warm up} of {@code server.stxt}): every template of the folder
 * with its sample data, to PDF and to SVG, and the output thrown away; without templates, a small document in the first
 * font family of the server. They read
 * the fonts and the images the documents use and run once the code of the engine, so that the first request is as fast
 * as the next ones.
 */
final class WarmUp {

    /** The document of a server without templates, with the font family to write it in. */
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
     * @param path the path of the template, or {@code null} for the document of a server without templates
     * @param withData whether the template has sample data: without them, a template with Slots cannot be converted,
     *        and that is no error of the template
     * @param error what the error says, with its code and its line
     */
    record Failure(String path, boolean withData, String error) {
    }

    private WarmUp() {
    }

    /**
     * Converts every template of {@code templates}, or the small document if there are none and the server has fonts.
     *
     * @param press the core of the server, on its shared converter
     * @param templates the templates folder, or {@code null}
     * @param font the font family of the small document, or {@code null} if the server has no fonts
     * @param options the options of the templates
     * @param timeout the time each conversion has
     * @return what it did
     */
    static Result run(Press press, Templates templates, String font, Options options, Duration timeout) {
        long start = System.nanoTime();
        List<Failure> failures = new ArrayList<>();
        int converted = 0;
        List<Templates.Entry> entries = List.of();
        if (templates != null) {
            try {
                entries = templates.list();
            } catch (IOException e) {
                failures.add(new Failure(null, true, "The templates folder cannot be read: " + e.getMessage()));
            }
        }
        if (entries.isEmpty() && font != null) {
            String error = convert(press, new Job(DOCUMENT.formatted(font), List.of(), null, Options.DEFAULT), timeout);
            if (error == null) {
                converted++;
            } else {
                failures.add(new Failure(null, true, error));
            }
        }
        for (Templates.Entry entry : entries) {
            String error;
            try {
                Template template = templates.get(entry.path());
                error = convert(press, template.job(options), timeout);
            } catch (TemplateException e) {
                error = e.code() + ": " + e.getMessage();
            } catch (IOException e) {
                error = "It cannot be read: " + e.getMessage();
            }
            if (error == null) {
                converted++;
            } else {
                failures.add(new Failure(entry.path(), entry.data(), error));
            }
        }
        return new Result(converted, List.copyOf(failures), Duration.ofNanos(System.nanoTime() - start));
    }

    /** Converts {@code job} to PDF and to SVG; {@return its error, or {@code null} if it was converted} */
    private static String convert(Press press, Job job, Duration timeout) {
        try {
            press.pdf(job, OutputStream.nullOutputStream(), Cancellation.timeout(timeout));
            press.svg(job, Cancellation.timeout(timeout));
            return null;
        } catch (PressException e) {
            return e.code() + (e.line() > 0 ? " (line " + e.line() + " of " + e.source() + ")" : "") + ": "
                    + e.getMessage();
        } catch (IOException e) {
            return e.getMessage();
        }
    }
}
