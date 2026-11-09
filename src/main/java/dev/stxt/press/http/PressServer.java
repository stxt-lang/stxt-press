package dev.stxt.press.http;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.sun.net.httpserver.HttpServer;

import dev.stxt.pdf.convert.Converter;
import dev.stxt.press.templates.Templates;

/**
 * The HTTP service (ROADMAP-PDF 2.4), on the {@link HttpServer} of the JDK: the routes of {@link Routes}, behind its
 * {@link Gate}, on the shared {@link Converter} of the server and its templates folder. The requests are answered by a
 * fixed pool of threads, and the conversions that run at once are limited by
 * {@link ServerConfig#concurrentConversions()}.
 *
 * <p>It has no TLS or users: for that, it is deployed behind what the one who runs it puts in front. Without a token, a
 * server on a loopback address answers only to the local names in {@code Host} ({@link Gate#localHosts}).</p>
 */
public final class PressServer implements AutoCloseable {

    private static final Logger LOG = System.getLogger("dev.stxt.press");

    /** The property of the JDK with the time a request has to arrive whole, its headers and its body, in seconds. */
    static final String MAX_REQUEST_TIME = "sun.net.httpserver.maxReqTime";
    /** The property of the JDK with the time from a request read whole to its response sent whole, in seconds. */
    static final String MAX_RESPONSE_TIME = "sun.net.httpserver.maxRspTime";
    /** The time a request has to arrive whole: 2 MiB, the largest body by default, at 70 KB/s. */
    static final long REQUEST_SECONDS = 30;
    /**
     * What a response has on top of the timeout of a conversion: to read the template and fill it, to create the
     * converter of a lazy server (reading the resources) and to send the PDF.
     */
    static final long RESPONSE_MARGIN_SECONDS = 30;

    private final HttpServer server;
    private final ExecutorService executor;
    private final Gate gate;
    private final Routes routes;

    private PressServer(HttpServer server, ExecutorService executor, Gate gate, Routes routes) {
        this.server = server;
        this.executor = executor;
        this.gate = gate;
        this.routes = routes;
    }

    /**
     * Starts a server.
     *
     * @param shared the converter of the server, with its resources and its limits; not {@code null}
     * @param templates the templates folder, or {@code null} if the server has none ({@code /print} is then always
     *        {@code 404})
     * @param config where it listens and its token, routes and limits; not {@code null}
     * @return the server, already answering
     * @throws IOException if it cannot listen on the address of {@code config}
     */
    public static PressServer start(Converter shared, Templates templates, ServerConfig config) throws IOException {
        Objects.requireNonNull(shared, "shared");
        return startLazily(() -> shared, templates, config);
    }

    /**
     * Starts a server whose converter is created by the first request that needs it ({@code Lazy init} of
     * {@code server.stxt}): a conversion or {@code /fonts}; not a fill ({@code ?format=stxt}), which reads no resources.
     * If creating it fails, that request is answered with the error and the next one tries again.
     *
     * @param shared what creates the converter of the server, with its resources and its limits; not {@code null}
     * @param templates the templates folder, or {@code null} if the server has none
     * @param config where it listens and its token, routes and limits; not {@code null}
     * @return the server, already answering
     * @throws IOException if it cannot listen on the address of {@code config}
     */
    public static PressServer startLazily(Supplier<Converter> shared, Templates templates, ServerConfig config)
            throws IOException {
        return start(shared, templates, config, false);
    }

    /**
     * Starts a server that answers every request, {@code /health} too, with {@code 503} ({@code SERVER_STARTING}) until
     * {@link #warmUp()} ends ({@code Warm up} of {@code server.stxt}). It listens from now, so that an address in use is
     * seen before the warm up.
     *
     * @param shared what creates the converter of the server, which {@link #warmUp()} calls; not {@code null}
     * @param templates the templates folder, or {@code null} if the server has none
     * @param config where it listens and its token, routes and limits; not {@code null}
     * @return the server, answering {@code 503}
     * @throws IOException if it cannot listen on the address of {@code config}
     */
    public static PressServer startWarmingUp(Supplier<Converter> shared, Templates templates, ServerConfig config)
            throws IOException {
        return start(shared, templates, config, true);
    }

    /**
     * Sets the time limits of the {@link HttpServer} of the JDK in the system properties of this JVM, each one only if
     * it is not given already ({@code -D}): {@link #timeLimits}. Without them the JDK has none, and a client that sends
     * the headers of a request and not its body, or sends them by halves, keeps a thread of the pool until it closes the
     * connection: as many such clients as threads leave the server without answering, {@code /health} too. With them,
     * the JDK closes the connection of a request that has not arrived whole in time, and of a response that has not been
     * sent whole in time, and the thread is free again.
     *
     * <p>The JDK reads them once, when its first server is created, so this goes before it, in the process that runs
     * the server ({@code start}); in a JVM that already has a server it changes nothing. A library that only creates a
     * server does not call it: the system properties are of whoever owns the JVM.</p>
     *
     * @param timeout the time a conversion has ({@link ServerConfig#timeout()}); positive
     */
    public static void limitTimes(Duration timeout) {
        timeLimits(timeout).forEach((property, value) -> {
            if (System.getProperty(property) == null) {
                System.setProperty(property, value);
            }
        });
    }

    /**
     * {@return the time limits of {@link #limitTimes}, by property, in seconds} A request has {@value #REQUEST_SECONDS}
     * seconds to arrive whole; the response, {@code timeout} and {@value #RESPONSE_MARGIN_SECONDS} more, so that a
     * conversion in time is never cut. A request without body is read whole with its headers, and its response counts
     * from then.
     *
     * @param timeout the time a conversion has; positive
     */
    static Map<String, String> timeLimits(Duration timeout) {
        long response = timeout.getSeconds() + (timeout.getNano() > 0 ? 1 : 0) + RESPONSE_MARGIN_SECONDS;
        Map<String, String> limits = new LinkedHashMap<>();
        limits.put(MAX_REQUEST_TIME, Long.toString(REQUEST_SECONDS));
        // A timeout too long for a long is no limit: the JDK takes a negative number as none.
        limits.put(MAX_RESPONSE_TIME, Long.toString(response < 0 ? -1 : response));
        return limits;
    }

    private static PressServer start(Supplier<Converter> shared, Templates templates, ServerConfig config,
            boolean starting) throws IOException {
        Objects.requireNonNull(shared, "shared");
        Objects.requireNonNull(config, "config");
        HttpServer server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        Routes routes = new Routes(shared, templates, config);
        // The address it got, not the name of the configuration, says whether the server is only local.
        Gate gate = new Gate(routes, Gate.localHosts(config, server.getAddress().getAddress()));
        gate.starting(starting);
        // Enough threads to answer a 503 or a /health while every conversion is running.
        AtomicInteger count = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(4, 2 * config.concurrentConversions()),
                task -> new Thread(task, "stxt-press-http-" + count.incrementAndGet()));
        server.setExecutor(executor);
        server.createContext("/", gate);
        server.start();
        return new PressServer(server, executor, gate, routes);
    }

    /** {@return the gate of the server, with the requests in progress, for the tests} */
    Gate gate() {
        return gate;
    }

    /**
     * Warms the server up and then answers the requests: creates its converter, reading the whole resources folder, and
     * converts every template with its sample data, to PDF and to SVG, throwing the output away; without templates, a
     * small document. A template that cannot be converted is a warning in the log, and the server answers all the same:
     * its error is that of {@code /print}. For a template without sample data it is only information, since a template
     * with Slots needs the data of the request.
     *
     * @throws dev.stxt.pdf.model.StxtPdfException if the converter cannot be created: its resources; the server keeps
     *         answering {@code 503}, and whoever started it closes it
     */
    public void warmUp() {
        LOG.log(Level.INFO, "Warming up on port " + address().getPort() + ": every request is answered with 503 until "
                + "the resources are read and the templates converted");
        WarmUp.Result result = routes.warmUp();
        for (WarmUp.Failure failure : result.failures()) {
            String what = failure.path() == null ? "The warm up document" : "The template " + failure.path();
            LOG.log(failure.withData() ? Level.WARNING : Level.INFO, what + " could not be converted in the warm up"
                    + (failure.withData() ? "" : " (it has no sample data)") + ": " + failure.error());
        }
        LOG.log(Level.INFO, "Warmed up in " + result.elapsed().toMillis() + " ms: " + result.converted() + " converted, "
                + result.failures().size() + " not");
        gate.starting(false);
    }

    /** {@return the address it listens on, with the port it got if the configuration asked for any free one} */
    public InetSocketAddress address() {
        return server.getAddress();
    }

    /** Stops answering, without waiting for the requests in progress. */
    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    /**
     * Answers new requests with {@code 503} and waits up to {@code grace} for those in progress, then stops: the
     * shutdown of {@code start} on {@code SIGTERM} and Ctrl+C. Without requests in progress it stops at once.
     *
     * @param grace how long the requests in progress have, such as the timeout of a conversion; not negative
     */
    public void close(Duration grace) {
        // Not HttpServer.stop(seconds), which on Java 17 waits all the seconds even without requests: see Gate.drain.
        gate.drain(grace.isNegative() ? Duration.ZERO : grace);
        server.stop(0);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
