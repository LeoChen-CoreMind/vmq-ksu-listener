package com.vmq.ksu;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigTest {
    private ConfigTest() {
    }

    public static void run() throws Exception {
        verifyValidUrls();
        verifyInvalidFields();
        verifyAtomicWriteRejectsInvalidReplacement();
        verifyEnabledCopy();
        verifyAtomicEnabledUpdate();
    }

    private static void verifyValidUrls() {
        Config.validate(json("https://vmq.example/base"));
        Config.validate(json("http://127.0.0.1:8080"));
    }

    private static void verifyInvalidFields() {
        reject(jsonWith("baseUrl", "vmq.example"));
        reject(jsonWith("baseUrl", "https://user:pass@vmq.example"));
        reject(jsonWith("baseUrl", "https://vmq.example?mode=test"));
        reject(jsonWith("baseUrl", "https://vmq.example/#fragment"));
        reject(new JSONObject(validJson("https://vmq.example").replace("}", ",\"unknown\":true}")));
        reject(jsonWith("key", "   "));
        reject(jsonWith("heartbeatSeconds", 14));
        reject(jsonWith("heartbeatSeconds", 3601));
        reject(jsonWith("connectTimeoutSeconds", 0));
        reject(jsonWith("readTimeoutSeconds", 121));
        reject(jsonWith("maxRetries", -1));
        reject(jsonWith("maxRetries", 31));
    }

    private static void verifyAtomicWriteRejectsInvalidReplacement() throws Exception {
        Path directory = Files.createTempDirectory("vmq-config-test");
        Path path = directory.resolve("config.json");
        JSONObject original = json("https://vmq.example");
        Config.writeAtomic(path, original);
        byte[] before = Files.readAllBytes(path);
        try {
            Config.writeAtomic(path, jsonWith("key", ""));
            throw new AssertionError("invalid config must be rejected");
        } catch (IllegalArgumentException expected) {
            require(java.util.Arrays.equals(before, Files.readAllBytes(path)), "previous config must remain intact");
        }
    }

    private static void verifyEnabledCopy() throws Exception {
        Path directory = Files.createTempDirectory("vmq-config-copy-test");
        Path configPath = directory.resolve("config.json");
        Config.writeAtomic(configPath, json("https://vmq.example"));
        Config original = Config.load(configPath);
        Config disabled = original.withEnabled(false);
        require(original.enabled, "original config must be enabled");
        require(!disabled.enabled, "enabled copy must be disabled");
        require(original.key.equals(disabled.key), "enabled copy must preserve key");
        require(original.baseUrl.equals(disabled.baseUrl), "enabled copy must preserve base URL");
    }

    private static void verifyAtomicEnabledUpdate() throws Exception {
        Path directory = Files.createTempDirectory("vmq-config-enabled-test");
        Path configPath = directory.resolve("config.json");
        Config.writeAtomic(configPath, json("https://vmq.example/latest"));
        Config.updateEnabledAtomic(configPath, false);
        Config updated = Config.load(configPath);
        require(!updated.enabled, "atomic enabled update must change the service state");
        require("https://vmq.example/latest".equals(updated.baseUrl), "atomic enabled update must preserve current fields");
        require("secret".equals(updated.key), "atomic enabled update must preserve the current key");
    }

    private static JSONObject json(String baseUrl) {
        return new JSONObject(validJson(baseUrl));
    }

    private static JSONObject jsonWith(String field, String value) {
        return new JSONObject(validJson("https://vmq.example").replace("\"" + field + "\":\"secret\"", "\"" + field + "\":\"" + value + "\"")
                .replace("\"baseUrl\":\"https://vmq.example\"", "\"baseUrl\":\"" + value + "\""));
    }

    private static JSONObject jsonWith(String field, int value) {
        String json = validJson("https://vmq.example");
        json = json.replace("\"" + field + "\":50", "\"" + field + "\":" + value)
                .replace("\"" + field + "\":10", "\"" + field + "\":" + value)
                .replace("\"" + field + "\":30", "\"" + field + "\":" + value)
                .replace("\"" + field + "\":8", "\"" + field + "\":" + value);
        return new JSONObject(json);
    }

    private static String validJson(String baseUrl) {
        return "{\"baseUrl\":\"" + baseUrl + "\",\"key\":\"secret\",\"enabled\":true,"
                + "\"wechatEnabled\":true,\"wecomEnabled\":true,\"alipayEnabled\":true,"
                + "\"heartbeatSeconds\":50,\"connectTimeoutSeconds\":10,\"readTimeoutSeconds\":30,"
                + "\"maxRetries\":8,\"logLevel\":\"info\"}";
    }

    private static void reject(JSONObject json) {
        try {
            Config.validate(json);
            throw new AssertionError("invalid config accepted: " + json);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
