package dev.stxt.press.config;

import java.nio.file.Path;

/**
 * A configuration the server cannot start with: a {@code server.stxt} that cannot be found, is not valid STXT, does not
 * follow the schema {@code dev.stxt.press} or has a value that cannot be used. Its message starts with the file and the
 * line ({@code /srv/press/server.stxt:4: ...}), for the output of the start.
 *
 * <p>The code is one of the constants of this class or, for an error of the syntax or of the schema, that of
 * {@code stxt-core} ({@code INVALID_LINE}, {@code CHILD_NOT_DECLARED}, {@code INVALID_VALUE}...).</p>
 */
public final class ConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    /** The file that {@code --config} or {@code STXT_PRESS_CONFIG} gives does not exist or is not a file. */
    public static final String NOT_FOUND = "CONFIG_NOT_FOUND";
    /** The file does not have exactly one root node, {@code Server (dev.stxt.press):}. */
    public static final String ROOT = "CONFIG_ROOT";
    /** A value the schema accepts and the server cannot use: a port above 65535, a limit of 0... */
    public static final String VALUE_NOT_VALID = "CONFIG_VALUE_NOT_VALID";
    /** Two nodes that cannot go together: {@code Resource folder} and {@code Font folder}, {@code PDF UA: 2} and {@code PDF A}... */
    public static final String CONFLICT = "CONFIG_CONFLICT";
    /** The variable {@code Token env} names is not set, or is empty. */
    public static final String TOKEN_NOT_SET = "CONFIG_TOKEN_NOT_SET";
    /** A folder of the file does not exist or is not a folder. */
    public static final String FOLDER_NOT_FOUND = "CONFIG_FOLDER_NOT_FOUND";

    /** The line of an error that is not of any line of the file. */
    public static final int NO_LINE = 0;

    private final String code;
    /** Not serialized: {@link Path} is not serializable. */
    private final transient Path file;
    private final int line;

    ConfigException(String code, Path file, int line, String message) {
        this(code, file, line, message, null);
    }

    ConfigException(String code, Path file, int line, String message, Throwable cause) {
        super(file + (line > NO_LINE ? ":" + line : "") + ": " + message, cause);
        this.code = code;
        this.file = file;
        this.line = line;
    }

    /** {@return the code of the error} */
    public String code() {
        return code;
    }

    /** {@return the configuration file, as it was given or found} */
    public Path file() {
        return file;
    }

    /** {@return the line of the file, from 1, or {@link #NO_LINE}} */
    public int line() {
        return line;
    }
}
