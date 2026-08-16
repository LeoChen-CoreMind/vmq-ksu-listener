package com.vmq.ksu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RuntimeState {
    private final Path path;

    public RuntimeState(Path path) {
        this.path = path;
    }

    public synchronized void mergeListener(Map<String, Object> listenerValues) throws IOException {
        AtomicFiles.update(path, new AtomicFiles.TextUpdater() {
            @Override public String update(String current) {
                Map<String, Object> root = current.trim().isEmpty()
                        ? new LinkedHashMap<String, Object>() : JsonCodec.parseObject(current);
                LinkedHashMap<String, Object> listener = new LinkedHashMap<String, Object>();
                Object existing = root.get("listener");
                if (existing instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existingListener = (Map<String, Object>) existing;
                    listener.putAll(existingListener);
                }
                listener.putAll(listenerValues);
                root.put("listener", listener);
                return JsonCodec.stringify(root) + "\n";
            }
        });
    }

    public synchronized String publicStatus(Config config) throws IOException {
        Map<String, Object> root = readRoot();
        root.put("config", config.toPublicMap());
        return JsonCodec.stringify(root);
    }

    private Map<String, Object> readRoot() throws IOException {
        if (!Files.exists(path) || Files.size(path) == 0L) return new LinkedHashMap<String, Object>();
        return JsonCodec.parseObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }
}
