package dev.stxt.press;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import dev.stxt.pdf.model.Check;
import dev.stxt.pdf.model.PdfA;

/**
 * The options of a job: the ones of the engine that change from one request to another. Everything else (the resources,
 * the limits) belongs to the shared converter of {@link Press}.
 *
 * @param pdfUa the part of PDF/UA the PDF complies with: 1 (PDF 1.7) or 2 (PDF 2.0)
 * @param pdfA the PDF/A the PDF complies with besides, or {@code null} for none; not with {@code pdfUa} 2
 * @param debug whether the outlines of the boxes are painted
 * @param excluded the checks that are not made; not {@code null}
 */
public record Options(int pdfUa, PdfA pdfA, boolean debug, Set<Check> excluded) {

    /** PDF/UA-1, no PDF/A, no outlines and every check: the defaults of the engine. */
    public static final Options DEFAULT = new Options(1, null, false, Set.of());

    /**
     * @throws IllegalArgumentException with a {@code pdfUa} other than 1 or 2, or with {@code pdfUa} 2 and a
     *         {@code pdfA}: PDF/UA-2 is a PDF 2.0 file and PDF/A-2 and PDF/A-3 are PDF 1.7 ones
     */
    public Options {
        if (pdfUa != 1 && pdfUa != 2) {
            throw new IllegalArgumentException("PDF/UA is part 1 or 2, not " + pdfUa);
        }
        if (pdfUa == 2 && pdfA != null) {
            throw new IllegalArgumentException("PDF/UA-2 is a PDF 2.0 file and " + pdfA
                    + " a PDF 1.7 one: a PDF cannot comply with both");
        }
        Objects.requireNonNull(excluded, "excluded");
        excluded = excluded.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(excluded));
    }

    /** {@return these options with {@code pdfUa} as the part of PDF/UA} */
    public Options withPdfUa(int pdfUa) {
        return new Options(pdfUa, pdfA, debug, excluded);
    }

    /** {@return these options with {@code pdfA} as the PDF/A, or none with {@code null}} */
    public Options withPdfA(PdfA pdfA) {
        return new Options(pdfUa, pdfA, debug, excluded);
    }

    /** {@return these options with the outlines of the boxes painted or not} */
    public Options withDebug(boolean debug) {
        return new Options(pdfUa, pdfA, debug, excluded);
    }

    /** {@return these options without the checks {@code excluded}, instead of the ones they had} */
    public Options withExcluded(Set<Check> excluded) {
        return new Options(pdfUa, pdfA, debug, excluded);
    }

    /**
     * {@return the constant of {@code type} named {@code name}, or {@code null} if none is} For a {@link Check} or a
     * {@link PdfA} written by name, as the body of {@code /render} and the command {@code render} write them.
     */
    public static <E extends Enum<E>> E named(Class<E> type, String name) {
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(name)) {
                return constant;
            }
        }
        return null;
    }

    /** {@return the names of the constants of {@code type}, in their order and separated by commas, for a message} */
    public static String names(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
