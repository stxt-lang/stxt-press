package dev.stxt.press.http;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import dev.stxt.press.templates.Templates;

/**
 * The body of {@code PUT /files/templates/{path}} and {@code PUT /files/documents/{path}}: the texts of the files to
 * write, in the shape {@code GET} gives them.
 *
 * <pre>
 * {"document": "Document (dev.stxt.pdf): ...",
 *  "styles": [{"path": ".styles.stxt", "text": "Styles (dev.stxt.pdf): ..."}, ...],
 *  "data": "{\"title\": ...}"}
 * </pre>
 *
 * <p>Only {@code document} is required. A styles file left out is not touched, and so are the data without
 * {@code data} or with {@code null}; a document has no {@code data}. The data are the text of the file, not a JSON
 * value: they are written as they come.</p>
 */
final class SaveBody {

    private static final Set<String> FIELDS = Set.of("document", "styles", "data");
    private static final Set<String> STYLES_FIELDS = Set.of("path", "text");

    private SaveBody() {
    }

    /**
     * {@return the change of a body of a save}
     *
     * @param body the body of the request
     * @param kind whether it is of a template or of a document
     * @throws HttpError if the body is not JSON or not one of a save ({@code 400})
     */
    static Templates.Change change(byte[] body, Templates.Kind kind) throws HttpError {
        JsonNode root = Json.object(body);
        Json.known(root, FIELDS, "");
        String document = Json.required(root, "document");

        Map<String, String> styles = new LinkedHashMap<>();
        JsonNode list = root.get("styles");
        if (list != null && !list.isNull()) {
            if (!list.isArray()) {
                throw Json.notValid("\"styles\" is not an array of {\"path\", \"text\"}");
            }
            for (JsonNode item : list) {
                if (!item.isObject()) {
                    throw Json.notValid("\"styles\" is not an array of {\"path\", \"text\"}");
                }
                Json.known(item, STYLES_FIELDS, "styles[].");
                JsonNode path = item.get("path");
                JsonNode text = item.get("text");
                if (path == null || !path.isTextual() || text == null || !text.isTextual()) {
                    throw Json.notValid("Each of \"styles\" has a \"path\" and a \"text\", as strings");
                }
                if (styles.put(path.textValue(), text.textValue()) != null) {
                    throw Json.notValid("The styles file " + path.textValue() + " is twice in \"styles\"");
                }
            }
        }

        String data = null;
        JsonNode value = root.get("data");
        if (value != null && !value.isNull()) {
            if (kind != Templates.Kind.TEMPLATE) {
                throw Json.notValid("A document has no \"data\"");
            }
            if (!value.isTextual()) {
                throw Json.notValid("\"data\" is the text of the file, as a string");
            }
            data = value.textValue();
        }
        return new Templates.Change(document, styles, data);
    }
}
