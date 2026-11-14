package dev.stxt.press;

import java.util.Map;

import dev.stxt.pdf.model.StxtPdfException;
import dev.stxt.pdf.model.StxtPdfException.Kind;

/**
 * An error of a job, whatever it comes from (the engine, the fill of Slots, the parser), saying which input of the job
 * its line belongs to. It is what the HTTP service returns and what the editor paints in the panel of that input.
 *
 * <p>The kinds are the ones of the engine ({@link StxtPdfException.Kind}): an error of the fill or of the data is
 * {@link Kind#INVALID_DOCUMENT}, and one of a limit of the JSON reader or of the parser, {@link Kind#LIMIT}.</p>
 */
public final class PressException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The source of a line of the layout document, the one with Slots if it has them. */
    public static final String DOCUMENT = "document";
    /** The source of a line of the JSON data. */
    public static final String DATA = "data";
    /**
     * The source of a line of the filled document: its Slots are filled, so it is not the text of the request and its
     * lines are not those of the document with Slots. The text is in {@link #filled()}.
     */
    public static final String FILLED = "filled";

    /** The value of {@link #line()} when the error does not belong to any line. */
    public static final int NO_LINE = 0;

    /** Whose fault the error is. */
    private final Kind kind;
    /** The stable code of the error, in capitals. */
    private final String code;
    /** The line of the error in {@link #source}, or {@link #NO_LINE}. */
    private final int line;
    /** The input the error belongs to, or {@code null}. */
    private final String source;
    /** The filled document, when the error is one of its lines. */
    private final String filled;

    PressException(Kind kind, String code, int line, String source, String filled, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.code = code;
        this.line = line;
        this.source = source;
        this.filled = filled;
    }

    /** {@return whose fault the error is} */
    public Kind kind() {
        return kind;
    }

    /** {@return the stable code of the error, in capitals ({@code BOX_OVERFLOW}, {@code SLOT_NO_DATA})} */
    public String code() {
        return code;
    }

    /** {@return the line of the error in {@link #source()}, from 1, or {@link #NO_LINE}} */
    public int line() {
        return line;
    }

    /**
     * {@return the input the error belongs to: {@link #DOCUMENT}, {@link #DATA}, {@link #FILLED} or the name of a
     * separate styles document ({@link Job.Styles#name()}); {@code null} if it belongs to none (a resource of the server,
     * a failure of the engine, a cancellation)}
     */
    public String source() {
        return source;
    }

    /** {@return the text of the filled document when {@link #source()} is {@link #FILLED}, or {@code null}} */
    public String filled() {
        return filled;
    }

    /**
     * {@return the error as one line, {@code where:line: CODE: message}, for a log or a terminal} {@code where} is the
     * name {@code names} gives the {@link #source()}, such as its file, or the source itself if it gives none, and
     * {@code the filled document} for {@link #FILLED}, whose text is not in any file. Without a line, {@code where: CODE:
     * message}; without a source, {@code CODE: message}.
     *
     * @param names the name of each source, such as the file of {@link #DOCUMENT}; not {@code null}, may be empty
     */
    public String describe(Map<String, String> names) {
        String where = FILLED.equals(source) ? "the filled document"
                : source == null ? null : names.getOrDefault(source, source);
        String place = where == null ? "" : where + (line > NO_LINE ? ":" + line : "") + ": ";
        return place + code + ": " + getMessage();
    }
}
