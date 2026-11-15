package dev.stxt.press.http;

import java.util.Map;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON of the service: every JSON response, and the bodies of {@code /render} ({@link RenderBody}) and of a save
 * ({@link SaveBody}), with what reading them has in common. An error of a body is a {@code 400} {@code BODY_NOT_VALID}.
 */
final class Json {

    /** The numbers are kept exact, as the fill of the Slots writes them: {@code 1.10} stays {@code 1.10}. */
    static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
            .build();

    private Json() {
    }

    /**
     * {@return the body of a request, which has to be a JSON object}
     *
     * @throws HttpError if it is not JSON, or not an object
     */
    static JsonNode object(byte[] body) throws HttpError {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JacksonException e) {
            throw notValid("The body is not JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw notValid("The body is not a JSON object");
        }
        return root;
    }

    /**
     * That {@code object} has no field but {@code fields}.
     *
     * @param prefix what goes before the name of a field in the message: {@code options.} for those of {@code options}
     * @throws HttpError for the first field that is not one of {@code fields}
     */
    static void known(JsonNode object, Set<String> fields, String prefix) throws HttpError {
        for (Map.Entry<String, JsonNode> field : object.properties()) {
            if (!fields.contains(field.getKey())) {
                throw notValid("Unknown field \"" + prefix + field.getKey() + "\"");
            }
        }
    }

    /**
     * {@return the string of the field {@code name} of the body, which it has to have}
     *
     * @throws HttpError if the body does not have it, or it is not a string
     */
    static String required(JsonNode body, String name) throws HttpError {
        JsonNode value = body.get(name);
        if (value == null || !value.isString()) {
            throw notValid("The body has no " + name + ": \"" + name + "\" is required, as a string");
        }
        return value.stringValue();
    }

    /** {@return the error of a body that is not valid: {@code 400}, {@code BODY_NOT_VALID}} */
    static HttpError notValid(String message) {
        return new HttpError(400, HttpError.BODY_NOT_VALID, message);
    }
}
