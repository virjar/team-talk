package com.virjar.tk.server.sys.service.trace.impl;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class WheelSlotFilter {

    private final ArrayList<AtomicLong> slots;
    private final boolean all;

    public WheelSlotFilter(boolean all) {
        this.all = all;
        this.slots = new ArrayList<>(30);
        for (int i = 0; i < 30; i++) {
            this.slots.add(new AtomicLong());
        }
    }

    public boolean acquireRecorder(boolean debug) {
        if (all || debug) {
            return true;
        }
        long nowTime = System.currentTimeMillis();
        int slotIndex = ((int) ((nowTime / 1000) % 60)) / 2;
        long timeMinute = nowTime / 60000;

        AtomicLong slot = slots.get(slotIndex);

        long slotTime = slot.get();
        if (slotTime == timeMinute) {
            return false;
        }
        return slot.compareAndSet(slotTime, timeMinute);
    }


}
