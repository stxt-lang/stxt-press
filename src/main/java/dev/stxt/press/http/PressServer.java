package dev.stxt.press.http;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.time.Duration;
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
 * The HTTP service (ROADMAP-PDF 2.4), on the {@link HttpServer} of the JDK: the routes of {@link Routes} on the shared
 * {@link Converter} of the server and its templates folder. The requests are answered by a fixed pool of threads, and
 * the conversions that run at once are limited by {@link ServerConfig#concurrentConversions()}.
 *
 * <p>It has no TLS or users: for that, it is deployed behind what the one who runs it puts in front.</p>
 */
public final class PressServer implements AutoCloseable {

    private static final Logger LOG = System.getLogger("dev.stxt.press");

    private final HttpServer server;
    private final ExecutorService executor;
    private final Routes routes;

    private PressServer(HttpServer server, ExecutorService executor, Routes routes) {
        this.server = server;
        this.executor = executor;
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
     * {@code server.stxt}): a conversion, {@code /fonts} or a fill. If creating it fails, that request is answered with
     * the error and the next one tries again.
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

    private static PressServer start(Supplier<Converter> shared, Templates templates, ServerConfig config,
            boolean starting) throws IOException {
        Objects.requireNonNull(shared, "shared");
        Objects.requireNonNull(config, "config");
        Routes routes = new Routes(shared, templates, config);
        routes.starting(starting);
        HttpServer server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        // Enough threads to answer a 503 or a /health while every conversion is running.
        AtomicInteger count = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(4, 2 * config.concurrentConversions()),
                task -> new Thread(task, "stxt-press-http-" + count.incrementAndGet()));
        server.setExecutor(executor);
        server.createContext("/", routes);
        server.start();
        return new PressServer(server, executor, routes);
    }

    /** {@return the routes of the server, for the tests} */
    Routes routes() {
        return routes;
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
        routes.starting(false);
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
        // Not HttpServer.stop(seconds), which on Java 17 waits all the seconds even without requests: see Routes.drain.
        routes.drain(grace.isNegative() ? Duration.ZERO : grace);
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
