package com.virjar.tk.server.sys.service.trace;


import com.virjar.tk.server.sys.service.safethread.Looper;
import com.virjar.tk.server.sys.service.trace.impl.SubscribeRecorders;
import com.virjar.tk.server.sys.service.trace.utils.StringSplitter;
import com.virjar.tk.server.sys.service.trace.utils.ThrowablePrinter;

import java.util.Collection;
import java.util.LinkedList;
import java.util.function.Function;

public abstract class Recorder {
    private static final ThreadLocal<Recorder> THREAD_LOCAL = new ThreadLocal<>();
    protected static final Looper thread = new Looper("eventRecorder").startLoop();

    public static Looper workThread() {
        return thread;
    }


    public void recordEvent(String message) {
        recordEvent(() -> message, (Throwable) null);
    }

    public void recordEvent(String message, Throwable throwable) {
        recordEvent(() -> message, throwable);
    }

    public void recordEvent(MessageGetter messageGetter) {
        recordEvent(messageGetter, (Throwable) null);
    }

    public <T> void recordEvent(T t, Function<T, String> fuc) {
        recordEvent(() -> fuc.apply(t));
    }

    public abstract void recordEvent(MessageGetter messageGetter, Throwable throwable);

    public void recordMosaicMsgIfSubscribeRecorder(MessageGetter message) {
        if (this instanceof SubscribeRecorders.SubscribeRecorder) {
            SubscribeRecorders.SubscribeRecorder s = (SubscribeRecorders.SubscribeRecorder) this;
            s.recordMosaicMsg(message);
        } else {
            recordEvent(message);
        }
    }

    protected Collection<String> splitMsg(String msg, Throwable throwable) {
        Collection<String> strings = StringSplitter.split(msg, '\n');
        if (throwable == null) {
            return strings;
        }
        if (strings.isEmpty()) {
            // 确保可以被编辑
            strings = new LinkedList<>();
        }
        ThrowablePrinter.printStackTrace(strings, throwable);
        return strings;
    }

    public interface MessageGetter {
        String getMessage();
    }

    public static final Recorder nop = new Recorder() {
        @Override
        public void recordEvent(MessageGetter messageGetter, Throwable throwable) {

        }
    };
    public void attach() {
        THREAD_LOCAL.set(this);
    }


    public void detach() {
        THREAD_LOCAL.remove();
    }

    public static Recorder current() {
        Recorder recorder = THREAD_LOCAL.get();
        return recorder == null ? nop : recorder;
    }
}
