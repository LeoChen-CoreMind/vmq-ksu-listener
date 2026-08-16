package com.vmq.ksu;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class Config {
    private static final Set<String> KEYS = new HashSet<String>(Arrays.asList(
            "baseUrl", "key", "enabled", "wechatEnabled", "wecomEnabled", "alipayEnabled",
            "heartbeatSeconds", "connectTimeoutSeconds", "readTimeoutSeconds", "maxRetries", "logLevel"));

    public final String baseUrl;
    public final String key;
    public final boolean enabled;
    public final boolean wechatEnabled;
    public final boolean wecomEnabled;
    public final boolean alipayEnabled;
    public final int heartbeatSeconds;
    public final int connectTimeoutSeconds;
    public final int readTimeoutSeconds;
    public final int maxRetries;
    public final String logLevel;

    private Config(Map<String, Object> values) {
        baseUrl = string(values, "baseUrl");
        key = string(values, "key");
        enabled = bool(values, "enabled");
        wechatEnabled = bool(values, "wechatEnabled");
        wecomEnabled = bool(values, "wecomEnabled");
        alipayEnabled = bool(values, "alipayEnabled");
        heartbeatSeconds = integer(values, "heartbeatSeconds");
        connectTimeoutSeconds = integer(values, "connectTimeoutSeconds");
        readTimeoutSeconds = integer(values, "readTimeoutSeconds");
        maxRetries = integer(values, "maxRetries");
        logLevel = string(values, "logLevel");
    }

    public static Config load(Path path) throws IOException {
        String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        return fromMap(JsonCodec.parseObject(json));
    }

    public static Config validate(JSONObject json) {
        if (json == null) throw new IllegalArgumentException("config 不能为空");
        return fromMap(JsonCodec.parseObject(json.toString()));
    }

    public static void writeAtomic(Path path, JSONObject json) throws IOException {
        Config config = validate(json);
        writeAtomic(path, config);
    }

    public static void writeAtomic(Path path, Config config) throws IOException {
        if (config == null) throw new IllegalArgumentException("config 不能为空");
        AtomicFiles.write(path, JsonCodec.stringify(config.toMap()) + "\n");
    }

    static void updateEnabledAtomic(Path path, final boolean enabled) throws IOException {
        AtomicFiles.update(path, new AtomicFiles.TextUpdater() {
            @Override public String update(String current) {
                Config latest = fromMap(JsonCodec.parseObject(current));
                return JsonCodec.stringify(latest.withEnabled(enabled).toMap()) + "\n";
            }
        });
    }

    public Config withEnabled(boolean value) {
        Map<String, Object> values = toMap();
        values.put("enabled", value);
        return fromMap(values);
    }

    public Map<String, Object> toMap() {
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<String, Object>();
        values.put("baseUrl", baseUrl);
        values.put("key", key);
        values.put("enabled", enabled);
        values.put("wechatEnabled", wechatEnabled);
        values.put("wecomEnabled", wecomEnabled);
        values.put("alipayEnabled", alipayEnabled);
        values.put("heartbeatSeconds", heartbeatSeconds);
        values.put("connectTimeoutSeconds", connectTimeoutSeconds);
        values.put("readTimeoutSeconds", readTimeoutSeconds);
        values.put("maxRetries", maxRetries);
        values.put("logLevel", logLevel);
        return values;
    }

    public Map<String, Object> toPublicMap() {
        Map<String, Object> values = toMap();
        values.remove("key");
        values.put("configured", !key.trim().isEmpty());
        return values;
    }

    private static Config fromMap(Map<String, Object> values) {
        if (!KEYS.equals(values.keySet())) {
            Set<String> unknown = new HashSet<String>(values.keySet());
            unknown.removeAll(KEYS);
            Set<String> missing = new HashSet<String>(KEYS);
            missing.removeAll(values.keySet());
            throw new IllegalArgumentException("配置字段不匹配；未知字段=" + unknown + "，缺少字段=" + missing);
        }
        Config config = new Config(values);
        validateUrl(config.baseUrl);
        if (config.key.trim().isEmpty()) throw new IllegalArgumentException("key 不能为空");
        range("heartbeatSeconds", config.heartbeatSeconds, 15, 3600);
        range("connectTimeoutSeconds", config.connectTimeoutSeconds, 1, 120);
        range("readTimeoutSeconds", config.readTimeoutSeconds, 1, 120);
        range("maxRetries", config.maxRetries, 0, 30);
        if (!Arrays.asList("error", "info", "debug").contains(config.logLevel)) {
            throw new IllegalArgumentException("logLevel 必须是 error、info 或 debug");
        }
        return config;
    }

    private static void validateUrl(String value) {
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) {
                throw new IllegalArgumentException("baseUrl 必须是不含凭据、查询参数或片段的 HTTP(S) 地址");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("baseUrl 无效", exception);
        }
    }

    private static String string(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException(key + " 必须是字符串");
        return (String) value;
    }

    private static boolean bool(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException(key + " 必须是布尔值");
        return (Boolean) value;
    }

    private static int integer(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue()) {
            throw new IllegalArgumentException(key + " 必须是整数");
        }
        return ((Number) value).intValue();
    }

    private static void range(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " 必须介于 " + minimum + " 和 " + maximum + " 之间");
        }
    }
}
