package dev.stxt.press.templates;

import java.io.IOException;

/**
 * A template that cannot be given: its path is not that of a template ({@link #NOT_FOUND}), or one of its files is a
 * link that leaves the templates folder ({@link #LINK_OUTSIDE}). The first is the fault of the request (a {@code 404}),
 * the second of the server (a {@code 500}), and neither says where the link goes.
 */
public final class TemplateException extends IOException {

    private static final long serialVersionUID = 1L;

    /** The path is not that of a template: its {@code .stxt} does not exist or the path is not a valid one. */
    public static final String NOT_FOUND = "TEMPLATE_NOT_FOUND";
    /** A file of the template, or a folder above it, is a symbolic link that leaves the templates folder. */
    public static final String LINK_OUTSIDE = "TEMPLATE_LINK_OUTSIDE";

    /** The stable code of the error, in capitals. */
    private final String code;
    /** The path of the template, as the request gave it. */
    private final String path;

    TemplateException(String code, String path, String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    /** {@return the stable code of the error: {@link #NOT_FOUND} or {@link #LINK_OUTSIDE}} */
    public String code() {
        return code;
    }

    /** {@return the path of the template, as the request gave it} */
    public String path() {
        return path;
    }
}
