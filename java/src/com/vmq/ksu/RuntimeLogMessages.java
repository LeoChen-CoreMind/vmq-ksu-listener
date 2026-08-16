package com.vmq.ksu;

final class RuntimeLogMessages {
    private RuntimeLogMessages() {
    }

    static String providerName(String provider) {
        if ("wechat".equals(provider)) return "微信";
        if ("wecom".equals(provider)) return "企业微信";
        if ("alipay".equals(provider)) return "支付宝";
        return provider == null ? "-" : provider;
    }

    static String paymentAccepted(PaymentEvent event) {
        return "已接收支付 来源=" + providerName(event.provider) + " 金额=" + event.price;
    }

    static String runtimeDisabled() {
        return "监听运行时已由配置禁用";
    }

    static String parserMiss(String packageName) {
        return "未匹配支付通知 package=" + packageName;
    }

    static String enqueueFailed(String detail) {
        return "入队失败: " + detail;
    }

    static String heartbeat(String message) {
        return "心跳: " + message;
    }

    static String paymentDelivered() {
        return "支付回调成功";
    }

    static String paymentDeliveryFailed(String message) {
        return "支付回调失败: " + message;
    }

    static String deliveryWorkerFailed(String detail) {
        return "投递工作线程失败: " + detail;
    }

    static String stateUpdateFailed(String detail) {
        return "状态更新失败: " + detail;
    }
}
