package com.vmq.ksu;

import java.util.Optional;

public final class PaymentParserTest {
    private PaymentParserTest() {
    }

    public static void run() {
        verifyWechatPayment();
        verifyWechatPaymentWithoutArrivalSuffix();
        verifyWechatPaymentWithCurrencySymbol();
        verifyExpandedNotificationFields();
        verifyAllOriginalWechatTitles();
        verifyWeWorkPayment();
        verifyAlipayPayment();
        verifyAlipayScanPayment();
        verifyAlipayStaffPayment();
        verifyAlipayAmountFallbacks();
        verifyRejectsGenericAndOtherProviderNotifications();
        verifyAmountNormalizationAndBounds();
        verifyNotificationKeyParticipatesInEventId();
    }

    private static void verifyWechatPayment() {
        PaymentEvent event = parse("com.tencent.mm", "wechat-1", 1000L, "微信支付", "微信支付收款到账0.01元");
        require(event.type == 1, "wechat type");
        require("0.01".equals(event.price), "wechat price");
        require("wechat".equals(event.provider), "wechat provider");
    }

    private static void verifyWechatPaymentWithoutArrivalSuffix() {
        PaymentEvent event = parse("com.tencent.mm", "wechat-legacy", 1001L, "微信支付", "微信支付收款30.01元");
        require("30.01".equals(event.price), "wechat legacy notification price");
    }

    private static void verifyWechatPaymentWithCurrencySymbol() {
        PaymentEvent event = parse("com.tencent.mm", "wechat-currency", 1002L,
                "\u5fae\u4fe1\u652f\u4ed8",
                "[4\u6761]\u5fae\u4fe1\u652f\u4ed8: \u4e2a\u4eba\u6536\u6b3e\u7801\u5230\u8d26\u00a51.00");
        require("1.00".equals(event.price), "wechat currency-symbol notification price");
    }

    private static void verifyExpandedNotificationFields() {
        Optional<PaymentEvent> wechat = PaymentParser.parse("com.tencent.mm", "wechat-big-text", 1050L,
                "微信支付", "", "个人收款到账2.34元", "收款提醒");
        require(wechat.isPresent() && "2.34".equals(wechat.get().price),
                "wechat must parse bigText/subText when text is empty");

        Optional<PaymentEvent> alipay = PaymentParser.parse("com.eg.android.AlipayGphone", "alipay-sub-text", 1051L,
                "成功收款", "", "", "已转入余额，收款2.35元");
        require(alipay.isPresent() && "2.35".equals(alipay.get().price),
                "alipay must parse subText when text is empty");
    }

    private static void verifyAllOriginalWechatTitles() {
        String[] titles = {
                "微信支付",
                "微信收款助手",
                "微信收款商业版",
                "对外收款",
                "企业微信",
                "Weixin Cashier Assistant"};
        for (int index = 0; index < titles.length; index++) {
            PaymentEvent event = parse("com.tencent.mm", "wechat-title-" + index,
                    1100L + index, titles[index], "收款到账1.23元");
            require("1.23".equals(event.price), "original wechat title " + titles[index]);
        }
        require(!PaymentParser.parse("com.tencent.mm", "wechat-title-prefix", 1200L,
                "通知-微信支付", "收款到账1.23元", null, null).isPresent(),
                "wechat title matching must remain exact");
    }

    private static void verifyWeWorkPayment() {
        PaymentEvent event = parse("com.tencent.wework", "wework-1", 1001L, "企业微信", "对外收款到账 12.30 元");
        require(event.type == 1, "wework type");
        require("12.30".equals(event.price), "wework price");
        require("wework".equals(event.provider), "wework provider");
    }

    private static void verifyAlipayPayment() {
        PaymentEvent event = parse("com.eg.android.AlipayGphone", "alipay-1", 1002L, "成功收款0.10元", "已转入余额");
        require(event.type == 2, "alipay type");
        require("0.10".equals(event.price), "alipay price");
        require("alipay".equals(event.provider), "alipay provider");
    }

    private static void verifyAlipayScanPayment() {
        PaymentEvent event = parse("com.eg.android.AlipayGphone", "alipay-scan", 1003L, "支付宝", "通过扫码向你付款0.20元");
        require("0.20".equals(event.price), "alipay scan payment price");
    }

    private static void verifyAlipayStaffPayment() {
        PaymentEvent event = parse("com.eg.android.AlipayGphone", "alipay-staff", 1004L, "店员通", "支付宝成功收款0.30元");
        require("0.30".equals(event.price), "alipay staff payment price");
    }

    private static void verifyAlipayAmountFallbacks() {
        PaymentEvent normal = parse("com.eg.android.AlipayGphone", "alipay-normal-content", 1201L,
                "成功收款", "已转入余额，收款0.40元");
        require("0.40".equals(normal.price), "normal alipay must fall back from title to content");

        PaymentEvent staff = parse("com.eg.android.AlipayGphone", "alipay-staff-title", 1202L,
                "店员通成功收款0.50元", "到账提醒");
        require("0.50".equals(staff.price), "staff alipay must fall back from content to title");
    }

    private static void verifyRejectsGenericAndOtherProviderNotifications() {
        require(!PaymentParser.parse("com.tencent.mm", "wechat-reject", 1003L, "新消息", "转账给张三0元", null, null).isPresent(), "generic wechat message must be rejected");
        require(!PaymentParser.parse("com.tencent.mm", "wechat-spoof", 1004L, "新消息", "微信支付收款1.00元", null, null).isPresent(), "wechat payment text requires an official title");
        require(!PaymentParser.parse("com.tencent.mm", "wechat-paid", 1005L, "微信支付", "已支付1.00元，收款方已收款", null, null).isPresent(), "outgoing payment must be rejected");
        require(!PaymentParser.parse("com.tencent.mm", "wechat-title-only", 1006L, "微信收款助手", "余额变动1.00元", null, null).isPresent(), "wechat title alone must not imply a payment");
        require(!PaymentParser.parse("com.eg.android.AlipayGphone", "alipay-outgoing", 1007L, "支付成功", "已支付1.00元", null, null).isPresent(), "outgoing alipay payment must be rejected");
        require(!PaymentParser.parse("com.example.other", "other-reject", 1004L, "成功收款1.00元", "已转入余额", null, null).isPresent(), "unrecognized package must be rejected");
    }

    private static void verifyAmountNormalizationAndBounds() {
        require("1.00".equals(parse("com.tencent.mm", "one", 1005L, "微信支付", "微信支付收款到账1元").price), "whole amount normalization");
        require("1.00".equals(parse("com.tencent.mm", "one-point-zero", 1006L, "微信支付", "微信支付收款到账1.0元").price), "one decimal normalization");
        require("1.00".equals(parse("com.tencent.mm", "one-point-zero-zero", 1007L, "微信支付", "微信支付收款到账1.00元").price), "two decimal normalization");
        require("1.00".equals(parse("com.tencent.mm", "three-decimals", 1008L, "微信支付", "微信支付收款到账1.001元").price), "original listener rounds to two decimals");
        require(!PaymentParser.parse("com.tencent.mm", "zero", 1009L, "微信支付", "微信支付收款到账0元", null, null).isPresent(), "zero amount must be rejected");
        require(!PaymentParser.parse("com.tencent.mm", "too-large", 1010L, "微信支付", "微信支付收款到账1000000.00元", null, null).isPresent(), "oversized amount must be rejected");
    }

    private static void verifyNotificationKeyParticipatesInEventId() {
        PaymentEvent first = parse("com.tencent.mm", "event-a", 1011L, "微信支付", "微信支付收款到账10.00元");
        PaymentEvent second = parse("com.tencent.mm", "event-b", 1011L, "微信支付", "微信支付收款到账10.00元");
        require(!first.eventId.equals(second.eventId), "different notification keys must produce different event IDs");
    }

    private static PaymentEvent parse(String packageName, String key, long postTime, String title, String text) {
        Optional<PaymentEvent> event = PaymentParser.parse(packageName, key, postTime, title, text, null, null);
        require(event.isPresent(), "expected payment event");
        return event.get();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
