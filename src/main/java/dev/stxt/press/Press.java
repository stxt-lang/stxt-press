package dev.stxt.press;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import dev.stxt.InlineNode;
import dev.stxt.Node;
import dev.stxt.Parser;
import dev.stxt.exceptions.LimitException;
import dev.stxt.exceptions.ParseException;
import dev.stxt.pdf.convert.Converter;
import dev.stxt.pdf.layout.LaidOutDocument;
import dev.stxt.pdf.model.Attachments;
import dev.stxt.pdf.model.Cancellation;
import dev.stxt.pdf.model.Check;
import dev.stxt.pdf.model.StxtPdfException;
import dev.stxt.pdf.model.StxtPdfException.Kind;
import dev.stxt.pdf.stxt.DocumentReader;
import dev.stxt.runtime.NodeWriter;
import dev.stxt.transform.FillJson;

/**
 * The jobs of the service, without HTTP: a PDF, a preview as one SVG per page, and the fill of the Slots of a document
 * (ROADMAP-PDF 2.2). With data, a job fills the Slots of its document first and converts the filled document; without
 * them, it converts the document as it is.
 *
 * <p>Every error is a {@link PressException}, which says which input of the job its line belongs to. An error of the
 * filled document (the engine validates it, not the document with Slots) has the line of the filled text, which it
 * carries, because a repeated Slot makes it longer and its lines are not those of the document with Slots.</p>
 *
 * <p>A {@code Press} is immutable and safe between threads, like the {@link Converter} it shares: one per server.</p>
 */
public final class Press {

    /** What every job shares: the resources of the server, read once, and its limits. */
    private final Converter shared;

    /**
     * @param shared the converter of the server, with its resources and its limits, and without separate styles or a
     *        PDF/A of its own: those of each job are added to it ({@link Converter#toBuilder()}); not {@code null}
     */
    public Press(Converter shared) {
        this.shared = Objects.requireNonNull(shared, "shared");
    }

    /**
     * {@return the version of the service, that of the manifest of its jar, or {@code null} if its classes do not come
     * from a jar (the tests)}
     */
    public static String version() {
        return Press.class.getPackage().getImplementationVersion();
    }

    /**
     * {@return the producer of the PDFs of the service, with its version and that of the engine:
     * {@code stxt-press 0.1.0 (stxt-pdf 0.18.0)}} Without a jar (the tests), {@code stxt-press (stxt-pdf 0.18.0)}.
     */
    public static String producer() {
        String press = version();
        String engine = Converter.version();
        return "stxt-press" + (press == null ? "" : " " + press) + (engine == null ? "" : " (stxt-pdf " + engine + ")");
    }

    /**
     * {@return {@code bytes} as UTF-8 text, decoded strictly, as {@code stxt-core} reads a file: bytes that are not UTF-8
     * are an error, never a U+FFFD} A BOM is kept.
     *
     * @throws CharacterCodingException if {@code bytes} are not UTF-8
     */
    public static String text(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    /**
     * Writes the PDF of a job. The document is laid out before anything is written, so an error of the document, of its
     * styles or of its data leaves {@code target} untouched.
     *
     * @param job what to convert; not {@code null}
     * @param target where the PDF is written; not {@code null}
     * @param cancellation what stops this job, such as its deadline; {@link Cancellation#NONE} for nothing
     * @throws PressException if the job cannot be converted
     * @throws IOException if writing to {@code target} fails
     */
    public void pdf(Job job, OutputStream target, Cancellation cancellation) throws IOException {
        Objects.requireNonNull(target, "target");
        Prepared prepared = prepare(job);
        try {
            LaidOutDocument laidOut = prepared.converter.layOut(prepared.document, cancellation);
            prepared.converter.toPdf(laidOut, target, Attachments.NONE, cancellation);
        } catch (StxtPdfException e) {
            throw fromEngine(e, job, prepared.filled);
        }
    }

    /**
     * Like {@link #pdf(Job, OutputStream, Cancellation)}, without a cancellation.
     *
     * @param job what to convert; not {@code null}
     * @param target where the PDF is written; not {@code null}
     * @throws PressException if the job cannot be converted
     * @throws IOException if writing to {@code target} fails
     */
    public void pdf(Job job, OutputStream target) throws IOException {
        pdf(job, target, Cancellation.NONE);
    }

    /**
     * {@return the preview of a job: one SVG per page, of the same layout as its PDF, with the outlines of the boxes if
     * its options ask for them}
     *
     * @param job what to convert; not {@code null}
     * @param cancellation what stops this job, such as its deadline; {@link Cancellation#NONE} for nothing
     * @throws PressException if the job cannot be converted
     */
    public List<String> svg(Job job, Cancellation cancellation) {
        Prepared prepared = prepare(job);
        try {
            return prepared.converter.toSvg(prepared.converter.layOut(prepared.document, cancellation), cancellation);
        } catch (StxtPdfException e) {
            throw fromEngine(e, job, prepared.filled);
        }
    }

    /**
     * Like {@link #svg(Job, Cancellation)}, without a cancellation.
     *
     * @param job what to convert; not {@code null}
     * @return one SVG per page
     * @throws PressException if the job cannot be converted
     */
    public List<String> svg(Job job) {
        return svg(job, Cancellation.NONE);
    }

    /**
     * {@return the document filled with the data, as STXT text: the text a job with these data converts, so the lines
     * of its errors ({@link PressException#FILLED}) are the lines of this text} Neither the document with Slots nor the
     * filled one is validated here: the engine validates the filled one when it converts it. It needs no converter, so
     * it is static: filling reads no resources.
     *
     * @param document the STXT text of a document with Slots; not {@code null}
     * @param data the JSON text that fills them; not {@code null}
     * @throws PressException if the document cannot be parsed ({@link PressException#DOCUMENT}), the data cannot be
     *         read ({@link PressException#DATA}) or do not fit the Slots ({@link PressException#DOCUMENT}, at the line
     *         of the Slot)
     */
    public static String fill(String document, String data) {
        Objects.requireNonNull(data, "data");
        List<Node> nodes;
        try {
            nodes = new Parser().parse(Objects.requireNonNull(document, "document"));
        } catch (ParseException e) {
            throw fromParser(e, PressException.DOCUMENT);
        }
        try {
            return NodeWriter.toSTXT(new FillJson().fill(nodes, data));
        } catch (LimitException e) {
            // The only limits of the fill are those of the JSON reader.
            throw fromParser(e, PressException.DATA);
        } catch (ParseException e) {
            throw fromParser(e, "JSON_NOT_VALID".equals(e.getCode()) ? PressException.DATA : PressException.DOCUMENT);
        }
    }

    /**
     * {@return the text a job converts: its document filled with its data, or the document as it is without data} It is
     * what {@code ?format=stxt} answers. Like {@link #fill}, it needs no converter and validates nothing.
     *
     * @param job the job; not {@code null}
     * @throws PressException as {@link #fill}, if the job has data
     */
    public static String filled(Job job) {
        return job.data() == null ? job.document() : fill(job.document(), job.data());
    }

    /** A job ready to lay out: the converter with its styles and options, and its document, filled if it had data. */
    private record Prepared(Converter converter, InlineNode document, String filled) {
    }

    private Prepared prepare(Job job) {
        String text = filled(Objects.requireNonNull(job, "job"));
        // Only with data is the text another one, whose lines are those of the errors of FILLED.
        String filled = job.data() == null ? null : text;
        Converter converter;
        InlineNode document;
        try {
            converter = forJob(job);
            document = new DocumentReader().read(text);
        } catch (StxtPdfException e) {
            throw fromEngine(e, job, filled);
        }
        return new Prepared(converter, document, filled);
    }

    /** {@return the converter of one job: the shared one with the styles and the options of the job} */
    private Converter forJob(Job job) {
        Options options = job.options();
        Converter.Builder builder = shared.toBuilder()
                .pdfUa(options.pdfUa())
                .debug(options.debug())
                .exclude(options.excluded().toArray(new Check[0]));
        if (options.pdfA() != null) {
            builder.pdfA(options.pdfA());
        }
        for (Job.Styles styles : job.styles()) {
            builder.styles(styles.text());
        }
        return builder.build();
    }

    /**
     * {@return an error of the engine as one of the job} The engine numbers the separate styles documents by their
     * place, from 1, which here is their name; a line that is not of the styles is of the document, or of the filled
     * document if the job had data.
     */
    private static PressException fromEngine(StxtPdfException e, Job job, String filled) {
        int line = e.line();
        String source;
        if (e.inStyles()) {
            int place = e.stylesDocument();
            source = place >= 1 && place <= job.styles().size() ? job.styles().get(place - 1).name() : "styles " + place;
        } else if (line == StxtPdfException.NO_LINE) {
            source = null;
        } else {
            source = filled == null ? PressException.DOCUMENT : PressException.FILLED;
        }
        return new PressException(e.kind(), e.code(), line, source,
                PressException.FILLED.equals(source) ? filled : null, e.getMessage(), e);
    }

    /** {@return an error of the parser of {@code stxt-core} or of the fill, whose line is of {@code source}} */
    private static PressException fromParser(ParseException e, String source) {
        Kind kind = e instanceof LimitException ? Kind.LIMIT : Kind.INVALID_DOCUMENT;
        return new PressException(kind, e.getCode(), e.getLine(), source, null, e.getMessage(), e);
    }
}
