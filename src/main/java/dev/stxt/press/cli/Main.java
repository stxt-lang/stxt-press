package dev.stxt.press.cli;

import java.nio.file.Path;

/** The {@code Main-Class} of the jar: {@code java -jar stxt-press.jar <command>} ({@link Cli}). */
public final class Main {

    private Main() {
    }

    /**
     * Runs a command and exits with its code.
     *
     * @param args the command and its options
     */
    public static void main(String[] args) {
        // JSVG, which the engine uses for SVG images, draws with AWT: the service never has a display.
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
        System.exit(new Cli(System.in, System.out, System.err, System.getenv(), Path.of("").toAbsolutePath())
                .run(args));
    }
}
