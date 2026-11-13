package dev.stxt.press.http;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JacksonException;
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

        Map<String, String> styles = new LinkedHashMap<>();
        JsonNode list = root.get("styles");
        if (list != null && !list.isNull()) {
            if (!list.isArray()) {
                throw notValid("\"styles\" is not an array of {\"path\", \"text\"}");
            }
            for (JsonNode item : list) {
                if (!item.isObject()) {
                    throw notValid("\"styles\" is not an array of {\"path\", \"text\"}");
                }
                known(item, STYLES_FIELDS, "styles[].");
                JsonNode path = item.get("path");
                JsonNode text = item.get("text");
                if (path == null || !path.isTextual() || text == null || !text.isTextual()) {
                    throw notValid("Each of \"styles\" has a \"path\" and a \"text\", as strings");
                }
                if (styles.put(path.textValue(), text.textValue()) != null) {
                    throw notValid("The styles file " + path.textValue() + " is twice in \"styles\"");
                }
            }
        }

        String data = null;
        JsonNode value = root.get("data");
        if (value != null && !value.isNull()) {
            if (kind != Templates.Kind.TEMPLATE) {
                throw notValid("A document has no \"data\"");
            }
            if (!value.isTextual()) {
                throw notValid("\"data\" is the text of the file, as a string");
            }
            data = value.textValue();
        }
        return new Templates.Change(document.textValue(), styles, data);
    }

    /** That {@code object} has no field but {@code fields}. */
    private static void known(JsonNode object, Set<String> fields, String prefix) throws HttpError {
        for (Map.Entry<String, JsonNode> field : object.properties()) {
            if (!fields.contains(field.getKey())) {
                throw notValid("Unknown field \"" + prefix + field.getKey() + "\"");
            }
        }
    }

    private static HttpError notValid(String message) {
        return new HttpError(400, HttpError.BODY_NOT_VALID, message);
    }
}
