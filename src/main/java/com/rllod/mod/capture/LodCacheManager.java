package com.rllod.mod.capture;

import com.rllod.mod.RlLodMod;
import com.rllod.mod.config.RlLodConfig;
import com.rllod.mod.render.LodRenderManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LodCacheManager {

    public static final LodCacheManager INSTANCE = new LodCacheManager();

    private static final int MAX_CAPTURES_PER_TICK = 2;
    private static final int MAX_RADAR_SCANS_PER_TICK = 16;
    private static final int MAX_RADAR_SEARCH_STEPS_PER_TICK = 256;

    private final ConcurrentHashMap<Long, LodChunkSnapshot> snapshotCache = new ConcurrentHashMap<>(4096);
    private final Queue<Long> pendingCaptureQueue = new ArrayDeque<>(512);
    private boolean diskCachePreloaded = false;

    // Singleplayer Saved-Chunk Radar state
    private int radarRadius = 2;
    private int radarLeg = 0;
    private int radarStep = 0;
    private int radarCenterChunkX = Integer.MIN_VALUE;
    private int radarCenterChunkZ = Integer.MIN_VALUE;
    private int radarCooldown = 0;

    private final ExecutorService meshWorkerPool = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2 - 1),
            r -> {
                Thread t = new Thread(r, "RlLOD-Worker");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
    );

    private LodCacheManager() {}

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        World world = event.getWorld();
        if (world == null || !world.isRemote) {
            return;
        }
        Chunk chunk = event.getChunk();
        if (chunk != null) {
            long key = LodChunkSnapshot.toChunkKey(chunk.x, chunk.z);
            if (!snapshotCache.containsKey(key)) {
                final int cx = chunk.x;
                final int cz = chunk.z;
                meshWorkerPool.submit(() -> {
                    LodChunkSnapshot diskSnapshot = LodDiskCache.loadSnapshot(cx, cz);
                    if (diskSnapshot != null) {
                        snapshotCache.put(key, diskSnapshot);
                        LodRenderManager.INSTANCE.markChunkDirty(cx, cz);
                    }
                });
            }
            pendingCaptureQueue.offer(key);
        }
    }

    public void markChunkAndNeighborsDirty(int chunkX, int chunkZ) {
        LodRenderManager.INSTANCE.markChunkDirty(chunkX, chunkZ);
        if ((chunkX & 3) == 0) LodRenderManager.INSTANCE.markChunkDirty(chunkX - 1, chunkZ);
        if ((chunkX & 3) == 3) LodRenderManager.INSTANCE.markChunkDirty(chunkX + 1, chunkZ);
        if ((chunkZ & 3) == 0) LodRenderManager.INSTANCE.markChunkDirty(chunkX, chunkZ - 1);
        if ((chunkZ & 3) == 3) LodRenderManager.INSTANCE.markChunkDirty(chunkX, chunkZ + 1);
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        World world = event.getWorld();
        if (world == null || !world.isRemote) {
            return;
        }
        Chunk chunk = event.getChunk();
        if (chunk != null && chunk.isLoaded() && !chunk.isEmpty()) {
            try {
                LodChunkSnapshot snapshot = ChunkSurfaceExtractor.extract(world, chunk);
                long key = LodChunkSnapshot.toChunkKey(chunk.x, chunk.z);
                snapshotCache.put(key, snapshot);
                final LodChunkSnapshot toSave = snapshot;
                meshWorkerPool.submit(() -> LodDiskCache.saveSnapshot(toSave));
                markChunkAndNeighborsDirty(chunk.x, chunk.z);
            } catch (Throwable t) {
                RlLodMod.LOGGER.debug("[RlLOD] Failed unload capture for chunk ({}, {})", chunk.x, chunk.z, t);
            }
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            return;
        }

        int playerChunkX = ((int) Math.floor(mc.player.posX)) >> 4;
        int playerChunkZ = ((int) Math.floor(mc.player.posZ)) >> 4;

        // 1. Initial disk cache preload (loads closest to player first)
        if (!diskCachePreloaded) {
            diskCachePreloaded = true;
            meshWorkerPool.submit(() -> {
                LodDiskCache.preloadCachedSnapshots(playerChunkX, playerChunkZ, snapshot -> {
                    long key = LodChunkSnapshot.toChunkKey(snapshot.chunkX, snapshot.chunkZ);
                    LodChunkSnapshot prev = snapshotCache.putIfAbsent(key, snapshot);
                    if (prev == null) {
                        LodRenderManager.INSTANCE.markChunkDirty(snapshot.chunkX, snapshot.chunkZ);
                    }
                });
            });
        }

        // 2. Process loaded client chunks in pendingCaptureQueue
        int processed = 0;
        while (processed < MAX_CAPTURES_PER_TICK && !pendingCaptureQueue.isEmpty()) {
            Long key = pendingCaptureQueue.poll();
            if (key == null) {
                break;
            }
            int chunkX = (int) (key & 0xFFFFFFFFL);
            int chunkZ = (int) (key >>> 32);

            if (mc.world.getChunkProvider().isChunkGeneratedAt(chunkX, chunkZ)) {
                Chunk chunk = mc.world.getChunk(chunkX, chunkZ);
                if (chunk != null && chunk.isLoaded() && !chunk.isEmpty()) {
                    try {
                        LodChunkSnapshot snapshot = ChunkSurfaceExtractor.extract(mc.world, chunk);
                        snapshotCache.put(key, snapshot);
                        final LodChunkSnapshot toSave = snapshot;
                        meshWorkerPool.submit(() -> LodDiskCache.saveSnapshot(toSave));
                        markChunkAndNeighborsDirty(chunkX, chunkZ);
                        processed++;
                    } catch (Throwable t) {
                        RlLodMod.LOGGER.debug("[RlLOD] Error extracting chunk ({}, {})", chunkX, chunkZ, t);
                    }
                }
            }
        }

        // 3. Singleplayer Saved-Chunk Radar: scan already-generated chunks from .mca region files
        if (mc.isIntegratedServerRunning() && mc.getIntegratedServer() != null && RlLodConfig.autoScanSavedWorldChunks) {
            stepSingleplayerRadar(mc);
        }
    }

    private void stepSingleplayerRadar(Minecraft mc) {
        if (radarCooldown > 0) {
            radarCooldown--;
            return;
        }

        int playerChunkX = ((int) Math.floor(mc.player.posX)) >> 4;
        int playerChunkZ = ((int) Math.floor(mc.player.posZ)) >> 4;

        if (Math.abs(playerChunkX - radarCenterChunkX) > 4 || Math.abs(playerChunkZ - radarCenterChunkZ) > 4) {
            radarCenterChunkX = playerChunkX;
            radarCenterChunkZ = playerChunkZ;
            radarRadius = 2;
            radarLeg = 0;
            radarStep = 0;
        }

        final int maxRadius = RlLodConfig.lodRenderDistanceChunks;
        int scansDispatched = 0;
        int searchSteps = 0;

        while (scansDispatched < MAX_RADAR_SCANS_PER_TICK && searchSteps < MAX_RADAR_SEARCH_STEPS_PER_TICK) {
            searchSteps++;
            if (radarRadius > maxRadius) {
                radarRadius = 2;
                radarLeg = 0;
                radarStep = 0;
                radarCooldown = 40; // 2 second pause before restarting radar sweep
                break;
            }

            int dx, dz;
            switch (radarLeg) {
                case 0: // Top edge: (-R, -R) to (R, -R)
                    dx = -radarRadius + radarStep;
                    dz = -radarRadius;
                    break;
                case 1: // Right edge: (R, -R) to (R, R)
                    dx = radarRadius;
                    dz = -radarRadius + radarStep;
                    break;
                case 2: // Bottom edge: (R, R) to (-R, R)
                    dx = radarRadius - radarStep;
                    dz = radarRadius;
                    break;
                case 3: // Left edge: (-R, R) to (-R, -R)
                default:
                    dx = -radarRadius;
                    dz = radarRadius - radarStep;
                    break;
            }

            radarStep++;
            if (radarStep >= (2 * radarRadius)) {
                radarStep = 0;
                radarLeg++;
                if (radarLeg >= 4) {
                    radarLeg = 0;
                    radarRadius++;
                }
            }

            final int targetChunkX = radarCenterChunkX + dx;
            final int targetChunkZ = radarCenterChunkZ + dz;
            final long targetKey = LodChunkSnapshot.toChunkKey(targetChunkX, targetChunkZ);

            if (snapshotCache.containsKey(targetKey)) {
                continue;
            }

            final int dimensionId = mc.player.dimension;
            meshWorkerPool.submit(() -> {
                try {
                    Minecraft client = Minecraft.getMinecraft();
                    if (!client.isIntegratedServerRunning() || client.getIntegratedServer() == null) {
                        return;
                    }
                    File baseWorldDir = client.getIntegratedServer().getWorld(0).getSaveHandler().getWorldDirectory();
                    File dimDir = AnvilRegionScanner.getDimensionDirectory(baseWorldDir, dimensionId);

                    LodChunkSnapshot snapshot = LodDiskCache.loadSnapshot(targetChunkX, targetChunkZ);
                    if (snapshot == null) {
                        snapshot = AnvilRegionScanner.scanChunk(dimDir, targetChunkX, targetChunkZ);
                        if (snapshot != null) {
                            LodDiskCache.saveSnapshot(snapshot);
                        }
                    }

                    if (snapshot != null) {
                        snapshotCache.put(targetKey, snapshot);
                        LodRenderManager.INSTANCE.markChunkDirty(targetChunkX, targetChunkZ);
                    } else if (RlLodConfig.autoGenerateUnexploredChunks) {
                        DistantSurfaceGenerator.INSTANCE.queueChunk(targetChunkX, targetChunkZ);
                    }
                } catch (Throwable t) {
                    RlLodMod.LOGGER.debug("[RlLOD] Radar error for chunk ({}, {})", targetChunkX, targetChunkZ, t);
                }
            });

            scansDispatched++;
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld() != null && event.getWorld().isRemote) {
            diskCachePreloaded = false;
            pendingCaptureQueue.clear();
            snapshotCache.clear();
            DistantSurfaceGenerator.INSTANCE.clearQueue();
            LodRenderManager.INSTANCE.clearAllGlResources();
            radarRadius = 2;
            radarLeg = 0;
            radarStep = 0;
            radarCenterChunkX = Integer.MIN_VALUE;
            radarCenterChunkZ = Integer.MIN_VALUE;
            radarCooldown = 0;
            RlLodMod.LOGGER.info("[RlLOD] Cleared client LOD cache and radar on world unload.");
        }
    }

    public void evictOutOfRangeSnapshots(int playerChunkX, int playerChunkZ, int maxDistChunks) {
        long maxDistSq = (long) maxDistChunks * maxDistChunks;
        Iterator<Map.Entry<Long, LodChunkSnapshot>> iter = snapshotCache.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Long, LodChunkSnapshot> entry = iter.next();
            LodChunkSnapshot snap = entry.getValue();
            long dx = snap.chunkX - playerChunkX;
            long dz = snap.chunkZ - playerChunkZ;
            if (dx * dx + dz * dz > maxDistSq) {
                iter.remove();
            }
        }
    }

    public void resetRadar(int playerChunkX, int playerChunkZ) {
        radarCenterChunkX = playerChunkX;
        radarCenterChunkZ = playerChunkZ;
        radarRadius = 2;
        radarLeg = 0;
        radarStep = 0;
        radarCooldown = 0;
    }

    public ConcurrentHashMap<Long, LodChunkSnapshot> getSnapshotCache() {
        return snapshotCache;
    }

    public ExecutorService getMeshWorkerPool() {
        return meshWorkerPool;
    }
}
