package com.vmq.ksu;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class VmqProtocol {
    private VmqProtocol() {
    }

    public static URL heartbeatUrl(String baseUrl, String key, long timestampMs) throws MalformedURLException {
        String timestamp = Long.toString(timestampMs);
        return new URL(normalizeBaseUrl(baseUrl) + "/appHeart?t=" + encode(timestamp)
                + "&sign=" + encode(md5(timestamp + key)));
    }

    public static URL paymentUrl(String baseUrl, String key, PaymentEvent event, long timestampMs) throws MalformedURLException {
        if (event == null) {
            throw new IllegalArgumentException("event 不能为空");
        }
        String normalizedPrice = normalizePrice(event.price);
        String type = Integer.toString(event.type);
        String timestamp = Long.toString(timestampMs);
        return new URL(normalizeBaseUrl(baseUrl) + "/appPush?type=" + encode(type)
                + "&price=" + encode(normalizedPrice)
                + "&t=" + encode(timestamp)
                + "&sign=" + encode(md5(type + normalizedPrice + timestamp + key)));
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.length() == 0) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        int end = baseUrl.length();
        while (end > 0 && baseUrl.charAt(end - 1) == '/') {
            end--;
        }
        if (end == 0) {
            throw new IllegalArgumentException("baseUrl 必须包含协议和主机名");
        }
        return baseUrl.substring(0, end);
    }

    private static String normalizePrice(String rawPrice) {
        if (rawPrice == null) {
            throw new IllegalArgumentException("price 不能为空");
        }
        try {
            BigDecimal amount = new BigDecimal(rawPrice).setScale(2, RoundingMode.UNNECESSARY);
            if (amount.compareTo(new BigDecimal("0.01")) < 0
                    || amount.compareTo(new BigDecimal("999999.99")) > 0) {
                throw new IllegalArgumentException("price 超出支持范围");
            }
            return amount.toPlainString();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("price 最多保留两位小数", exception);
        }
    }

    private static String encode(String value) {
        if (value == null) {
            throw new IllegalArgumentException("查询参数不能为空");
        }
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException exception) {
            throw new IllegalStateException("UTF-8 不可用", exception);
        }
    }

    private static String md5(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte current : hash) {
                hex.append(String.format("%02x", current & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 不可用", exception);
        }
    }
}
