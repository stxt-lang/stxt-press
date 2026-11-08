/**
 * STXT Press: PDF and SVG generation from STXT layout documents ({@code dev.stxt.pdf}), as a service.
 *
 * <p>A consumer of {@code dev.stxt:stxt-pdf}, {@code dev.stxt:stxt-transform} and {@code dev.stxt:stxt-core}: it does not lay out, parse or validate
 * anything itself.
 *
 * <p>{@link dev.stxt.press.Press} does the jobs (a PDF, a preview as SVG, the fill of Slots) of a
 * {@link dev.stxt.press.Job}, and every error is a {@link dev.stxt.press.PressException} that says which input of the
 * job its line belongs to. The templates of the server are in {@link dev.stxt.press.templates}, the HTTP service in
 * {@link dev.stxt.press.http} and its configuration, {@code server.stxt}, in {@link dev.stxt.press.config}.</p>
 */
package dev.stxt.press;
