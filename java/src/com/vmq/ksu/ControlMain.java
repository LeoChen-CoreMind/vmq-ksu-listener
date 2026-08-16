package com.vmq.ksu;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ControlMain {
    private ControlMain() {
    }

    public static void main(String[] arguments) {
        try {
            ParsedArguments parsed = ParsedArguments.parse(arguments);
            Map<String, Object> data = execute(parsed);
            respond(true, data);
        } catch (Exception exception) {
            respond(false, message(exception));
            System.exit(1);
        }
    }

    private static Map<String, Object> execute(ParsedArguments parsed) throws Exception {
        String command = parsed.command;
        if ("config-set".equals(command)) {
            if (parsed.commandArguments.length != 0) throw new IllegalArgumentException("config-set 不接受参数");
            String input = readBounded(System.in, 64 * 1024);
            Config.writeAtomic(parsed.root.resolve("config.json"), new JSONObject(input));
            return map("configured", true);
        }
        if ("config-get".equals(command)) {
            if (parsed.commandArguments.length != 0) throw new IllegalArgumentException("config-get 不接受参数");
            return Config.load(parsed.root.resolve("config.json")).toMap();
        }
        if ("status".equals(command)) {
            if (parsed.commandArguments.length != 0) throw new IllegalArgumentException("status 不接受参数");
            Config config = Config.load(parsed.root.resolve("config.json"));
            Map<String, Object> status = JsonCodec.parseObject(
                    new RuntimeState(parsed.root.resolve("state.json")).publicStatus(config));
            Map<String, Object> listener = listenerState(status);
            Map<String, Object> health = listenerHealth(parsed, listener);
            listener.put("running", health.get("listenerRunning"));
            listener.putAll(health);
            status.put("listener", listener);
            boolean serviceRunning = "running".equals(health.get("desiredState"))
                    && Boolean.TRUE.equals(health.get("supervisorRunning"));
            syncModuleDescription(parsed, serviceRunning);
            return status;
        }
        if ("test-listener".equals(command)) {
            if (parsed.commandArguments.length != 0) throw new IllegalArgumentException("test-listener 不接受参数");
            Map<String, Object> root = readState(parsed.root.resolve("state.json"));
            return listenerHealth(parsed, listenerState(root));
        }
        if ("test-connection".equals(command)) {
            if (parsed.commandArguments.length != 0) throw new IllegalArgumentException("test-connection 不接受参数");
            return VmqClient.sendHeartbeat(Config.load(parsed.root.resolve("config.json"))).toMap();
        }
        if ("test-parser".equals(command)) return parserTest(parsed);
        if ("logs".equals(command)) {
            if (parsed.commandArguments.length != 0) throw new IllegalArgumentException("logs 不接受参数");
            LogTail.Result result = LogTail.read(parsed.root.resolve("vmq.log"),
                    parsed.root.resolve("vmq.log.1"), 200, 32 * 1024);
            return map("text", result.text, "lineCount", result.lineCount, "truncated", result.truncated);
        }
        if ("clear-logs".equals(command)) {
            if (parsed.commandArguments.length != 0) throw new IllegalArgumentException("clear-logs 不接受参数");
            LogStore.clear(parsed.root.resolve("vmq.log"));
            return map("cleared", true);
        }
        if ("start".equals(command) || "stop".equals(command) || "restart".equals(command)) {
            if (parsed.commandArguments.length != 0) throw new IllegalArgumentException(command + " 不接受参数");
            return lifecycle(parsed, command);
        }
        throw new IllegalArgumentException("未知命令：" + command);
    }

    private static Map<String, Object> lifecycle(ParsedArguments parsed, String command) throws Exception {
        String desired = "stop".equals(command) ? "stopped" : "running";
        Path configPath = parsed.root.resolve("config.json");
        Config.updateEnabledAtomic(configPath, "running".equals(desired));
        AtomicFiles.write(parsed.root.resolve("desired_state"), desired + "\n");
        Path statePath = parsed.root.resolve("state.json");
        long supervisorPid = statePid(statePath, "supervisor", "pid");
        if (supervisorPid > 0 && !isSupervisorProcess(supervisorPid, parsed.root)) supervisorPid = -1L;
        if (supervisorPid <= 0) cleanupOrphanListener(statePath, parsed.root);
        if (supervisorPid > 0 && ("stop".equals(command) || "restart".equals(command))) {
            signal(supervisorPid, "stop".equals(command) ? "TERM" : "HUP");
        } else if (("start".equals(command) || "restart".equals(command)) && supervisorPid <= 0) {
            Path binary = parsed.module.resolve("bin/vmqd");
            new ProcessBuilder(binary.toString(), "--root", parsed.root.toString(), "--module", parsed.module.toString()).start();
        }
        syncModuleDescription(parsed, "running".equals(desired));
        return map("desiredState", desired);
    }

    private static void syncModuleDescription(ParsedArguments parsed, boolean running) {
        try {
            ModuleMetadata.updateDescription(parsed.module.resolve("module.prop"), running);
        } catch (IOException exception) {
            System.err.println("VMQ_MODULE_METADATA_UPDATE_FAILED 模块状态说明更新失败: " + exception.getMessage());
        }
    }

    private static Map<String, Object> parserTest(ParsedArguments parsed) throws IOException {
        if (parsed.commandArguments.length != 0) throw new IllegalArgumentException("test-parser 不接受参数");
        String fixture = readFile(parsed.module.resolve("fixtures/notifications.json"), 64 * 1024);
        Object raw = JsonCodec.parse(fixture);
        if (!(raw instanceof List)) throw new IllegalArgumentException("fixtures 必须是数组");
        int accepted = 0;
        int rejected = 0;
        for (Object item : (List<?>) raw) {
            @SuppressWarnings("unchecked") Map<String, Object> values = (Map<String, Object>) item;
            String packageName = string(values, "package");
            String title = string(values, "title");
            String text = string(values, "text");
            OptionalPayment result = new OptionalPayment(PaymentParser.parse(packageName, "fixture", 1L, title, text, "", ""));
            boolean expectedReject = Boolean.TRUE.equals(values.get("reject"));
            if (result.present == expectedReject) throw new IllegalArgumentException("fixture 预期结果不一致：" + packageName);
            if (result.present) accepted++; else rejected++;
        }
        return map("accepted", accepted, "rejected", rejected);
    }

    private static long statePid(Path statePath, String section, String field) throws IOException {
        Map<String, Object> root = readState(statePath);
        Object values = root.get(section);
        if (!(values instanceof Map)) return -1L;
        Object pid = ((Map<?, ?>) values).get(field);
        return pid instanceof Number ? ((Number) pid).longValue() : -1L;
    }

    private static Map<String, Object> listenerHealth(ParsedArguments parsed, Map<String, Object> listener) throws IOException {
        long supervisorPid = statePid(parsed.root.resolve("state.json"), "supervisor", "pid");
        long listenerPid = statePid(parsed.root.resolve("state.json"), "supervisor", "listenerPid");
        return ListenerHealth.evaluate(desiredState(parsed.root),
                isSupervisorProcess(supervisorPid, parsed.root),
                isListenerProcess(listenerPid, parsed.root),
                Boolean.TRUE.equals(listener.get("connected")),
                number(listener.get("connectedAt")),
                number(listener.get("lastNotificationPollAt")),
                Boolean.TRUE.equals(listener.get("lastNotificationPollSuccess")),
                number(listener.get("lastNotificationAt")));
    }

    private static Map<String, Object> listenerState(Map<String, Object> root) {
        LinkedHashMap<String, Object> listener = new LinkedHashMap<String, Object>();
        Object existing = root.get("listener");
        if (existing instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> existingListener = (Map<String, Object>) existing;
            listener.putAll(existingListener);
        }
        listener.remove("key");
        return listener;
    }

    private static Map<String, Object> readState(Path path) throws IOException {
        if (!Files.exists(path)) return new LinkedHashMap<String, Object>();
        String text = readFile(path, 64 * 1024).trim();
        return text.isEmpty() ? new LinkedHashMap<String, Object>() : JsonCodec.parseObject(text);
    }

    private static String desiredState(Path root) throws IOException {
        String value = readFile(root.resolve("desired_state"), 64 * 1024).trim();
        if ("running".equals(value) || "stopped".equals(value)) return value;
        return "stopped";
    }

    private static void cleanupOrphanListener(Path statePath, Path root) throws Exception {
        long listenerPid = statePid(statePath, "supervisor", "listenerPid");
        if (!isListenerProcess(listenerPid, root)) return;
        signal(listenerPid, "TERM");
        for (int attempt = 0; attempt < 20; attempt++) {
            if (!isListenerProcess(listenerPid, root)) return;
            Thread.sleep(100L);
        }
        if (isListenerProcess(listenerPid, root)) signal(listenerPid, "KILL");
    }

    private static void signal(long pid, String signal) throws Exception {
        if (pid <= 0 || pid > Integer.MAX_VALUE) return;
        Process process = new ProcessBuilder("/system/bin/kill", "-" + signal, Long.toString(pid)).start();
        if (process.waitFor() != 0) throw new IOException("向服务守护进程发送信号失败：" + pid);
    }

    private static boolean isSupervisorProcess(long pid, Path root) {
        try {
            String commandLine = readFile(Paths.get("/proc", Long.toString(pid), "cmdline"), 4096);
            return commandLine.contains("vmqd") && commandLine.contains(root.toString());
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean isListenerProcess(long pid, Path root) {
        if (pid <= 0 || pid > Integer.MAX_VALUE) return false;
        try {
            String commandLine = readFile(Paths.get("/proc", Long.toString(pid), "cmdline"), 4096);
            return commandLine.contains("com.vmq.ksu.Main")
                    && commandLine.contains("--root")
                    && commandLine.contains(root.toString());
        } catch (IOException exception) {
            return false;
        }
    }

    private static String readFile(Path path, int maximum) throws IOException {
        if (!Files.exists(path)) return "";
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > maximum) throw new IOException("文件超过大小限制：" + maximum + " 字节");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String readBounded(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > maximum) throw new IOException("输入超过大小限制：" + maximum + " 字节");
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void respond(boolean ok, Object value) {
        LinkedHashMap<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("ok", ok);
        response.put(ok ? "data" : "error", value);
        System.out.println(JsonCodec.stringify(response));
    }

    private static String message(Exception exception) {
        return exception.getClass().getSimpleName() + ": " + (exception.getMessage() == null ? "" : exception.getMessage());
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : -1L;
    }

    private static Map<String, Object> map(Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }

    private static String string(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("fixture 字段 " + key + " 必须是字符串");
        return (String) value;
    }

    private static final class OptionalPayment {
        final boolean present;
        OptionalPayment(java.util.Optional<PaymentEvent> value) { present = value.isPresent(); }
    }

    private static final class ParsedArguments {
        final Path root;
        final Path module;
        final String command;
        final String[] commandArguments;

        private ParsedArguments(Path root, Path module, String command, String[] commandArguments) {
            this.root = root;
            this.module = module;
            this.command = command;
            this.commandArguments = commandArguments;
        }

        static ParsedArguments parse(String[] arguments) {
            if (arguments.length < 4 || !"--root".equals(arguments[0]) || !"--module".equals(arguments[2])) {
                throw new IllegalArgumentException("用法：ControlMain --root DIR --module DIR COMMAND [ARGS]");
            }
            if (arguments.length < 5) throw new IllegalArgumentException("必须指定命令");
            String[] commandArguments = new String[arguments.length - 5];
            System.arraycopy(arguments, 5, commandArguments, 0, commandArguments.length);
            return new ParsedArguments(Paths.get(arguments[1]), Paths.get(arguments[3]), arguments[4], commandArguments);
        }
    }
}
