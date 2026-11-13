package dev.stxt.press.templates;

import java.io.IOException;

/**
 * A template or a document of a folder that cannot be given: its path is not that of one ({@link #NOT_FOUND},
 * {@link #DOCUMENT_NOT_FOUND}), or one of its files is a link that leaves the folder ({@link #LINK_OUTSIDE},
 * {@link #DOCUMENT_LINK_OUTSIDE}). The first is the fault of the request (a {@code 404}), the second of the server (a
 * {@code 500}), and neither says where the link goes.
 */
public final class TemplateException extends IOException {

    private static final long serialVersionUID = 1L;

    /** The path is not that of a template: its {@code .stxt} does not exist or the path is not a valid one. */
    public static final String NOT_FOUND = "TEMPLATE_NOT_FOUND";
    /** A file of the template, or a folder above it, is a symbolic link that leaves the templates folder. */
    public static final String LINK_OUTSIDE = "TEMPLATE_LINK_OUTSIDE";
    /** The path is not that of a document of the documents folder. */
    public static final String DOCUMENT_NOT_FOUND = "DOCUMENT_NOT_FOUND";
    /** A file of the document, or a folder above it, is a symbolic link that leaves the documents folder. */
    public static final String DOCUMENT_LINK_OUTSIDE = "DOCUMENT_LINK_OUTSIDE";

    /** The stable code of the error, in capitals. */
    private final String code;
    /** The path of the template or the document, as the request gave it. */
    private final String path;
    /** Whether the path is not that of a template or a document, the fault of the request. */
    private final boolean notFound;

    TemplateException(String code, boolean notFound, String path, String message) {
        super(message);
        this.code = code;
        this.notFound = notFound;
        this.path = path;
    }

    /**
     * {@return the stable code of the error: {@link #NOT_FOUND} or {@link #LINK_OUTSIDE} for a template,
     * {@link #DOCUMENT_NOT_FOUND} or {@link #DOCUMENT_LINK_OUTSIDE} for a document}
     */
    public String code() {
        return code;
    }

    /** {@return the path of the template or the document, as the request gave it} */
    public String path() {
        return path;
    }

    /**
     * {@return whether the path is not that of a template or a document ({@link #NOT_FOUND},
     * {@link #DOCUMENT_NOT_FOUND}), and not a link that leaves the folder}
     */
    public boolean notFound() {
        return notFound;
    }
}
