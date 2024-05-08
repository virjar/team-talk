package com.virjar.tk.server.sys.service.safethread;


import lombok.Getter;

public class ValueCallbackGetter<T> {
    private volatile ValueCallback.Value<T> value;
    private final Object lock = new Object();
    @Getter
    private final ValueCallback<T> callback = value -> {
        ValueCallbackGetter.this.value = value;
        synchronized (lock) {
            lock.notifyAll();
        }
    };

    public T getUncheck() {
        try {
            return get();
        } catch (Throwable throwable) {
            throw new IllegalStateException(throwable);
        }
    }

    public T get() throws Throwable {
        if (value == null) {
            synchronized (lock) {
                if (value == null) {
                    lock.wait();
                }
            }
        }
        if (value.isSuccess()) {
            return value.v;
        }
        throw value.e;
    }

    public interface Setup<T> {
        void setup(ValueCallback<T> callback);
    }

    public static <T> T syncGet(Setup<T> setup) {
        ValueCallbackGetter<T> callbackGetter = new ValueCallbackGetter<>();
        setup.setup(callbackGetter.getCallback());
        return callbackGetter.getUncheck();
    }
}
