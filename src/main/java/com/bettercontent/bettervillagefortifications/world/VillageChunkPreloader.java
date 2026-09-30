package com.bettercontent.bettervillagefortifications.world;

import net.minecraft.world.level.ChunkPos;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

final class VillageChunkPreloader {
    enum Status { WAITING, READY, FAILED }

    interface Loader {
        boolean hasChunk(ChunkPos chunk);

        CompletableFuture<Boolean> request(ChunkPos chunk);

        void release(ChunkPos chunk);
    }

    private final Loader loader;
    private final Set<ChunkPos> targets = new LinkedHashSet<>();
    private final Set<ChunkPos> held = new LinkedHashSet<>();
    private final Map<ChunkPos, CompletableFuture<Boolean>> pending = new LinkedHashMap<>();
    private boolean closed;

    static <T> CompletableFuture<T> requestAsync(Supplier<CompletableFuture<T>> request) {
        return CompletableFuture.supplyAsync(request).thenCompose(future -> future);
    }

    VillageChunkPreloader(Loader loader) {
        this.loader = loader;
    }

    boolean addTargets(Collection<ChunkPos> chunks, int maximum) {
        Set<ChunkPos> combined = new LinkedHashSet<>(targets);
        combined.addAll(chunks);
        if (combined.size() > maximum) {
            return false;
        }
        targets.addAll(chunks);
        return true;
    }

    int inFlight() {
        return pending.size();
    }

    int targetCount() {
        return targets.size();
    }

    TickResult tick(int requestBudget, int outstandingBudget) {
        if (closed) {
            return new TickResult(Status.FAILED, 0);
        }
        for (var iterator = pending.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<ChunkPos, CompletableFuture<Boolean>> entry = iterator.next();
            if (!entry.getValue().isDone()) {
                continue;
            }
            try {
                if (!entry.getValue().join() || !loader.hasChunk(entry.getKey())) {
                    return new TickResult(Status.FAILED, 0);
                }
            } catch (RuntimeException failure) {
                return new TickResult(Status.FAILED, 0);
            }
            iterator.remove();
        }

        int issued = 0;
        for (ChunkPos chunk : targets) {
            if (loader.hasChunk(chunk) || held.contains(chunk)) {
                continue;
            }
            if (issued >= requestBudget || issued >= outstandingBudget) {
                break;
            }
            try {
                CompletableFuture<Boolean> future = loader.request(chunk);
                held.add(chunk);
                pending.put(chunk, future);
                issued++;
            } catch (RuntimeException failure) {
                return new TickResult(Status.FAILED, issued);
            }
        }
        boolean ready = pending.isEmpty() && targets.stream().allMatch(loader::hasChunk);
        return new TickResult(ready ? Status.READY : Status.WAITING, issued);
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (ChunkPos chunk : held) {
            loader.release(chunk);
        }
        held.clear();
        pending.clear();
    }

    record TickResult(Status status, int issued) {
    }
}
