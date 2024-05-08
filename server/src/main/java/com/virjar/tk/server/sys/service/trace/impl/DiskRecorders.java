package com.virjar.tk.server.sys.service.trace.impl;

import com.virjar.tk.server.sys.service.trace.Recorder;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

public class DiskRecorders {

    public static DiskRecorders USER_SESSION = new DiskRecorders("user", false);
    /**
     * 给debug调试使用，传递本参数可以打印全流程日志
     */
    public static DiskRecorders USER_DEBUG_TRACE = new DiskRecorders("user_trace", true);
    public static DiskRecorders IP_SOURCE = new DiskRecorders("ip_source", true);
    public static DiskRecorders IP_TEST = new DiskRecorders("ip_test", false);
    public static DiskRecorders MOCK_SESSION = new DiskRecorders("mock_proxy", false);
    public static DiskRecorders PORTAL = new DiskRecorders("portal", false);
    public static DiskRecorders OTHER = new DiskRecorders("other", false);

    private static final Logger log = LoggerFactory.getLogger("EventTrace");

    @Getter
    private final String tag;
    @Getter
    private final boolean all;
    private final WheelSlotFilter wheelSlotFilter;


    public DiskRecorders(String tag, boolean all) {
        this.tag = tag;
        this.all = all;
        this.wheelSlotFilter = new WheelSlotFilter(all);
    }

    public DiskRecorder acquireRecorder(String sessionId, boolean debug) {
        return this.wheelSlotFilter.acquireRecorder(debug) ?
                new DiskRecorderImpl(sessionId) : nopDiskRecorder;
    }

    public static abstract class DiskRecorder extends Recorder {
        abstract void takeHistory(Consumer<List<String>> consumer);

        abstract void recordBatchEvent(Collection<String> msgLines);

        abstract boolean enable();
    }

    private class DiskRecorderImpl extends DiskRecorder {
        private final String sessionId;

        private LinkedList<String> historyMsg = new LinkedList<>();
        private static final int MAX_HISTORY = 100;

        private DiskRecorderImpl(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        void recordBatchEvent(Collection<String> msgLines) {
            thread.execute(() -> {
                MDC.put("Scene", tag);
                historyMsg.addAll(msgLines);
                int overflow = historyMsg.size() - MAX_HISTORY;
                for (int i = 0; i < overflow; i++) {
                    historyMsg.removeFirst();
                }
                msgLines.forEach(s -> log.info("sessionId:" + sessionId + " -> " + s));
            });
        }

        @Override
        public void recordEvent(MessageGetter messageGetter, Throwable throwable) {
            recordBatchEvent(splitMsg(messageGetter.getMessage(), throwable));
        }

        public void takeHistory(Consumer<List<String>> consumer) {
            thread.execute(() -> {
                LinkedList<String> historySnapshot = historyMsg;
                historyMsg = new LinkedList<>();
                consumer.accept(historySnapshot);
            });
        }

        boolean enable() {
            return true;
        }
    }


    private final static DiskRecorder nopDiskRecorder = new DiskRecorder() {
        public void takeHistory(Consumer<List<String>> consumer) {
            consumer.accept(Collections.emptyList());
        }

        @Override
        public void recordEvent(MessageGetter messageGetter, Throwable throwable) {

        }

        void recordBatchEvent(Collection<String> msgLines) {

        }

        boolean enable() {
            return false;
        }
    };
}
