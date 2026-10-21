package com.rllod.mod.capture;

import com.rllod.mod.RlLodMod;
import com.rllod.mod.config.RlLodConfig;
import com.rllod.mod.meshing.LodMeshBuilder;
import com.rllod.mod.render.LodRenderManager;
import net.minecraft.client.Minecraft;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class DistantSurfaceGenerator {

    public static final DistantSurfaceGenerator INSTANCE = new DistantSurfaceGenerator();

    private static final int MAX_FAST_GEN_PER_TICK = 2;
    private static final double MAX_ALLOWABLE_TICK_TIME_MS = 25.0;
    private static final long MAX_TICK_BUDGET_NANOS = 6_000_000L; // 6 ms max CPU per tick

    private final ConcurrentLinkedQueue<Long> incomingFastQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> fastQueuedSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final List<Long> pendingFastList = new ArrayList<>(1024);

    private final ConcurrentLinkedQueue<Long> incomingUpgradeQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> upgradeQueuedSet = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private int lastSortChunkX = Integer.MIN_VALUE;
    private int lastSortChunkZ = Integer.MIN_VALUE;
    private int serverTickCounter = 0;

    private DistantSurfaceGenerator() {}

    public void queueChunk(int chunkX, int chunkZ) {
        long key = LodChunkSnapshot.toChunkKey(chunkX, chunkZ);
        if (fastQueuedSet.add(key)) {
            incomingFastQueue.offer(key);
        }
    }

    public void queueUpgrade(int chunkX, int chunkZ) {
        long key = LodChunkSnapshot.toChunkKey(chunkX, chunkZ);
        if (upgradeQueuedSet.add(key)) {
            incomingUpgradeQueue.offer(key);
        }
    }

    public void clearQueue() {
        incomingFastQueue.clear();
        fastQueuedSet.clear();
        pendingFastList.clear();

        incomingUpgradeQueue.clear();
        upgradeQueuedSet.clear();

        lastSortChunkX = Integer.MIN_VALUE;
        lastSortChunkZ = Integer.MIN_VALUE;
        serverTickCounter = 0;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        if (!RlLodConfig.autoGenerateUnexploredChunks) {
            return;
        }

        serverTickCounter++;

        Minecraft mc = Minecraft.getMinecraft();
        if (!mc.isIntegratedServerRunning() || mc.getIntegratedServer() == null || mc.player == null) {
            return;
        }

        IntegratedServer server = mc.getIntegratedServer();

        // Protect TPS: Throttle when integrated server tick time exceeds threshold
        long[] tickTimes = server.tickTimeArray;
        if (tickTimes != null && tickTimes.length > 0) {
            long sum = 0;
            for (long t : tickTimes) {
                sum += t;
            }
            double avgTickMs = (sum / (double) tickTimes.length) * 1.0E-6D;
            if (avgTickMs > MAX_ALLOWABLE_TICK_TIME_MS) {
                return;
            }
        }

        int dimId = mc.player.dimension;
        WorldServer ws = server.getWorld(dimId);
        if (ws == null) {
            return;
        }

        ChunkProviderServer provider = ws.getChunkProvider();
        if (provider == null) {
            return;
        }

        // Drain incoming queues
        while (!incomingFastQueue.isEmpty()) {
            Long k = incomingFastQueue.poll();
            if (k != null) {
                pendingFastList.add(k);
            }
        }
        while (!incomingUpgradeQueue.isEmpty()) {
            Long k = incomingUpgradeQueue.poll();
            if (k != null) {
                pendingFastList.add(k);
            }
        }

        final int playerChunkX = ((int) Math.floor(mc.player.posX)) >> 4;
        final int playerChunkZ = ((int) Math.floor(mc.player.posZ)) >> 4;

        File baseWorldDir = ws.getSaveHandler().getWorldDirectory();
        File dimDir = AnvilRegionScanner.getDimensionDirectory(baseWorldDir, dimId);

        if (pendingFastList.isEmpty()) {
            return;
        }

        // Inside-Out Sort: sort by distSq to player ascending when player moves >= 4 chunks
        if (Math.abs(playerChunkX - lastSortChunkX) >= 4 || Math.abs(playerChunkZ - lastSortChunkZ) >= 4) {
            lastSortChunkX = playerChunkX;
            lastSortChunkZ = playerChunkZ;

            pendingFastList.sort((k1, k2) -> {
                int x1 = (int) (k1 & 0xFFFFFFFFL);
                int z1 = (int) (k1 >>> 32);
                int x2 = (int) (k2 & 0xFFFFFFFFL);
                int z2 = (int) (k2 >>> 32);
                return Long.compare(distSq(x1, z1, playerChunkX, playerChunkZ), distSq(x2, z2, playerChunkX, playerChunkZ));
            });
        }

        long tickStartNanos = System.nanoTime();

        // -------------------------------------------------------------
        // Fast Surface-First Generation
        // NEVER increment spiral/pending queue unless a snapshot was stored!
        // -------------------------------------------------------------
        int fastGenerated = 0;
        while (fastGenerated < MAX_FAST_GEN_PER_TICK && !pendingFastList.isEmpty()) {
            if ((System.nanoTime() - tickStartNanos) > MAX_TICK_BUDGET_NANOS) {
                // Time budget reached! Chunk remains at front of queue for next tick.
                break;
            }

            Long key = pendingFastList.get(0);
            if (key == null) {
                pendingFastList.remove(0);
                continue;
            }

            int cx = (int) (key & 0xFFFFFFFFL);
            int cz = (int) (key >>> 32);

            if (LodCacheManager.INSTANCE.getSnapshotCache().containsKey(key)) {
                pendingFastList.remove(0);
                fastQueuedSet.remove(key);
                continue;
            }

            try {
                // 1. If chunk is already loaded in provider, extract directly
                Chunk loaded = provider.getLoadedChunk(cx, cz);
                if (loaded != null && loaded.isLoaded()) {
                    LodChunkSnapshot snapshot = ChunkSurfaceExtractor.extract(ws, loaded);
                    LodDiskCache.saveSnapshot(snapshot);
                    LodCacheManager.INSTANCE.getSnapshotCache().put(key, snapshot);
                    LodRenderManager.INSTANCE.markChunkDirty(cx, cz);
                    pendingFastList.remove(0);
                    fastQueuedSet.remove(key);
                    fastGenerated++;
                    continue;
                }

                // 2. Check if already generated and saved in .mca region file on disk
                LodChunkSnapshot diskSnapshot = AnvilRegionScanner.scanChunk(dimDir, cx, cz);
                if (diskSnapshot != null) {
                    LodDiskCache.saveSnapshot(diskSnapshot);
                    LodCacheManager.INSTANCE.getSnapshotCache().put(key, diskSnapshot);
                    LodRenderManager.INSTANCE.markChunkDirty(cx, cz);
                    pendingFastList.remove(0);
                    fastQueuedSet.remove(key);
                    fastGenerated++;
                    continue;
                }

                // 3. Fast terrain generation: generate transient Chunk in memory without registering in ChunkProviderServer
                // Only if the chunk does NOT exist in .mca on disk at all, generate at most 1 chunk every 2 server ticks
                if ((serverTickCounter % 2) != 0) {
                    // Waiting for tick alignment: keep chunk at index 0 and exit tick
                    return;
                }

                IChunkGenerator generator = provider.chunkGenerator;
                if (generator != null) {
                    Chunk fastChunk = null;
                    try {
                        fastChunk = generator.generateChunk(cx, cz);
                    } catch (Throwable t) {
                        fastChunk = null;
                    }

                    if (fastChunk != null) {
                        LodChunkSnapshot snapshot = ChunkSurfaceExtractor.extract(ws, fastChunk);
                        LodDiskCache.saveSnapshot(snapshot);
                        LodCacheManager.INSTANCE.getSnapshotCache().put(key, snapshot);
                        LodRenderManager.INSTANCE.markChunkDirty(cx, cz);
                        pendingFastList.remove(0);
                        fastQueuedSet.remove(key);
                        fastGenerated++;
                        return; // Max 1 transient chunk per 2 ticks
                    }
                }

                // Fallback guarantee: Never drop a polled chunk without putting a snapshot in cache
                LodChunkSnapshot fallback = createDefaultFallbackSnapshot(cx, cz, 64, 0x5B8C32);
                LodDiskCache.saveSnapshot(fallback);
                LodCacheManager.INSTANCE.getSnapshotCache().put(key, fallback);
                LodRenderManager.INSTANCE.markChunkDirty(cx, cz);
                pendingFastList.remove(0);
                fastQueuedSet.remove(key);
                fastGenerated++;
            } catch (Throwable t) {
                RlLodMod.LOGGER.debug("[RlLOD] Fast surface generation error at chunk ({}, {})", cx, cz, t);
                LodChunkSnapshot fallback = createDefaultFallbackSnapshot(cx, cz, 64, 0x5B8C32);
                LodDiskCache.saveSnapshot(fallback);
                LodCacheManager.INSTANCE.getSnapshotCache().put(key, fallback);
                LodRenderManager.INSTANCE.markChunkDirty(cx, cz);
                pendingFastList.remove(0);
                fastQueuedSet.remove(key);
                fastGenerated++;
            }
        }
    }

    private static LodChunkSnapshot createDefaultFallbackSnapshot(int chunkX, int chunkZ, int defaultY, int defaultRgb) {
        long[] cols = new long[512];
        long packed = LodColumnPacker.pack(defaultY, 0, defaultRgb, 15, 0, 0, 0);
        Arrays.fill(cols, 0, 256, packed);
        return new LodChunkSnapshot(chunkX, chunkZ, cols);
    }

    private static long distSq(int cx, int cz, int px, int pz) {
        long dx = cx - px;
        long dz = cz - pz;
        return dx * dx + dz * dz;
    }
}
