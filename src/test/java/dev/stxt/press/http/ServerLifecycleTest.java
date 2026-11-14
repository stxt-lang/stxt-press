package dev.stxt.press.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import dev.stxt.pdf.model.Limits;
import dev.stxt.press.Options;
import dev.stxt.press.Press;
import dev.stxt.press.templates.Templates;

/**
 * The life of a server of the test ({@link ServerFixture}): how it starts (the kind of each folder, its configuration,
 * a lazy converter), its warm up, its shutdown, the conversions that do not fit, the time limits of a request and the
 * limits of a body and of the engine.
 */
class ServerLifecycleTest extends ServerFixture {

    @Test
    void eachFolderIsOfItsKind() {
        assertThrows(IllegalArgumentException.class,
                () -> PressServer.start(SHARED, documents, null, ServerConfig.DEFAULT.withAddress("127.0.0.1", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> PressServer.start(SHARED, null, templates, ServerConfig.DEFAULT.withAddress("127.0.0.1", 0)));
    }

    /** A server that warms up answers every request with 503, /health too, until the warm up ends. */
    @Test
    void aServerThatWarmsUpAnswers503UntilItEnds() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server = PressServer.startWarmingUp(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return SHARED;
        }, templates, documents, ServerConfig.DEFAULT.withAddress("127.0.0.1", 0));
        PressServer warming = server;
        Thread warmUp = new Thread(warming::warmUp);
        warmUp.start();

        HttpResponse<byte[]> health = get("/health");
        error(health, 503, HttpError.SERVER_STARTING);
        assertEquals("1", health.headers().firstValue("Retry-After").orElse(null));
        error(get("/print/hello"), 503, HttpError.SERVER_STARTING);
        error(get("/"), 503, HttpError.SERVER_STARTING);

        release.countDown();
        warmUp.join(60_000);
        assertFalse(warmUp.isAlive());
        assertEquals(200, get("/health").statusCode());
        assertEquals(200, get("/print/hello").statusCode());
    }

    /**
     * The warm up converts every template with its sample data and every document; one that fails is reported, and that
     * of a template without sample data says so, since a template with Slots needs the data of the request.
     */
    @Test
    void theWarmUpConvertsEveryTemplateAndDocument() throws Exception {
        Path folder = templates.folder();
        Files.writeString(folder.resolve("broken.stxt"), "Document (dev.stxt.pdf):\n\tColour: red\n");
        Files.writeString(folder.resolve("broken.json"), "{}");
        // A template without sample data: its failure is only information.
        Files.writeString(folder.resolve("plain.stxt"), "Document (dev.stxt.pdf):\n\tColour: red\n");

        // A document that fails is always a warning: it has no data to wait for.
        Files.writeString(documents.folder().resolve("broken.stxt"), "Document (dev.stxt.pdf):\n\tColour: red\n");

        WarmUp.Result result = WarmUp.run(new Press(SHARED), templates, documents, "IBM Plex Sans", Options.DEFAULT,
                Duration.ofSeconds(30));

        assertEquals(4, result.converted(), result::toString);
        assertEquals(List.of("broken", "plain", "broken"), result.failures().stream().map(WarmUp.Failure::path).toList());
        assertEquals(List.of(Templates.Kind.TEMPLATE, Templates.Kind.TEMPLATE, Templates.Kind.DOCUMENT),
                result.failures().stream().map(WarmUp.Failure::kind).toList());
        assertTrue(result.failures().get(0).withData());
        assertFalse(result.failures().get(1).withData());
        assertTrue(result.failures().get(2).withData());
        // As the command line writes it: the filled document of a template with sample data, and the file of one
        // without them or of a document.
        assertTrue(result.failures().get(0).error().startsWith("the filled document:2: NODE_NOT_DEFINED_IN_SCHEMA: "),
                result::toString);
        assertTrue(result.failures().get(1).error().startsWith(folder.resolve("plain.stxt") + ":2: "
                + "NODE_NOT_DEFINED_IN_SCHEMA: "), result::toString);
        assertTrue(result.failures().get(2).error().startsWith(documents.folder().resolve("broken.stxt") + ":2: "),
                result::toString);

        WarmUp.Result none = WarmUp.run(new Press(SHARED), null, null, "IBM Plex Sans", Options.DEFAULT,
                Duration.ofSeconds(30));
        assertEquals(1, none.converted(), none::toString);
        assertEquals(List.of(), none.failures());
    }

    /**
     * The shutdown of {@code start} (SIGTERM, Ctrl+C): at once without requests, and with one in progress, it waits for
     * it and answers the new ones with {@code 503}.
     */
    @Test
    void closingWaitsOnlyForTheRequestsInProgress() throws Exception {
        start(ServerConfig.DEFAULT);
        long started = System.nanoTime();
        server.close(Duration.ofSeconds(30));
        server = null;
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5, "without requests it stops at once");

        start(ServerConfig.DEFAULT);
        PressServer closing = server;
        byte[] body = "{\"document\": \"Document (dev.stxt.pdf):\"}".getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", closing.address().getPort())) {
            // A request whose body has not all arrived: it is in progress until the rest comes.
            OutputStream out = socket.getOutputStream();
            out.write(("POST /render?format=stxt HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n"
                    + "Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body, 0, 10);
            out.flush();
            long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (closing.gate().active() == 0 && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertEquals(1, closing.gate().active());

            started = System.nanoTime();
            Thread close = new Thread(() -> closing.close(Duration.ofSeconds(30)));
            close.start();
            HttpResponse<byte[]> refused = get("/health");
            while (refused.statusCode() == 200 && System.nanoTime() < until) {
                refused = get("/health");
            }
            error(refused, 503, HttpError.SERVER_BUSY);
            assertTrue(close.isAlive(), "it waits for the request in progress");

            out.write(body, 10, body.length - 10);
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("HTTP/1.1 200 OK", in.readLine());
            close.join(10_000);
            assertFalse(close.isAlive(), "it stops when the request in progress ends");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 10);
        } finally {
            server = null;
        }
    }

    /**
     * The shutdown with a request that does not end in time: {@code close(grace)} returns when the grace runs out, and
     * the request is cut without a response.
     */
    @Test
    void closingCutsWhatDoesNotEndInTime() throws Exception {
        start(ServerConfig.DEFAULT);
        PressServer closing = server;
        try (Socket socket = new Socket("127.0.0.1", closing.address().getPort())) {
            // A body that never arrives whole: the request is in progress until the JDK closes it, after
            // sun.net.httpserver.maxReqTime (3 s in the tests).
            OutputStream out = socket.getOutputStream();
            out.write(("POST /render HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n"
                    + "Content-Length: 100\r\n\r\n{").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (closing.gate().active() == 0 && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertEquals(1, closing.gate().active());

            long started = System.nanoTime();
            closing.close(Duration.ofMillis(300));
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            assertTrue(took.toMillis() >= 300, "it waits for the request up to the grace: " + took);
            // Well before the JDK would close the request itself.
            assertTrue(took.toMillis() < 2500, "it stops when the grace runs out: " + took);

            socket.setSoTimeout(5000);
            try {
                assertEquals(-1, socket.getInputStream().read(), "the request is cut without a response");
            } catch (IOException e) {
                // Reset: cut too.
            }
        } finally {
            server = null;
        }
    }

    /**
     * With every conversion that can run at once running, a new one is answered at once with {@code 503} and
     * {@code Retry-After}: there is no queue. A fill and {@code /health}, which are no conversions, answer all the
     * same. The test takes the permits instead of running conversions, which at once would be fragile.
     */
    @Test
    void aConversionThatDoesNotFitIsAnswered503() throws Exception {
        start(ServerConfig.DEFAULT);
        Semaphore permits = server.conversions().permits();
        int taken = permits.drainPermits();
        assertEquals(ServerConfig.DEFAULT.concurrentConversions(), taken);
        try {
            HttpResponse<byte[]> busy = post("/render", render(DOCUMENT, Map.of()));
            error(busy, 503, HttpError.SERVER_BUSY);
            assertEquals("1", busy.headers().firstValue("Retry-After").orElse(null));
            error(get("/print/hello?format=svg"), 503, HttpError.SERVER_BUSY);

            HttpResponse<byte[]> filled = get("/print/invoices/invoice?format=stxt");
            assertEquals(200, filled.statusCode(), () -> text(filled));
            assertEquals(200, get("/health").statusCode());
        } finally {
            permits.release(taken);
        }
        assertEquals(taken, permits.availablePermits(), "a conversion refused gives back no permit it did not take");

        HttpResponse<byte[]> pdf = post("/render", render(DOCUMENT, Map.of()));
        assertEquals(200, pdf.statusCode(), () -> text(pdf));
        assertTrue(isPdf(pdf.body()));
    }

    /**
     * A client that sends the headers of a request and not its body keeps a thread of the pool while it waits for it.
     * As many as the threads leave the server without answering, {@code /health} too, until the JDK closes their
     * connections: after {@code sun.net.httpserver.maxReqTime}, which the pom sets to 3 s for the tests.
     */
    @Test
    void aClientThatDoesNotSendTheBodyLosesItsThread() throws Exception {
        assertEquals("3", System.getProperty(PressServer.MAX_REQUEST_TIME), "set by the argLine of surefire");
        // One conversion at once: the pool has its 4 threads, the least.
        start(ServerConfig.DEFAULT.withLimits(2 * 1024 * 1024, Duration.ofSeconds(30), 1));
        List<Socket> slow = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                Socket socket = new Socket("127.0.0.1", server.address().getPort());
                slow.add(socket);
                socket.getOutputStream().write(("POST /render HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                        + "Content-Type: application/json\r\nContent-Length: 100\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            }
            long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (server.gate().active() < 4 && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertEquals(4, server.gate().active(), "every thread waits for a body");
            assertThrows(HttpTimeoutException.class, () -> CLIENT.send(request("/health")
                    .timeout(Duration.ofMillis(500)).GET().build(), BodyHandlers.ofByteArray()));

            HttpResponse<byte[]> health = null;
            while (health == null && System.nanoTime() < until) {
                try {
                    health = CLIENT.send(request("/health").timeout(Duration.ofSeconds(2)).GET().build(),
                            BodyHandlers.ofByteArray());
                } catch (IOException e) {
                    // Still without threads, or the JDK closed this one too while it waited for a thread.
                }
            }
            assertTrue(health != null && health.statusCode() == 200, "the server answers again");
            for (Socket socket : slow) {
                // Closed by the server: no response, since there is no one to give it to.
                socket.setSoTimeout(5000);
                try {
                    assertEquals(-1, socket.getInputStream().read());
                } catch (IOException e) {
                    // Reset: the 100 bytes it was waiting for never came.
                }
            }
        } finally {
            for (Socket socket : slow) {
                socket.close();
            }
        }
    }

    /**
     * The time limits that {@code start} sets: the request has a fixed time, and the response the timeout of a
     * conversion and a margin, so that a conversion in time is never cut.
     */
    @Test
    void theTimeLimitsFollowTheTimeoutOfAConversion() {
        assertEquals(Map.of(PressServer.MAX_REQUEST_TIME, "30", PressServer.MAX_RESPONSE_TIME, "60"),
                PressServer.timeLimits(Duration.ofSeconds(30)));
        assertEquals("32", PressServer.timeLimits(Duration.ofMillis(1500)).get(PressServer.MAX_RESPONSE_TIME));
        // Too long to be counted: no limit, as the JDK takes a negative number.
        assertEquals("-1", PressServer.timeLimits(Duration.ofSeconds(Long.MAX_VALUE))
                .get(PressServer.MAX_RESPONSE_TIME));
    }

    @Test
    void aBodyLargerThanTheLimitIsA413() throws Exception {
        start(ServerConfig.DEFAULT.withLimits(100, Duration.ofSeconds(30), 4));

        error(post("/render", render(DOCUMENT, Map.of())), 413, HttpError.BODY_TOO_LARGE);
    }

    @Test
    void aLimitOfTheEngineIsA413() throws Exception {
        start(SHARED.toBuilder().limits(Limits.DEFAULT.withMaxPages(1)).build(), ServerConfig.DEFAULT);

        JsonNode body = error(post("/render", render(DOCUMENT + "\t\tParagraph: More.\n".repeat(200), Map.of())),
                413, "PAGE_LIMIT");

        assertEquals("LIMIT", body.get("kind").asText());
    }

    @Test
    void aConversionOutOfTimeIsA503() throws Exception {
        start(ServerConfig.DEFAULT.withLimits(2 * 1024 * 1024, Duration.ofNanos(1), 4));

        JsonNode body = error(post("/render", render(DOCUMENT, Map.of())), 503, "CANCELLED");

        assertEquals("CANCELLED", body.get("kind").asText());
        assertTrue(body.get("source").isNull());
    }

    /**
     * A fill ({@code ?format=stxt}) reads no resources, so on a lazy server it does not create the converter: with
     * resources that cannot be read, a fill is answered and a conversion is not.
     */
    @Test
    void aFillDoesNotNeedTheConverterOfALazyServer() throws Exception {
        server = PressServer.startLazily(() -> {
            throw new IllegalStateException("The resources cannot be read");
        }, templates, documents, ServerConfig.DEFAULT.withAddress("127.0.0.1", 0));

        HttpResponse<byte[]> filled = post("/render?format=stxt", render(WITH_SLOTS, Map.of("data", JSON.readTree(SAMPLE))));
        assertEquals(200, filled.statusCode(), () -> text(filled));
        assertTrue(text(filled).contains("Title: Sample"), () -> text(filled));
        HttpResponse<byte[]> print = post("/print/invoices/invoice?format=stxt", "{\"title\": \"Posted\", \"lines\": []}");
        assertEquals(200, print.statusCode(), () -> text(print));
        assertTrue(text(print).contains("Title: Posted"), () -> text(print));
        assertEquals(200, get("/print/invoices/invoice?format=stxt").statusCode());

        error(post("/render", render(DOCUMENT, Map.of())), 500, HttpError.INTERNAL);
        error(get("/fonts"), 500, HttpError.INTERNAL);
    }

    @Test
    void theConfigurationIsChecked() {
        for (Runnable bad : List.<Runnable>of(() -> ServerConfig.DEFAULT.withToken(" "),
                () -> ServerConfig.DEFAULT.withAddress("127.0.0.1", 70000),
                () -> ServerConfig.DEFAULT.withLimits(0, Duration.ofSeconds(1), 1),
                () -> ServerConfig.DEFAULT.withLimits(1, Duration.ZERO, 1),
                () -> ServerConfig.DEFAULT.withLimits(1, Duration.ofSeconds(1), 0))) {
            assertThrows(IllegalArgumentException.class, bad::run);
        }
        assertFalse(ServerConfig.DEFAULT.withToken("s3cret").toString().contains("s3cret"));
    }
}
