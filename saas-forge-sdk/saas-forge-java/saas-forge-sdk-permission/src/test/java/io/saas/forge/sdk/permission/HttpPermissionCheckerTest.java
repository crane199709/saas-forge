package io.saas.forge.sdk.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HttpPermissionCheckerTest {
    @Test
    void eachRequestRechecksTheAuthorityAndFailsClosedOnOutage() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/permissions/decision", exchange -> {
            int call = calls.incrementAndGet();
            assertEquals("Bearer user-token", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = (call == 1
                    ? "{\"code\":\"example:project:list\",\"allowed\":true}"
                    : call == 2 ? "{\"code\":\"example:project:list\",\"allowed\":false}" : "{}").getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(call == 3 ? 503 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var checker = new HttpPermissionChecker(() -> java.net.URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort()));
            checker.require("example:project:list", "Bearer user-token");
            assertThrows(PermissionDeniedException.class,
                    () -> checker.require("example:project:list", "Bearer user-token"));
            assertThrows(PermissionAuthorityUnavailableException.class,
                    () -> checker.require("example:project:list", "Bearer user-token"));
            assertEquals(3, calls.get());
        } finally {
            server.stop(0);
        }
    }
}
