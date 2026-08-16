package com.vmq.ksu;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class VmqClientTest {
    private VmqClientTest() {
    }

    public static void run() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok/appHeart", response(200, "{\"code\":1}"));
        server.createContext("/reject/appHeart", response(200, "{\"code\":0,\"msg\":\"no\"}"));
        server.createContext("/redirect/appHeart", redirect());
        server.createContext("/large/appHeart", response(200, repeat('x', 65537)));
        server.start();
        try {
            int port = server.getAddress().getPort();
            VmqClient.DeliveryResult accepted = VmqClient.sendHeartbeat(config("http://127.0.0.1:" + port + "/ok"));
            require(accepted.success && "成功".equals(accepted.message), "accepted heartbeat message localized");
            VmqClient.DeliveryResult rejected = VmqClient.sendHeartbeat(config("http://127.0.0.1:" + port + "/reject"));
            require(!rejected.success && "服务器拒绝响应".equals(rejected.message), "server rejection message localized");
            require(!VmqClient.sendHeartbeat(config("http://127.0.0.1:" + port + "/redirect")).success, "redirects must not be followed");
            VmqClient.DeliveryResult large = VmqClient.sendHeartbeat(config("http://127.0.0.1:" + port + "/large"));
            require(!large.success && large.message.contains("65536"), "response limit");
        } finally {
            server.stop(0);
        }
    }

    private static Config config(String baseUrl) throws Exception {
        Path path = Files.createTempFile("vmq-client", ".json");
        String json = "{\"baseUrl\":\"" + baseUrl + "\",\"key\":\"secret\",\"enabled\":true,"
                + "\"wechatEnabled\":true,\"wecomEnabled\":true,\"alipayEnabled\":true,"
                + "\"heartbeatSeconds\":50,\"connectTimeoutSeconds\":2,\"readTimeoutSeconds\":2,"
                + "\"maxRetries\":8,\"logLevel\":\"info\"}";
        Files.write(path, json.getBytes(StandardCharsets.UTF_8));
        return Config.load(path);
    }

    private static HttpHandler response(final int status, final String body) {
        return new HttpHandler() {
            @Override public void handle(HttpExchange exchange) throws IOException {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            }
        };
    }

    private static HttpHandler redirect() {
        return new HttpHandler() {
            @Override public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().add("Location", "/ok/appHeart");
                exchange.sendResponseHeaders(302, -1L);
                exchange.close();
            }
        };
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        java.util.Arrays.fill(values, value);
        return new String(values);
    }

    public static void verifyRuntimeState(Config config) throws Exception {
        Path path = Files.createTempFile("vmq-state", ".json");
        Files.write(path, "{\"supervisor\":{\"pid\":123},\"listener\":{\"running\":false}}".getBytes(StandardCharsets.UTF_8));
        RuntimeState state = new RuntimeState(path);
        Map<String, Object> listener = new LinkedHashMap<String, Object>();
        listener.put("running", true);
        state.mergeListener(listener);
        String publicStatus = state.publicStatus(config);
        require(publicStatus.contains("\"supervisor\":{\"pid\":123}"), "preserve supervisor state");
        require(publicStatus.contains("\"configured\":true"), "public configured flag");
        require(!publicStatus.contains("secret"), "public status must redact key");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
