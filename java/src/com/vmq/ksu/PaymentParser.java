package com.vmq.ksu;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PaymentParser {
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final String WEWORK_PACKAGE = "com.tencent.wework";
    private static final String ALIPAY_PACKAGE = "com.eg.android.AlipayGphone";
    private static final Pattern MONEY_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final String REWARD_PREFIX = "二维码赞赏到账";
    private static final Pattern REWARD_PATTERN = Pattern.compile("二维码赞赏到账\\s*[¥￥]?\\s*(\\d+(?:\\.\\d{1,2})?)\\s*元");
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("(?:[¥￥]\\s*(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)\\s*元)");
    private static final String[] MONEY_KEYWORDS = {"收款", "付款", "到账", "转入"};
    private static final String[] WECHAT_PAYMENT_TITLES = {
            "微信支付",
            "微信收款助手",
            "微信收款商业版",
            "对外收款",
            "企业微信",
            "Weixin Cashier Assistant"};
    private static final BigDecimal MINIMUM_PRICE = new BigDecimal("0.01");
    private static final BigDecimal MAXIMUM_PRICE = new BigDecimal("999999.99");

    private PaymentParser() {
    }

    public static Optional<PaymentEvent> parse(
            String packageName,
            String key,
            long postTime,
            String title,
            String text,
            String bigText,
            String subText) {
        if (packageName == null || key == null) {
            return Optional.empty();
        }

        String safeTitle = title == null ? "" : title;
        String content = join(text, bigText, subText);
        Provider provider = providerFor(packageName, safeTitle, content);
        if (provider == null || provider.money == null || provider.money.isEmpty()) return Optional.empty();

        String normalizedPrice = normalizePrice(provider.money);
        if (normalizedPrice == null) {
            return Optional.empty();
        }

        String eventId = sha256(packageName + "\n" + key + "\n" + postTime + "\n" + normalizedPrice);
        return Optional.of(new PaymentEvent(
                eventId,
                packageName,
                key,
                postTime,
                provider.type,
                normalizedPrice,
                provider.name));
    }

    private static Provider providerFor(String packageName, String title, String content) {
        if (WECHAT_PACKAGE.equals(packageName) || WEWORK_PACKAGE.equals(packageName)) {
            boolean paymentTitle = false;
            for (String candidate : WECHAT_PAYMENT_TITLES) {
                if (candidate.equals(title)) {
                    paymentTitle = true;
                    break;
                }
            }
            if (!paymentTitle || content.contains("已支付")) return null;
            if (WECHAT_PACKAGE.equals(packageName) && content.contains(REWARD_PREFIX)) {
                Matcher rewards = REWARD_PATTERN.matcher(content);
                Set<String> amounts = new LinkedHashSet<String>();
                int start = content.indexOf(REWARD_PREFIX);
                while (start >= 0) {
                    rewards.region(start, content.length());
                    if (!rewards.lookingAt()) return null;
                    String price = normalizePrice(rewards.group(1));
                    if (price == null) return null;
                    amounts.add(price);
                    start = content.indexOf(REWARD_PREFIX, rewards.end());
                }
                if (amounts.size() != 1 || ambiguousAmounts(content)) return null;
                return new Provider(1, "wechat", amounts.iterator().next());
            }
            if (!content.contains("收款") || ambiguousAmounts(content)) return null;
            return new Provider(1, WECHAT_PACKAGE.equals(packageName) ? "wechat" : "wework", getMoney(content));
        }

        if (!ALIPAY_PACKAGE.equals(packageName)) return null;
        boolean normalPayment = title.contains("成功收款") && content.contains("已转入余额")
                || content.contains("通过扫码向你付款");
        boolean staffPayment = title.contains("店员通") || content.contains("支付宝成功收款");
        if (!normalPayment && !staffPayment) return null;
        if (ambiguousAmounts(join(title, content))) return null;

        String money = normalPayment ? getMoney(title) : getMoney(content);
        if (money == null || money.isEmpty()) money = normalPayment ? getMoney(content) : getMoney(title);
        if (money == null || money.isEmpty()) money = normalPayment ? getMoney(content) : getMoney(title);
        return new Provider(2, "alipay", money);
    }

    static String join(String... values) {
        StringBuilder builder = new StringBuilder();
        Set<String> seen = new LinkedHashSet<String>();
        for (String value : values) {
            if (value == null) continue;
            for (String line : value.split("\\r\\n|\\r|\\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && seen.add(trimmed)) {
                    if (builder.length() > 0) builder.append('\n');
                    builder.append(trimmed);
                }
            }
        }
        return builder.toString();
    }

    static boolean ambiguousAmounts(String... values) {
        Set<String> amounts = new LinkedHashSet<String>();
        Matcher matcher = CURRENCY_PATTERN.matcher(join(values));
        while (matcher.find()) {
            String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            amounts.add(new BigDecimal(raw).stripTrailingZeros().toPlainString());
        }
        return amounts.size() > 1;
    }

    static String getMoney(String content) {
        if (content == null || content.isEmpty()) return null;
        int startIndex = -1;
        for (String keyword : MONEY_KEYWORDS) {
            int index = content.indexOf(keyword);
            if (index >= 0) {
                startIndex = index;
                break;
            }
        }

        String searchText = startIndex >= 0 ? content.substring(startIndex) : content;
        List<String> validAmounts = new ArrayList<String>();
        Matcher matcher = MONEY_PATTERN.matcher(searchText);
        while (matcher.find()) {
            String matched = matcher.group();
            if (!isValidNumber(matched)) continue;
            try {
                double amount = Double.parseDouble(matched);
                if (amount >= 0.01d && amount <= 999999.99d) validAmounts.add(matched);
            } catch (NumberFormatException ignored) {
                // Continue scanning exactly as the original listener does.
            }
        }
        return validAmounts.isEmpty() ? null : validAmounts.get(0);
    }

    private static boolean isValidNumber(String value) {
        if (value == null || value.isEmpty()) return false;
        int dotCount = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '.') {
                dotCount++;
                if (dotCount > 1 || index == 0 || index == value.length() - 1) return false;
            } else if (!Character.isDigit(current)) {
                return false;
            }
        }
        return true;
    }

    private static String normalizePrice(String rawPrice) {
        try {
            BigDecimal price = new BigDecimal(rawPrice).setScale(2, RoundingMode.HALF_UP);
            if (price.compareTo(MINIMUM_PRICE) < 0 || price.compareTo(MAXIMUM_PRICE) > 0) return null;
            return price.toPlainString();
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte current : hash) {
                hex.append(String.format("%02x", current & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static final class Provider {
        private final int type;
        private final String name;
        private final String money;

        private Provider(int type, String name, String money) {
            this.type = type;
            this.name = name;
            this.money = money;
        }
    }
}
