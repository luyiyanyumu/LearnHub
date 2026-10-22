package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedConstruction;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Mock sockets only: verifies real timeout cancellation without connecting to any host. */
class PinnedHttpDeadlineTest {
    @Test
    @Timeout(3)
    void totalDeadlineClosesAResponseStreamEvenWhenAnIdleTimeoutWouldNotFinishIt() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        InputStream waiting = new InputStream() {
            @Override public int read() throws IOException {
                try { closed.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
                throw new IOException("synthetic closed socket");
            }
        };
        try (MockedConstruction<Socket> sockets = mockConstruction(Socket.class, (socket, context) -> {
            when(socket.getInputStream()).thenReturn(waiting);
            when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
            doAnswer(call -> { closed.countDown(); return null; }).when(socket).close();
        })) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> PinnedHttpClient.get(
                    InetAddress.getByAddress(new byte[]{93, (byte)184, (byte)216, 34}),
                    URI.create("http://example.invalid/synthetic"), "synthetic-agent", 1000, 1000, 1000, 30));
            assertEquals("网页抓取时间预算已用尽", failure.getMessage());
            assertEquals(1, sockets.constructed().size());
            verify(sockets.constructed().getFirst(), atLeastOnce()).close();
        } finally { closed.countDown(); }
    }

    @Test void oldSixArgumentSignatureStillPerformsAnonymousPinnedHttp() throws Exception {
        byte[] response = "HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n\r\nsynthetic body".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        try (MockedConstruction<Socket> sockets = mockConstruction(Socket.class, (socket, context) -> {
            when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(response));
            when(socket.getOutputStream()).thenReturn(request);
        })) {
            var actual = PinnedHttpClient.get(InetAddress.getByAddress(new byte[]{93, (byte)184, (byte)216, 34}),
                    URI.create("http://example.invalid/synthetic?q=1"), "synthetic-agent", 1000, 1000, 1000);
            assertEquals(200, actual.status());
            assertEquals("synthetic body", new String(actual.body(), StandardCharsets.US_ASCII));
            String headers = request.toString(StandardCharsets.US_ASCII);
            assertTrue(headers.startsWith("GET /synthetic?q=1 HTTP/1.0"));
            assertTrue(headers.contains("Host: example.invalid"));
            assertFalse(headers.contains("Authorization"));
            assertFalse(headers.contains("Cookie"));
            verify(sockets.constructed().getFirst()).close();
        }
    }
}
