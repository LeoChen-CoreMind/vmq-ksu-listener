package com.vmq.ksu;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class VmqClient {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    public static final class DeliveryResult {
        public final boolean success;
        public final int httpStatus;
        public final String message;

        DeliveryResult(boolean success, int httpStatus, String message) {
            this.success = success;
            this.httpStatus = httpStatus;
            this.message = message;
        }

        public Map<String, Object> toMap() {
            LinkedHashMap<String, Object> values = new LinkedHashMap<String, Object>();
            values.put("success", success);
            values.put("httpStatus", httpStatus);
            values.put("message", message);
            return values;
        }
    }

    private VmqClient() {
    }

    public static DeliveryResult sendHeartbeat(Config config) {
        try {
            return execute(VmqProtocol.heartbeatUrl(config.baseUrl, config.key, System.currentTimeMillis()), config);
        } catch (Exception exception) {
            return failure(exception);
        }
    }

    public static DeliveryResult sendPayment(Config config, PaymentEvent event) {
        try {
            return execute(VmqProtocol.paymentUrl(config.baseUrl, config.key, event, System.currentTimeMillis()), config);
        } catch (Exception exception) {
            return failure(exception);
        }
    }

    private static DeliveryResult execute(URL url, Config config) {
        HttpURLConnection connection = null;
        InputStream stream = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(config.connectTimeoutSeconds * 1000);
            connection.setReadTimeout(config.readTimeoutSeconds * 1000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            stream = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
            String body = readBounded(stream);
            if (status < 200 || status >= 300) {
                return new DeliveryResult(false, status, "HTTP " + status);
            }
            Map<String, Object> response = JsonCodec.parseObject(body);
            boolean accepted = numericCode(response.get("code")) || "成功".equals(response.get("msg"));
            return new DeliveryResult(accepted, status, accepted ? "成功" : "服务器拒绝响应");
        } catch (Exception exception) {
            return failure(exception);
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) { }
            }
            if (connection != null) connection.disconnect();
        }
    }

    private static String readBounded(InputStream input) throws IOException {
        if (input == null) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_RESPONSE_BYTES) throw new IOException("响应超过 65536 字节");
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean numericCode(Object value) {
        if (value instanceof Number) {
            int code = ((Number) value).intValue();
            return code == 1 || code == 200;
        }
        return false;
    }

    private static DeliveryResult failure(Exception exception) {
        String message = exception.getMessage();
        return new DeliveryResult(false, 0, exception.getClass().getSimpleName() + ": " + (message == null ? "" : message));
    }
}
