package com.vmq.ksu;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ModuleMetadataTest {
    private ModuleMetadataTest() {
    }

    public static void run() throws Exception {
        Path directory = Files.createTempDirectory("vmq-module-metadata-test");
        Path property = directory.resolve("module.prop");
        String original = "id=vmq_ksu\nname=V免签通知监听\nversion=v1.0.0\n"
                + "description=旧说明\nauthor=LeoChen\n";
        Files.write(property, original.getBytes(StandardCharsets.UTF_8));

        require(ModuleMetadata.updateDescription(property, true), "running description updates");
        String running = read(property);
        require(running.contains("description=" + ModuleMetadata.RUNNING_DESCRIPTION), "running description stored");
        require(running.contains("id=vmq_ksu\nname=V免签通知监听\nversion=v1.0.0\n"), "metadata prefix preserved");
        require(running.endsWith("author=LeoChen\n"), "metadata suffix preserved");
        require(!ModuleMetadata.updateDescription(property, true), "same description avoids a rewrite");

        require(ModuleMetadata.updateDescription(property, false), "stopped description updates");
        String stopped = read(property);
        require(stopped.contains("description=" + ModuleMetadata.STOPPED_DESCRIPTION), "stopped description stored");
        require(!stopped.contains(ModuleMetadata.RUNNING_DESCRIPTION), "running description replaced");
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
