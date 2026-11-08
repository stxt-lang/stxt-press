package dev.stxt.press.http;

import java.io.IOException;
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

    private final HttpServer server;
    private final ExecutorService executor;

    private PressServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
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
        Objects.requireNonNull(shared, "shared");
        Objects.requireNonNull(config, "config");
        Routes routes = new Routes(shared, templates, config);
        HttpServer server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        // Enough threads to answer a 503 or a /health while every conversion is running.
        AtomicInteger count = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(4, 2 * config.concurrentConversions()),
                task -> new Thread(task, "stxt-press-http-" + count.incrementAndGet()));
        server.setExecutor(executor);
        server.createContext("/", routes);
        server.start();
        return new PressServer(server, executor);
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
     * Stops answering new requests and waits up to {@code grace} for those in progress, then stops the rest: the
     * shutdown of {@code serve} on {@code SIGTERM}.
     *
     * @param grace how long the requests in progress have, such as the timeout of a conversion; not negative
     */
    public void close(Duration grace) {
        // HttpServer.stop waits for the exchanges in progress up to the seconds it is given, and refuses new ones.
        server.stop((int) Math.min(Integer.MAX_VALUE, Math.max(0, grace.toSeconds())));
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
