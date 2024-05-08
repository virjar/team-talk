package com.virjar.tk.server.sys.service.trace.impl;

import com.virjar.tk.server.sys.service.trace.Recorder;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class SubscribeRecorders {
    private static final Map<String, SubscribeRecorders> recorderCategories = Maps.newHashMap();

    public static SubscribeRecorders fromTag(@NotBlank String tag) {
        return recorderCategories.get(tag.toLowerCase());
    }

    public static SubscribeRecorders USER_SESSION = new SubscribeRecorders(DiskRecorders.USER_SESSION, false);
    public static SubscribeRecorders USER_DEBUG_TRACE = new SubscribeRecorders(DiskRecorders.USER_DEBUG_TRACE, true);
    public static SubscribeRecorders IP_SOURCE = new SubscribeRecorders(DiskRecorders.IP_SOURCE, true);
    public static SubscribeRecorders IP_TEST = new SubscribeRecorders(DiskRecorders.IP_TEST, true);
    public static SubscribeRecorders MOCK_SESSION = new SubscribeRecorders(DiskRecorders.MOCK_SESSION, true);
    public static SubscribeRecorders PORTAL = new SubscribeRecorders(DiskRecorders.PORTAL, true);
    public static SubscribeRecorders OTHER = new SubscribeRecorders(DiskRecorders.OTHER, false);


    private final Multimap<String, Listener> listenerRegistry = HashMultimap.create();
    private final Map<String, WheelSlotFilter> scopeSlotFilters = Maps.newHashMap();
    private final DiskRecorders lowLevel;

    @Getter
    private final String tag;


    private final boolean lowLevelAll;

    @Getter
    private final boolean forAdmin;

    public SubscribeRecorders(DiskRecorders lowLevel, boolean forAdmin) {
        this.lowLevel = lowLevel;
        this.forAdmin = forAdmin;
        this.tag = lowLevel.getTag().toLowerCase();
        this.lowLevelAll = lowLevel.isAll();
        recorderCategories.put(tag, this);
    }

    public void registerListener(String scope, Listener listener) {
        Recorder.workThread().execute(() -> listenerRegistry.put(scope, listener));
    }

    public void unregisterListener(String scope, Listener listener) {
        Recorder.workThread().execute(() -> listenerRegistry.remove(scope, listener));
    }

    public SubscribeRecorder acquireRecorder(boolean debug) {
        return acquireRecorder(debug, "default");
    }

    public SubscribeRecorder acquireRecorder(boolean debug, String scope) {
        return acquireRecorder(UUID.randomUUID().toString(), debug, scope);
    }

    public SubscribeRecorder acquireRecorder(String sessionId, boolean debug, String scope) {
        return new SubscribeRecorder(sessionId, lowLevel.acquireRecorder(sessionId, debug), scope);
    }

    public class SubscribeRecorder extends Recorder {

        private final DiskRecorders.DiskRecorder lowLevel;
        private String[] scopes;
        private boolean subscribeTicket;
        private final String sessionId;


        public SubscribeRecorder(String sessionId, DiskRecorders.DiskRecorder lowLevel, String scope) {
            this.lowLevel = lowLevel;
            this.sessionId = sessionId;
            this.scopes = new String[]{scope};
            refreshSlotTick();
        }

        private void refreshSlotTick() {
            if (lowLevelAll) {
                subscribeTicket = true;
                return;
            }
            Recorder.workThread().execute(() -> {
                boolean hint = false;
                for (String scope : scopes) {
                    boolean ticket = scopeSlotFilters
                            .computeIfAbsent(scope, (k) -> new WheelSlotFilter(false))
                            .acquireRecorder(false);
                    if (ticket) {
                        hint = true;
                    }
                }
                subscribeTicket = hint;
            });

        }

        public void takeHistory(Consumer<List<String>> consumer) {
            lowLevel.takeHistory(consumer);
        }

        public void recordBatchEvent(Collection<String> msgLines) {
            lowLevel.recordBatchEvent(msgLines);
        }

        @Override
        public void recordEvent(MessageGetter messageGetter, Throwable throwable) {
            List<Listener> targetListenerList = Lists.newArrayList();
            for (String scope : scopes) {
                Collection<Listener> listeners = listenerRegistry.get(scope);
                targetListenerList.addAll(listeners);
            }

            if (!lowLevel.enable() && (targetListenerList.isEmpty() || !subscribeTicket)) {
                // this indicate no log print need
                return;
            }

            thread.execute(() -> {
                Collection<String> msgLines = splitMsg(messageGetter.getMessage(), throwable);
                lowLevel.recordBatchEvent(msgLines);

                if (subscribeTicket) {
                    targetListenerList.forEach(listener -> msgLines.forEach(s -> listener.onLogMsg(sessionId, s)));
                }
            });
        }

        public void changeScope(String... scope) {
            this.scopes = scope;
            refreshSlotTick();
        }


        public void recordMosaicMsg(MessageGetter message) {
            recordEvent(() -> NOT_SHOW_FOR_NORMAL_USER + message.getMessage());
        }


    }

    private static final String NOT_SHOW_FOR_NORMAL_USER = "nsfnu:";

    public static boolean isMosaicMsg(String msg) {
        return msg.startsWith(NOT_SHOW_FOR_NORMAL_USER);
    }

    public interface Listener {
        void onLogMsg(String sessionId, String line);
    }
}
