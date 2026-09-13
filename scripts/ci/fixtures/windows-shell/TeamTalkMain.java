package com.virjar.tk.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Loaded only from a temporary CI seed; never included in a product source set. */
public final class TeamTalkMain {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getenv("TEAMTALK_SMOKE_ROOT"));
        String report = "{\"pid\":" + ProcessHandle.current().pid()
            + ",\"javaHome\":" + quote(System.getProperty("java.home"))
            + ",\"userHome\":" + quote(System.getProperty("user.home"))
            + ",\"launcher\":" + quote(System.getProperty("teamtalk.shell.launcher"))
            + ",\"payloadDir\":" + quote(System.getProperty("teamtalk.payload.dir"))
            + ",\"version\":" + quote(System.getProperty("teamtalk.payload.version"))
            + ",\"build\":" + quote(System.getProperty("teamtalk.payload.build"))
            + ",\"shellAbi\":" + quote(System.getProperty("teamtalk.shell.abi"))
            + ",\"headless\":" + quote(System.getProperty("java.awt.headless"))
            + ",\"args\":[" + Arrays.stream(args).map(TeamTalkMain::quote).collect(Collectors.joining(","))
            + "],\"ready\":true}";
        Path pending = root.resolve("probe.pending");
        Files.writeString(pending, report);
        Files.move(pending, root.resolve("probe.json"), StandardCopyOption.ATOMIC_MOVE);
        // Keep the actual JVM alive until the runner holds its process handle;
        // the Launch4j wrapper is allowed to have exited before this point.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(root.resolve("probe.release"))) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Runner did not release fixture JVM");
            Thread.sleep(20);
        }
    }

    private static String quote(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
