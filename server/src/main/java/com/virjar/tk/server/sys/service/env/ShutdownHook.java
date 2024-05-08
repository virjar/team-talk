package com.virjar.tk.server.sys.service.env;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
public class ShutdownHook {
    private static final Set<Runnable> shutdownHooks = Collections.synchronizedSet(new LinkedHashSet<>());
    private static volatile boolean shutdownHookRunning = false;

    public static void registerShutdownHook(Runnable runnable) {
        HookWrapper hookWrapper = new HookWrapper(runnable);
        shutdownHooks.add(hookWrapper);
        Runtime.getRuntime().addShutdownHook(new Thread(hookWrapper));
    }

    private static class HookWrapper implements Runnable {
        private boolean called = false;
        private final Runnable delegate;

        public HookWrapper(Runnable delegate) {
            this.delegate = delegate;
        }

        @Override
        public void run() {
            synchronized (this) {
                if (called) {
                    return;
                }
                called = true;
            }
            delegate.run();
        }
    }

    public static int prepareShutdown() {
        int nowTaskSize = shutdownHooks.size();
        if (shutdownHookRunning || nowTaskSize == 0) {
            return nowTaskSize;
        }
        synchronized (Environment.class) {
            if (shutdownHookRunning) {
                return nowTaskSize;
            }
            shutdownHookRunning = true;
        }
        new Thread(() -> {
            while (!shutdownHooks.isEmpty()) {
                Runnable next = shutdownHooks.iterator().next();
                try {
                    next.run();
                } catch (Throwable throwable) {
                    log.error("running shutdown hook failed", throwable);
                }
            }
        }).start();
        return shutdownHooks.size();
    }
}
