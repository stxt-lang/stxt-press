package dev.stxt.press.http;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * The handler of the whole server, in front of {@link Routes}: it checks the {@code Host} of a local server, answers
 * {@code 503} while the server warms up or stops, counts the requests in progress for {@link #drain}, and answers the
 * error of a request whose response has not started.
 */
final class Gate implements HttpHandler {

    private static final Logger LOG = System.getLogger("dev.stxt.press");

    /** The names of the local machine that a server without token answers to, besides the address it listens on. */
    private static final List<String> LOCAL_HOSTS = List.of("localhost", "127.0.0.1", "[::1]");
    /** The value of a {@code Host} header: a name or an IPv4 address, or an IPv6 one in brackets, and the port. */
    private static final Pattern HOST = Pattern.compile("(\\[[0-9A-Fa-f:.]+\\]|[^:\\[\\]]+)(?::[0-9]*)?");

    private final Routes routes;
    /** The names a request may give in {@code Host}, in lowercase, or {@code null} for any: {@link #localHosts}. */
    private final Set<String> hosts;
    /** The requests being answered, for {@link #drain}. */
    private final AtomicInteger active = new AtomicInteger();
    /** What {@link #drain} waits on, notified when the last request in progress ends. */
    private final Object drained = new Object();
    /** Whether the server is stopping: every new request is answered with {@code 503}. */
    private volatile boolean closing;
    /** Whether the server is warming up: every request, {@code /health} too, is answered with {@code 503}. */
    private volatile boolean starting;

    /**
     * @param hosts the names a request may give in {@code Host}, or {@code null} for any: {@link #localHosts}
     */
    Gate(Routes routes, Set<String> hosts) {
        this.routes = routes;
        this.hosts = hosts;
    }

    /**
     * {@return the names a request may give in {@code Host} on a server with {@code config} listening on
     * {@code address}, or {@code null} for any} A server without token on a loopback address answers only to
     * {@code localhost}, {@code 127.0.0.1}, {@code [::1]} and the {@code Host} of its configuration: otherwise a page
     * of another site, open in the browser of whoever runs it, could make its own name resolve to {@code 127.0.0.1}
     * (DNS rebinding) and, being then of the same origin, read the templates and use {@code /render}. With a token that
     * page cannot do anything, since it does not have it; on another address the server is meant to be behind a proxy,
     * whose name is the one the requests give.
     */
    static Set<String> localHosts(ServerConfig config, InetAddress address) {
        if (config.token() != null || address == null || !address.isLoopbackAddress()) {
            return null;
        }
        Set<String> hosts = new LinkedHashSet<>(LOCAL_HOSTS);
        String host = config.host().toLowerCase(Locale.ROOT);
        hosts.add(host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host);
        return Collections.unmodifiableSet(hosts);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Counted before looking at closing: drain() either sees this request or this request sees closing.
        active.incrementAndGet();
        try {
            try {
                // Before anything else, /health and the 503 too: a request with a foreign Host gets nothing.
                host(exchange);
                if (closing) {
                    throw new HttpError(503, HttpError.SERVER_BUSY, "The server is stopping",
                            Map.of("Connection", "close"));
                }
                if (starting) {
                    throw new HttpError(503, HttpError.SERVER_STARTING, "The server is warming up",
                            Map.of("Retry-After", "1"));
                }
                routes.route(exchange);
            } catch (Exception e) {
                if (exchange.getResponseCode() != -1) {
                    // The response had started (a PDF being written): the exception drops the connection, so that the
                    // client does not take a part of the PDF for the whole.
                    LOG.log(Level.WARNING, "The response to " + exchange.getRequestURI() + " could not be finished", e);
                    throw e instanceof IOException io ? io : new IOException(e);
                }
                Responses.fail(exchange, e);
            }
            exchange.close();
        } finally {
            if (active.decrementAndGet() == 0) {
                synchronized (drained) {
                    drained.notifyAll();
                }
            }
        }
    }

    /** That the request names the server by one of {@link #hosts}, if it has to. */
    private void host(HttpExchange exchange) throws HttpError {
        if (hosts == null) {
            return;
        }
        List<String> values = exchange.getRequestHeaders().get("Host");
        if (values != null && values.size() == 1) {
            Matcher matcher = HOST.matcher(values.get(0).trim());
            if (matcher.matches() && hosts.contains(matcher.group(1).toLowerCase(Locale.ROOT))) {
                return;
            }
        }
        throw new HttpError(403, HttpError.HOST_NOT_ALLOWED, "A server without token on a local address answers only to "
                + "the Host " + String.join(", ", hosts) + ": with another name it needs a token (Token env in "
                + "server.stxt)");
    }

    /**
     * Answers every new request with {@code 503} and waits up to {@code grace} for those in progress, so that the server
     * can stop without cutting them. {@code HttpServer.stop(seconds)} does this too, but on Java 17 it waits all the
     * seconds even without requests (fixed in later JDKs), so a Ctrl+C took the whole timeout of a conversion.
     *
     * @return whether every request finished in time
     */
    boolean drain(Duration grace) {
        closing = true;
        long deadline = System.nanoTime() + grace.toNanos();
        synchronized (drained) {
            try {
                while (active.get() > 0) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        return false;
                    }
                    TimeUnit.NANOSECONDS.timedWait(drained, left);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** {@return the requests being answered} */
    int active() {
        return active.get();
    }

    /** Whether the server is warming up, and answers every request with {@code 503} until it is not. */
    void starting(boolean starting) {
        this.starting = starting;
    }
}
