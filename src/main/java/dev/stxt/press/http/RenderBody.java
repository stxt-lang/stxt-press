package dev.stxt.press.http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;

import dev.stxt.pdf.model.Check;
import dev.stxt.pdf.model.PdfA;
import dev.stxt.press.Job;
import dev.stxt.press.Options;

/**
 * The body of {@code POST /render}: the texts of the job as JSON strings, and its data as a JSON value.
 *
 * <pre>
 * {"document": "Document (dev.stxt.pdf): ...",
 *  "styles": ["Styles (dev.stxt.pdf): ...", ...],
 *  "data": {"title": "...", "lines": [...]},
 *  "options": {"pdfUa": 2, "pdfA": "A2B", "debug": true, "exclude": ["CONTRAST"]}}
 * </pre>
 *
 * <p>Only {@code document} is required. The separate styles documents are named {@code styles[1]},
 * {@code styles[2]}... in the errors, by their place. The data are written again as JSON text for the fill, with their
 * numbers as they came ({@code 1.10} stays {@code 1.10}); as they are already read, a body that is not JSON is an error
 * of the body ({@code 400}), not of the data.</p>
 */
final class RenderBody {

    private static final Set<String> FIELDS = Set.of("document", "styles", "data", "options");
    private static final Set<String> OPTIONS = Set.of("pdfUa", "pdfA", "debug", "exclude");

    private RenderBody() {
    }

    /**
     * {@return the job of a body of {@code /render}}
     *
     * @param body the body of the request
     * @param debug whether the request asks for the outlines of the boxes ({@code ?debug=true}), which adds to those of
     *        its options
     * @throws HttpError if the body is not JSON or not one of {@code /render} ({@code 400})
     */
    static Job job(byte[] body, boolean debug) throws HttpError {
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(body);
        } catch (JacksonException e) {
            throw notValid("The body is not JSON: " + e.getOriginalMessage());
        } catch (IOException e) {
            throw notValid("The body cannot be read: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw notValid("The body is not a JSON object");
        }
        known(root, FIELDS, "");

        JsonNode document = root.get("document");
        if (document == null || !document.isTextual()) {
            throw notValid("The body has no document: \"document\" is required, as a string");
        }

        List<Job.Styles> styles = new ArrayList<>();
        JsonNode list = root.get("styles");
        if (list != null && !list.isNull()) {
            if (!list.isArray()) {
                throw notValid("\"styles\" is not an array of strings");
            }
            for (JsonNode item : list) {
                if (!item.isTextual()) {
                    throw notValid("\"styles\" is not an array of strings");
                }
                styles.add(new Job.Styles("styles[" + (styles.size() + 1) + "]", item.textValue()));
            }
        }

        String data = null;
        JsonNode value = root.get("data");
        if (value != null && !value.isNull()) {
            try {
                data = Json.MAPPER.writeValueAsString(value);
            } catch (IOException e) {
                throw new IllegalStateException("A JSON value read cannot be written", e);
            }
        }

        Options options = options(root.get("options"));
        return new Job(document.textValue(), styles, data, debug ? options.withDebug(true) : options);
    }

    /** {@return the options of the body, or the default ones without {@code options}} */
    private static Options options(JsonNode node) throws HttpError {
        if (node == null || node.isNull()) {
            return Options.DEFAULT;
        }
        if (!node.isObject()) {
            throw notValid("\"options\" is not a JSON object");
        }
        known(node, OPTIONS, "options.");
        int pdfUa = Options.DEFAULT.pdfUa();
        PdfA pdfA = null;
        boolean debug = false;
        Set<Check> excluded = EnumSet.noneOf(Check.class);

        JsonNode ua = node.get("pdfUa");
        if (ua != null) {
            if (!ua.isInt()) {
                throw notValid("\"options.pdfUa\" is not 1 or 2");
            }
            pdfUa = ua.intValue();
        }
        JsonNode a = node.get("pdfA");
        if (a != null && !a.isNull()) {
            pdfA = constant(PdfA.class, a, "options.pdfA");
        }
        JsonNode d = node.get("debug");
        if (d != null) {
            if (!d.isBoolean()) {
                throw notValid("\"options.debug\" is not a boolean");
            }
            debug = d.booleanValue();
        }
        JsonNode exclude = node.get("exclude");
        if (exclude != null) {
            if (!exclude.isArray()) {
                throw notValid("\"options.exclude\" is not an array of checks");
            }
            for (JsonNode check : exclude) {
                excluded.add(constant(Check.class, check, "options.exclude"));
            }
        }
        try {
            return new Options(pdfUa, pdfA, debug, excluded);
        } catch (IllegalArgumentException e) {
            throw notValid("\"options\" are not valid: " + e.getMessage());
        }
    }

    /** {@return the constant of {@code type} that {@code node} names} */
    private static <E extends Enum<E>> E constant(Class<E> type, JsonNode node, String field) throws HttpError {
        if (node.isTextual()) {
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(node.textValue())) {
                    return constant;
                }
            }
        }
        List<String> names = new ArrayList<>();
        for (E constant : type.getEnumConstants()) {
            names.add(constant.name());
        }
        throw notValid("\"" + field + "\" is " + node + ", not one of " + String.join(", ", names));
    }

    /** That {@code object} has no field but {@code fields}. */
    private static void known(JsonNode object, Set<String> fields, String prefix) throws HttpError {
        for (Map.Entry<String, JsonNode> field : object.properties()) {
            String name = field.getKey();
            if (!fields.contains(name)) {
                throw notValid("Unknown field \"" + prefix + name + "\"");
            }
        }
    }

    private static HttpError notValid(String message) {
        return new HttpError(400, HttpError.BODY_NOT_VALID, message);
    }
}
