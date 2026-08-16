package com.vmq.ksu;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

final class AtomicFiles {
    interface TextUpdater {
        String update(String current) throws IOException;
    }

    private AtomicFiles() {
    }

    static void write(Path destination, String contents) throws IOException {
        update(destination, new TextUpdater() {
            @Override public String update(String current) {
                return contents;
            }
        });
    }

    static void update(Path destination, TextUpdater updater) throws IOException {
        Path absoluteDestination = destination.toAbsolutePath();
        Path parent = absoluteDestination.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path lockPath = absoluteDestination.resolveSibling(absoluteDestination.getFileName() + ".lock");
        Path temporary = null;
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            secure(lockPath);
            try (FileLock ignored = channel.lock()) {
                String current = Files.exists(absoluteDestination)
                        ? new String(Files.readAllBytes(absoluteDestination), StandardCharsets.UTF_8) : "";
                String contents = updater.update(current);
                byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
                temporary = Files.createTempFile(parent, absoluteDestination.getFileName() + ".", ".tmp");
                secure(temporary);
                try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                    output.write(bytes);
                    output.flush();
                    output.getFD().sync();
                }
                try {
                    Files.move(temporary, absoluteDestination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, absoluteDestination, StandardCopyOption.REPLACE_EXISTING);
                }
                secure(absoluteDestination);
            }
        } finally {
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }

    static void secure(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows host tests do not expose POSIX file permissions.
        }
    }
}
