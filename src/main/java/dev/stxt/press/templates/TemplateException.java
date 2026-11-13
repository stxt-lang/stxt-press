package dev.stxt.press.templates;

import java.io.IOException;

/**
 * A template or a document of a folder that cannot be given or saved: its path is not that of one
 * ({@link #NOT_FOUND}, {@link #DOCUMENT_NOT_FOUND}), one of its files is a link that leaves the folder
 * ({@link #LINK_OUTSIDE}, {@link #DOCUMENT_LINK_OUTSIDE}), or its files changed since the version a save starts from
 * ({@link #FILES_CHANGED}). The first and the last are of the request (a {@code 404} and a {@code 409}), the second of
 * the server (a {@code 500}), and none says where a link goes.
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
    /** The files of a template or a document are not those of the version a save starts from. */
    public static final String FILES_CHANGED = "FILES_CHANGED";

    /** What went wrong, which decides whose fault it is. */
    public enum Reason {
        /** The path is not that of a template or a document: the request. */
        NOT_FOUND,
        /** A file is a link that leaves the folder: the server. */
        LINK_OUTSIDE,
        /** The files changed since the version a save starts from: someone else saved first. */
        CHANGED
    }

    /** The stable code of the error, in capitals. */
    private final String code;
    /** The path of the template or the document, as the request gave it. */
    private final String path;
    private final Reason reason;

    TemplateException(String code, Reason reason, String path, String message) {
        super(message);
        this.code = code;
        this.reason = reason;
        this.path = path;
    }

    /**
     * {@return the stable code of the error: {@link #NOT_FOUND} or {@link #LINK_OUTSIDE} for a template,
     * {@link #DOCUMENT_NOT_FOUND} or {@link #DOCUMENT_LINK_OUTSIDE} for a document, {@link #FILES_CHANGED} for both}
     */
    public String code() {
        return code;
    }

    /** {@return the path of the template or the document, as the request gave it} */
    public String path() {
        return path;
    }

    /** {@return what went wrong} */
    public Reason reason() {
        return reason;
    }

    /**
     * {@return whether the path is not that of a template or a document ({@link #NOT_FOUND},
     * {@link #DOCUMENT_NOT_FOUND}), and not a link that leaves the folder}
     */
    public boolean notFound() {
        return reason == Reason.NOT_FOUND;
    }
}
