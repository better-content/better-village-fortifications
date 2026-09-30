package com.bettercontent.villagewalls.world;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageChunkPreloaderTest {
    @Test
    void limitsRequestsAndReleasesOnlyRequestedChunks() {
        FakeLoader loader = new FakeLoader();
        ChunkPos alreadyLoaded = new ChunkPos(0, 0);
        loader.loaded.add(alreadyLoaded);
        VillageChunkPreloader preloader = new VillageChunkPreloader(loader);
        List<ChunkPos> targets = List.of(alreadyLoaded, new ChunkPos(1, 0), new ChunkPos(2, 0), new ChunkPos(3, 0));
        assertTrue(preloader.addTargets(targets, 4));
        assertEquals(new VillageChunkPreloader.TickResult(VillageChunkPreloader.Status.WAITING, 2), preloader.tick(2, 2));
        assertEquals(2, preloader.inFlight());
        assertEquals(new VillageChunkPreloader.TickResult(VillageChunkPreloader.Status.WAITING, 0), preloader.tick(2, 0));

        loader.finish(new ChunkPos(1, 0));
        assertEquals(new VillageChunkPreloader.TickResult(VillageChunkPreloader.Status.WAITING, 1), preloader.tick(2, 1));
        loader.finish(new ChunkPos(2, 0));
        loader.finish(new ChunkPos(3, 0));
        assertEquals(new VillageChunkPreloader.TickResult(VillageChunkPreloader.Status.READY, 0), preloader.tick(2, 2));

        preloader.close();
        preloader.close();
        assertEquals(List.of(new ChunkPos(1, 0), new ChunkPos(2, 0), new ChunkPos(3, 0)), loader.released);
        assertFalse(loader.released.contains(alreadyLoaded));
    }

    @Test
    void rejectsOversizedExpansionWithoutChangingTargets() {
        FakeLoader loader = new FakeLoader();
        VillageChunkPreloader preloader = new VillageChunkPreloader(loader);
        assertTrue(preloader.addTargets(List.of(new ChunkPos(0, 0), new ChunkPos(1, 0)), 2));
        assertFalse(preloader.addTargets(List.of(new ChunkPos(2, 0)), 2));
        assertEquals(2, preloader.targetCount());
    }

    @Test
    void failedFutureReleasesTicketOnClose() {
        FakeLoader loader = new FakeLoader();
        VillageChunkPreloader preloader = new VillageChunkPreloader(loader);
        ChunkPos target = new ChunkPos(4, 5);
        preloader.addTargets(List.of(target), 1);
        preloader.tick(1, 1);
        loader.pending.get(target).complete(false);
        assertEquals(VillageChunkPreloader.Status.FAILED, preloader.tick(1, 1).status());
        preloader.close();
        assertEquals(List.of(target), loader.released);
    }

    private static final class FakeLoader implements VillageChunkPreloader.Loader {
        final Set<ChunkPos> loaded = new HashSet<>();
        final Map<ChunkPos, CompletableFuture<Boolean>> pending = new HashMap<>();
        final List<ChunkPos> released = new ArrayList<>();

        @Override
        public boolean hasChunk(ChunkPos chunk) {
            return loaded.contains(chunk);
        }

        @Override
        public CompletableFuture<Boolean> request(ChunkPos chunk) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            pending.put(chunk, future);
            return future;
        }

        @Override
        public void release(ChunkPos chunk) {
            released.add(chunk);
        }

        void finish(ChunkPos chunk) {
            loaded.add(chunk);
            pending.get(chunk).complete(true);
        }
    }
}
