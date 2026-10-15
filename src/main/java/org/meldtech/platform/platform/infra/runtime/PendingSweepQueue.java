package org.meldtech.platform.platform.infra.runtime;

import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.meldtech.platform.platform.infra.runtime.SweepRegistryLoader.RegisteredSweep;

/** Coalesces due signals by name without moving an existing signal behind newer work. */
final class PendingSweepQueue extends AbstractQueue<RegisteredSweep> {

    private final int capacity;
    private final LinkedHashMap<String, RegisteredSweep> pending = new LinkedHashMap<>();
    private long coalesced;

    PendingSweepQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("A sweep lane requires a positive queue capacity");
        }
        this.capacity = capacity;
    }

    @Override
    public synchronized boolean offer(RegisteredSweep sweep) {
        String name = sweep.definition().metadata().name();
        if (pending.containsKey(name)) {
            pending.put(name, sweep);
            coalesced++;
            return true;
        }
        if (pending.size() == capacity) {
            return false;
        }
        pending.put(name, sweep);
        return true;
    }

    @Override
    public synchronized @Nullable RegisteredSweep poll() {
        var iterator = pending.values().iterator();
        if (!iterator.hasNext()) {
            return null;
        }
        RegisteredSweep next = iterator.next();
        iterator.remove();
        return next;
    }

    @Override
    public synchronized @Nullable RegisteredSweep peek() {
        return pending.isEmpty() ? null : pending.values().iterator().next();
    }

    @Override
    public synchronized Iterator<RegisteredSweep> iterator() {
        return List.copyOf(pending.values()).iterator();
    }

    @Override
    public synchronized int size() {
        return pending.size();
    }

    @Override
    public synchronized void clear() {
        pending.clear();
    }

    synchronized long coalescedCount() {
        return coalesced;
    }
}
