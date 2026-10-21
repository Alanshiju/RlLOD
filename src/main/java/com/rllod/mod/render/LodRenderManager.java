package com.rllod.mod.render;

import com.rllod.mod.RlLodMod;
import com.rllod.mod.capture.LodCacheManager;
import com.rllod.mod.capture.LodChunkSnapshot;
import com.rllod.mod.config.RlLodConfig;
import com.rllod.mod.meshing.LodMeshBuilder;
import com.rllod.mod.meshing.LodMeshData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.culling.ClippingHelper;
import net.minecraft.client.renderer.culling.ClippingHelperImpl;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LodRenderManager {

    public static final LodRenderManager INSTANCE = new LodRenderManager();

    private static final int MAX_VBO_UPLOADS_PER_FRAME = 3;
    private static final int MAX_DIRTY_DISPATCH_PER_FRAME = 4;
    private static final float LOD_Z_NEAR = 0.05f;

    public static float whiteU = 0.5f;
    public static float whiteV = 0.5f;

    private final ConcurrentLinkedQueue<LodMeshData> pendingUploads = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Long, LodVboEntry> activeVbos = new ConcurrentHashMap<>(4096);
    private final Set<Long> dirtyRegions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Long> inFlightRegions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ConcurrentHashMap<Long, Long> innerCoreLoadedSinceMs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> vanillaChunkLoadedSinceMs = new ConcurrentHashMap<>();

    private final float[][] cachedFrustumPlanes = new float[5][4];
    private int cachedNumFrustumPlanes = 0;
    private final List<LodVboEntry> visibleVbos = new ArrayList<>(512);
    private final List<Long> sortedDirty = new ArrayList<>(64);

    private final FloatBuffer originalProjBuffer = GLAllocation.createDirectFloatBuffer(16);
    private final FloatBuffer extendedProjBuffer = GLAllocation.createDirectFloatBuffer(16);
    private final FloatBuffer extendedProjInvBuffer = GLAllocation.createDirectFloatBuffer(16);

    private int lastPlayerRegionX = Integer.MIN_VALUE;
    private int lastPlayerRegionZ = Integer.MIN_VALUE;
    private int lastPlayerChunkX = Integer.MIN_VALUE;
    private int lastPlayerChunkZ = Integer.MIN_VALUE;
    private int lastCheckedPlayerChunkX = Integer.MIN_VALUE;
    private int lastCheckedPlayerChunkZ = Integer.MIN_VALUE;
    private long lastEvictionSweepMs = 0L;
    private long lastMaskCheckMs = 0L;
    private int lastFrameRenderedCount = 0;

    private LodRenderManager() {}

    public static void updateWhiteAtlasUv() {
        try {
            TextureAtlasSprite snowSprite = Minecraft.getMinecraft().getTextureMapBlocks().getAtlasSprite("minecraft:blocks/snow");
            if (snowSprite != null) {
                whiteU = snowSprite.getInterpolatedU(8.0);
                whiteV = snowSprite.getInterpolatedV(8.0);
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean shouldRenderLodSubChunk(World world, int cx, int cz, int playerCx, int playerCz, int vanillaDist, long nowMs) {
        boolean insideVanillaSquare = Math.abs(cx - playerCx) <= vanillaDist && Math.abs(cz - playerCz) <= vanillaDist;
        if (!insideVanillaSquare) {
            INSTANCE.innerCoreLoadedSinceMs.remove(LodChunkSnapshot.toChunkKey(cx, cz));
            return true;
        }

        if (world != null && world.getChunkProvider() != null) {
            net.minecraft.world.chunk.Chunk c = world.getChunkProvider().getLoadedChunk(cx, cz);
            boolean readyToHide = (c != null && c.isLoaded() && !c.isEmpty()) && (
                    (Math.abs(cx - playerCx) < vanillaDist && Math.abs(cz - playerCz) < vanillaDist)
                    || (world.getChunkProvider().getLoadedChunk(cx - 1, cz) != null
                        && world.getChunkProvider().getLoadedChunk(cx + 1, cz) != null
                        && world.getChunkProvider().getLoadedChunk(cx, cz - 1) != null
                        && world.getChunkProvider().getLoadedChunk(cx, cz + 1) != null)
            );
            if (readyToHide) {
                long chunkKey = LodChunkSnapshot.toChunkKey(cx, cz);
                Long since = INSTANCE.innerCoreLoadedSinceMs.putIfAbsent(chunkKey, nowMs);
                return (since == null) || ((nowMs - since) < 400L);
            } else {
                return true;
            }
        }

        return true;
    }

    public static boolean shouldRenderLodSubChunk(World world, int cx, int cz, int playerCx, int playerCz, int vanillaDist) {
        return shouldRenderLodSubChunk(world, cx, cz, playerCx, playerCz, vanillaDist, System.currentTimeMillis());
    }

    public static boolean shouldRenderLodSubChunk(World world, int cx, int cz, int playerCx, int playerCz, int vanillaDist, double camY) {
        return shouldRenderLodSubChunk(world, cx, cz, playerCx, playerCz, vanillaDist, System.currentTimeMillis());
    }

    private void setupVertexPointers(int vboId) {
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, vboId);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, LodMeshData.STRIDE_BYTES, 0L);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, LodMeshData.STRIDE_BYTES, 12L);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, LodMeshData.STRIDE_BYTES, 20L);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glTexCoordPointer(2, GL11.GL_SHORT, LodMeshData.STRIDE_BYTES, 24L);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glNormalPointer(GL11.GL_BYTE, LodMeshData.STRIDE_BYTES, 28L);
    }

    public static boolean isBoxInInfiniteFrustum(float[][] planes, int numPlanes, AxisAlignedBB bb, double camX, double camY, double camZ) {
        if (planes == null || numPlanes <= 0) {
            return true;
        }
        double minX = bb.minX - camX;
        double minY = bb.minY - camY;
        double minZ = bb.minZ - camZ;
        double maxX = bb.maxX - camX;
        double maxY = bb.maxY - camY;
        double maxZ = bb.maxZ - camZ;

        for (int i = 0; i < numPlanes; ++i) {
            float[] plane = planes[i];
            if (plane[0] * minX + plane[1] * minY + plane[2] * minZ + plane[3] <= 0.0D &&
                plane[0] * maxX + plane[1] * minY + plane[2] * minZ + plane[3] <= 0.0D &&
                plane[0] * minX + plane[1] * maxY + plane[2] * minZ + plane[3] <= 0.0D &&
                plane[0] * maxX + plane[1] * maxY + plane[2] * minZ + plane[3] <= 0.0D &&
                plane[0] * minX + plane[1] * minY + plane[2] * maxZ + plane[3] <= 0.0D &&
                plane[0] * maxX + plane[1] * minY + plane[2] * maxZ + plane[3] <= 0.0D &&
                plane[0] * minX + plane[1] * maxY + plane[2] * maxZ + plane[3] <= 0.0D &&
                plane[0] * maxX + plane[1] * maxY + plane[2] * maxZ + plane[3] <= 0.0D) {
                return false;
            }
        }
        return true;
    }

    public void markChunkDirty(int chunkX, int chunkZ) {
        int rx = chunkX >> 2;
        int rz = chunkZ >> 2;
        markRegionDirty(rx, rz);
    }

    public void markRegionDirty(int regionX, int regionZ) {
        dirtyRegions.add(LodMeshData.toRegionKey(regionX, regionZ));
    }

    public void markAllRegionsDirty() {
        java.util.Set<Long> regions = new java.util.HashSet<>();
        for (LodVboEntry entry : activeVbos.values()) {
            regions.add(LodMeshData.toRegionKey(entry.regionX, entry.regionZ));
        }
        for (Long chunkKey : LodCacheManager.INSTANCE.getSnapshotCache().keySet()) {
            int cx = (int) (long) chunkKey;
            int cz = (int) (chunkKey >>> 32);
            regions.add(LodMeshData.toRegionKey(cx >> 2, cz >> 2));
        }
        dirtyRegions.addAll(regions);
    }

    public void scheduleMeshBuild(final LodChunkSnapshot snapshot) {
        if (snapshot != null) {
            markChunkDirty(snapshot.chunkX, snapshot.chunkZ);
        }
    }

    public static int calcExpectedLodLevel(int regionX, int regionZ, int playerChunkX, int playerChunkZ, int vanillaDist) {
        int rCenterCx = (regionX << 2) + 2;
        int rCenterCz = (regionZ << 2) + 2;
        int chebDist = Math.max(Math.abs(rCenterCx - playerChunkX), Math.abs(rCenterCz - playerChunkZ));

        if (chebDist <= vanillaDist + 5) {
            return 0; // LOD 0 (1x1 Textured)
        } else if (chebDist <= vanillaDist + 18) {
            return 1; // LOD 1 (2x2 Solid)
        } else if (chebDist <= 44) {
            return 2; // LOD 2 (4x4 Solid)
        } else {
            return 3; // LOD 3 (8x8 Solid)
        }
    }

    public static int calcExpectedLodLevel(int regionX, int regionZ, int playerChunkX, int playerChunkZ) {
        Minecraft mc = Minecraft.getMinecraft();
        int vanillaDist = (mc != null && mc.gameSettings != null) ? mc.gameSettings.renderDistanceChunks : 8;
        return calcExpectedLodLevel(regionX, regionZ, playerChunkX, playerChunkZ, vanillaDist);
    }

    public void scheduleRegionMeshBuild(int regionX, int regionZ, int targetLodLevel) {
        scheduleRegionMeshBuild(regionX, regionZ, targetLodLevel, false);
    }

    public void scheduleRegionMeshBuild(int regionX, int regionZ, int targetLodLevel, boolean ultraDetail) {
        Minecraft mc = Minecraft.getMinecraft();
        int px = (mc.player != null) ? (((int) Math.floor(mc.player.posX)) >> 4) : 0;
        int pz = (mc.player != null) ? (((int) Math.floor(mc.player.posZ)) >> 4) : 0;
        int vanillaDist = (mc.gameSettings != null) ? mc.gameSettings.renderDistanceChunks : 8;
        scheduleRegionMeshBuild(regionX, regionZ, targetLodLevel, ultraDetail, px, pz, vanillaDist);
    }

    public void scheduleRegionMeshBuild(
            int regionX,
            int regionZ,
            int targetLodLevel,
            boolean ultraDetail,
            int playerChunkX,
            int playerChunkZ,
            int vanillaRenderDist
    ) {
        LodChunkSnapshot[] chunks4x4 = new LodChunkSnapshot[16];
        int baseChunkX = regionX << 2;
        int baseChunkZ = regionZ << 2;
        boolean hasAny = false;
        ConcurrentHashMap<Long, LodChunkSnapshot> cache = LodCacheManager.INSTANCE.getSnapshotCache();

        for (int cz = 0; cz < 4; cz++) {
            for (int cx = 0; cx < 4; cx++) {
                long key = LodChunkSnapshot.toChunkKey(baseChunkX + cx, baseChunkZ + cz);
                LodChunkSnapshot snapshot = cache.get(key);
                if (snapshot != null) {
                    chunks4x4[(cz << 2) | cx] = snapshot;
                    hasAny = true;
                }
            }
        }

        if (!hasAny) {
            return;
        }

        ExecutorService pool = LodCacheManager.INSTANCE.getMeshWorkerPool();
        final long regKey = LodMeshData.toRegionKey(regionX, regionZ);
        inFlightRegions.add(regKey);

        pool.submit(() -> {
            try {
                LodMeshData meshData = LodMeshBuilder.buildRegionMesh(
                        regionX,
                        regionZ,
                        targetLodLevel,
                        chunks4x4
                );
                if (meshData.vertexCount > 0) {
                    pendingUploads.offer(meshData);
                }
            } catch (Throwable t) {
                RlLodMod.LOGGER.debug("[RlLOD] Worker mesh build failed for region ({}, {})", regionX, regionZ, t);
            } finally {
                inFlightRegions.remove(regKey);
            }
        });
    }

    public void clearAllGlResources() {
        LodMeshData mesh;
        while ((mesh = pendingUploads.poll()) != null) {
            LodMeshBuilder.freeDirectBuffer(mesh.vertexBuffer);
        }
        pendingUploads.clear();
        dirtyRegions.clear();
        inFlightRegions.clear();
        innerCoreLoadedSinceMs.clear();
        vanillaChunkLoadedSinceMs.clear();
        visibleVbos.clear();
        sortedDirty.clear();
        for (LodVboEntry entry : activeVbos.values()) {
            entry.deleteGlResources();
        }
        activeVbos.clear();
        lastFrameRenderedCount = 0;
        lastPlayerRegionX = Integer.MIN_VALUE;
        lastPlayerRegionZ = Integer.MIN_VALUE;
        lastPlayerChunkX = Integer.MIN_VALUE;
        lastPlayerChunkZ = Integer.MIN_VALUE;
        lastCheckedPlayerChunkX = Integer.MIN_VALUE;
        lastCheckedPlayerChunkZ = Integer.MIN_VALUE;
        lastEvictionSweepMs = 0L;
    }

    private void evictOutOfRangeRegions(int playerChunkX, int playerChunkZ, int maxEvictDistChunks) {
        long maxEvictDistSq = (long) maxEvictDistChunks * maxEvictDistChunks;
        Iterator<Map.Entry<Long, LodVboEntry>> iter = activeVbos.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Long, LodVboEntry> entry = iter.next();
            LodVboEntry vbo = entry.getValue();
            int centerChunkX = (vbo.regionX << 2) + 2;
            int centerChunkZ = (vbo.regionZ << 2) + 2;
            long dx = centerChunkX - playerChunkX;
            long dz = centerChunkZ - playerChunkZ;
            if (dx * dx + dz * dz > maxEvictDistSq) {
                iter.remove();
                vbo.deleteGlResources();
                dirtyRegions.remove(entry.getKey());
            }
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity viewEntity = mc.getRenderViewEntity();
        if (mc.world == null || viewEntity == null) {
            return;
        }

        // 1. Upload pending meshes to GPU VBOs
        uploadPendingMeshes();

        final float partialTicks = event.getPartialTicks();
        final double camX = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * partialTicks;
        final double camY = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * partialTicks;
        final double camZ = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * partialTicks;

        final int playerChunkX = ((int) Math.floor(camX)) >> 4;
        final int playerChunkZ = ((int) Math.floor(camZ)) >> 4;
        final int playerRegionX = playerChunkX >> 2;
        final int playerRegionZ = playerChunkZ >> 2;

        int jumpDist = Math.max(Math.abs(playerChunkX - lastPlayerChunkX), Math.abs(playerChunkZ - lastPlayerChunkZ));
        boolean teleported = (lastPlayerChunkX != Integer.MIN_VALUE && jumpDist > 16);
        int prevChunkX = lastPlayerChunkX;
        int prevChunkZ = lastPlayerChunkZ;
        lastPlayerChunkX = playerChunkX;
        lastPlayerChunkZ = playerChunkZ;

        long nowMs = System.currentTimeMillis();
        if (teleported || (nowMs - lastEvictionSweepMs > 1000L)) {
            lastEvictionSweepMs = nowMs;
            evictOutOfRangeRegions(playerChunkX, playerChunkZ, RlLodConfig.lodRenderDistanceChunks + 6);
            LodCacheManager.INSTANCE.evictOutOfRangeSnapshots(playerChunkX, playerChunkZ, RlLodConfig.lodRenderDistanceChunks + 8);

            // Stored presentChunkMask check: verify each active VBO against snapshotCache every 3000ms
            if (!teleported && (nowMs - lastMaskCheckMs > 3000L)) {
                lastMaskCheckMs = nowMs;
                int markedCount = 0;
                ConcurrentHashMap<Long, LodChunkSnapshot> snapCache = LodCacheManager.INSTANCE.getSnapshotCache();
                for (LodVboEntry vbo : activeVbos.values()) {
                    if (markedCount >= 4) break;
                    long regionKey = LodMeshData.toRegionKey(vbo.regionX, vbo.regionZ);
                    if (dirtyRegions.contains(regionKey) || inFlightRegions.contains(regionKey)) {
                        continue;
                    }
                    int curMask = 0;
                    int baseChunkX = vbo.regionX << 2;
                    int baseChunkZ = vbo.regionZ << 2;
                    for (int scz = 0; scz < 4; scz++) {
                        for (int scx = 0; scx < 4; scx++) {
                            long k = LodChunkSnapshot.toChunkKey(baseChunkX + scx, baseChunkZ + scz);
                            if (snapCache.containsKey(k)) {
                                curMask |= (1 << ((scz << 2) | scx));
                            }
                        }
                    }
                    if (curMask != vbo.presentChunkMask) {
                        markRegionDirty(vbo.regionX, vbo.regionZ);
                        markedCount++;
                    }
                }
            }

            if (teleported) {
                LodMeshData staleMesh;
                while ((staleMesh = pendingUploads.poll()) != null) {
                    LodMeshBuilder.freeDirectBuffer(staleMesh.vertexBuffer);
                }
                LodCacheManager.INSTANCE.resetRadar(playerChunkX, playerChunkZ);
            }
        }

        // 2. Check for LOD tier transitions across 4x4 region boundaries (only check when moved >= 2 chunks)
        int moveDist = Math.max(Math.abs(playerChunkX - lastCheckedPlayerChunkX), Math.abs(playerChunkZ - lastCheckedPlayerChunkZ));
        if (moveDist >= 2 || lastCheckedPlayerChunkX == Integer.MIN_VALUE) {
            lastCheckedPlayerChunkX = playerChunkX;
            lastCheckedPlayerChunkZ = playerChunkZ;
            lastPlayerRegionX = playerRegionX;
            lastPlayerRegionZ = playerRegionZ;
            int vanillaDist = mc.gameSettings.renderDistanceChunks;

            for (LodVboEntry entry : activeVbos.values()) {
                int expectedLod = calcExpectedLodLevel(entry.regionX, entry.regionZ, playerChunkX, playerChunkZ, vanillaDist);
                if (entry.lodLevel != expectedLod) {
                    markRegionDirty(entry.regionX, entry.regionZ);
                }
            }
        }

        // 3. Dispatch up to MAX_DIRTY_DISPATCH_PER_FRAME dirty region rebuilds (closest to player first!)
        if (!dirtyRegions.isEmpty()) {
            sortedDirty.clear();
            sortedDirty.addAll(dirtyRegions);
            sortedDirty.sort((k1, k2) -> {
                int r1x = (int) (long) k1;
                int r1z = (int) (k1 >>> 32);
                int r2x = (int) (long) k2;
                int r2z = (int) (k2 >>> 32);
                long d1 = (long) (r1x - playerRegionX) * (r1x - playerRegionX) + (long) (r1z - playerRegionZ) * (r1z - playerRegionZ);
                long d2 = (long) (r2x - playerRegionX) * (r2x - playerRegionX) + (long) (r2z - playerRegionZ) * (r2z - playerRegionZ);
                return Long.compare(d1, d2);
            });

            int dispatched = 0;
            for (Long regionKey : sortedDirty) {
                if (dispatched >= MAX_DIRTY_DISPATCH_PER_FRAME) {
                    break;
                }
                dirtyRegions.remove(regionKey);
                int rx = (int) (regionKey & 0xFFFFFFFFL);
                int rz = (int) (regionKey >>> 32);
                int vanillaRenderDist = mc.gameSettings.renderDistanceChunks;
                int targetLod = calcExpectedLodLevel(rx, rz, playerChunkX, playerChunkZ, vanillaRenderDist);
                boolean ultraDetail = (targetLod == 0);
                scheduleRegionMeshBuild(rx, rz, targetLod, ultraDetail, playerChunkX, playerChunkZ, vanillaRenderDist);
                dispatched++;
            }
        }

        if (activeVbos.isEmpty()) {
            lastFrameRenderedCount = 0;
            return;
        }

        final int maxLodDistanceChunks = RlLodConfig.lodRenderDistanceChunks;
        final int maxDistSq = maxLodDistanceChunks * maxLodDistanceChunks;
        final int vanillaRenderDistChunks = mc.gameSettings.renderDistanceChunks;
        final int minRenderDistChunks = Math.max(2, vanillaRenderDistChunks - 1);
        final int minDistSq = minRenderDistChunks * minRenderDistChunks;

        final float lodZFar = Math.max(4096.0f, maxLodDistanceChunks * 16.0f * 1.5f);
        final float lodFogEnd = maxLodDistanceChunks * 16.0f;
        final float lodFogStart = lodFogEnd * 0.55f;
        final float sunBrightness = mc.world.getSunBrightness(partialTicks);
        final float celestialAngle = mc.world.getCelestialAngle(partialTicks);
        final net.minecraft.util.math.Vec3d fogColor = mc.world.getFogColor(partialTicks);

        final ClippingHelper clippingHelper = ClippingHelperImpl.getInstance();
        cachedNumFrustumPlanes = 0;
        if (clippingHelper != null && clippingHelper.frustum != null) {
            int farPlaneIdx = 5;
            if (Math.abs(clippingHelper.frustum[4][3]) > Math.abs(clippingHelper.frustum[5][3]) && Math.abs(clippingHelper.frustum[4][3]) > 5.0f) {
                farPlaneIdx = 4;
            }
            for (int i = 0; i < 6 && cachedNumFrustumPlanes < 5; ++i) {
                if (i == farPlaneIdx) continue;
                System.arraycopy(clippingHelper.frustum[i], 0, cachedFrustumPlanes[cachedNumFrustumPlanes], 0, 4);
                cachedNumFrustumPlanes++;
            }
        }
        final boolean optifineShaders = LodShaderBridge.isOptifineShadersActive();

        // 4. Projection Matrix Setup:
        // Always load extendedProjBuffer (zNear = 0.05f, zFar = lodZFar) onto GL_PROJECTION_MATRIX in BOTH Vanilla Mode AND OptiFine Shader Mode!
        originalProjBuffer.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, originalProjBuffer);
        originalProjBuffer.rewind();

        extendedProjBuffer.clear();
        extendedProjBuffer.put(originalProjBuffer);
        extendedProjBuffer.rewind();
        originalProjBuffer.rewind();

        extendedProjBuffer.put(10, -(lodZFar + LOD_Z_NEAR) / (lodZFar - LOD_Z_NEAR));
        extendedProjBuffer.put(14, -(2.0f * lodZFar * LOD_Z_NEAR) / (lodZFar - LOD_Z_NEAR));
        extendedProjBuffer.rewind();

        // Exact analytical inverse of column-major perspective matrix
        float m0  = extendedProjBuffer.get(0);
        float m5  = extendedProjBuffer.get(5);
        float m8  = extendedProjBuffer.get(8);
        float m9  = extendedProjBuffer.get(9);
        float m10 = extendedProjBuffer.get(10);
        float m14 = extendedProjBuffer.get(14);
        for (int i = 0; i < 16; i++) {
            extendedProjInvBuffer.put(i, 0.0f);
        }
        extendedProjInvBuffer.put(0,  1.0f / m0);
        extendedProjInvBuffer.put(5,  1.0f / m5);
        extendedProjInvBuffer.put(11, 1.0f / m14);
        extendedProjInvBuffer.put(12, m8 / m0);
        extendedProjInvBuffer.put(13, m9 / m5);
        extendedProjInvBuffer.put(14, -1.0f);
        extendedProjInvBuffer.put(15, m10 / m14);
        extendedProjInvBuffer.rewind();

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GL11.glLoadMatrix(extendedProjBuffer);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);

        // 5. Gather and Frustum-Cull Visible Region Super-VBOs
        updateWhiteAtlasUv();
        visibleVbos.clear();
        for (LodVboEntry vbo : activeVbos.values()) {
            int minChunkX = vbo.regionX << 2;
            int maxChunkX = minChunkX + 3;
            int minChunkZ = vbo.regionZ << 2;
            int maxChunkZ = minChunkZ + 3;

            int closestX = Math.max(minChunkX, Math.min(playerChunkX, maxChunkX));
            int closestZ = Math.max(minChunkZ, Math.min(playerChunkZ, maxChunkZ));
            int cdx = closestX - playerChunkX;
            int cdz = closestZ - playerChunkZ;
            int closestDistSq = cdx * cdx + cdz * cdz;
            if (closestDistSq > maxDistSq) {
                continue;
            }

            int farthestX = (Math.abs(minChunkX - playerChunkX) > Math.abs(maxChunkX - playerChunkX)) ? minChunkX : maxChunkX;
            int farthestZ = (Math.abs(minChunkZ - playerChunkZ) > Math.abs(maxChunkZ - playerChunkZ)) ? minChunkZ : maxChunkZ;
            int fdx = farthestX - playerChunkX;
            int fdz = farthestZ - playerChunkZ;
            int farthestDistSq = fdx * fdx + fdz * fdz;
            if (farthestDistSq < minDistSq) {
                continue;
            }

            // Infinite Far-Plane Frustum Culling (planes 0..4, ignoring plane 5 vanilla far plane)
            if (!isBoxInInfiniteFrustum(cachedFrustumPlanes, cachedNumFrustumPlanes, vbo.boundingBox, camX, camY, camZ)) {
                continue;
            }

            visibleVbos.add(vbo);
        }

        if (visibleVbos.isEmpty()) {
            lastFrameRenderedCount = 0;
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            return;
        }

        // 6. Configure Isolated OpenGL State
        GlStateManager.pushMatrix();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.disableCull();
        GL11.glDisable(GL11.GL_CULL_FACE);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO
        );

        GlStateManager.doPolygonOffset(2.0f, 8.0f);
        GlStateManager.enablePolygonOffset();

        float oldFogStart = GL11.glGetFloat(GL11.GL_FOG_START);
        float oldFogEnd = GL11.glGetFloat(GL11.GL_FOG_END);
        GL11.glFogf(GL11.GL_FOG_START, lodFogStart);
        GL11.glFogf(GL11.GL_FOG_END, lodFogEnd);
        boolean fogWasEnabled = GL11.glIsEnabled(GL11.GL_FOG);
        if (!optifineShaders) {
            GlStateManager.disableFog();
            GL11.glDisable(GL11.GL_FOG);
        }

        // Bind vanilla block atlas so texture2D(texture, uv) samples white sprite coordinates
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableTexture2D();
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        GL11.glTexCoord2f(whiteU, whiteV);

        // Disable alpha test and blend during Solid Pass for authentic OptiFine Fast Leaves (solid black backing)
        GlStateManager.disableAlpha();
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GlStateManager.disableBlend();
        GL11.glDisable(GL11.GL_BLEND);

        // Enable pointers for 32-byte interleaved vertex format:
        // Position: offset 0 (12 bytes)
        // TexCoord0 (UV): offset 12 (8 bytes) on default unit
        // Color: offset 20 (4 bytes)
        // Lightmap: offset 24 (4 bytes) on lightmap unit
        // Normal: offset 28 (4 bytes)
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);

        // 7. PASS 1: SOLID TERRAIN, CLIFFS, AND CANOPIES
        LodShaderBridge.ShaderPassType passType = LodShaderBridge.ShaderPassType.NONE;
        if (optifineShaders) {
            LodShaderBridge.pushOptifineProgram();
            LodShaderBridge.beginOptifineSolidPass(lodFogEnd, extendedProjBuffer, extendedProjInvBuffer);
        } else {
            passType = LodShaderBridge.beginLodShaderPass(
                    lodFogStart,
                    lodFogEnd,
                    sunBrightness,
                    celestialAngle,
                    fogColor
            );
        }

        // Fix 1: Ensure hardware depth test and depth writing are strictly active for Solid LOD terrain!
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(true);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(true);

        nowMs = System.currentTimeMillis();

        for (LodVboEntry vbo : visibleVbos) {
            if (vbo.solidVertexCount <= 0) continue;

            float animYOffset = 0.0f;
            if (RlLodConfig.enableChunkAnimatorEffect) {
                if (vbo.firstSeenMs == 0L) {
                    vbo.firstSeenMs = nowMs;
                }
                long ageMs = nowMs - vbo.firstSeenMs;
                int minChunkX = vbo.regionX << 2;
                int maxChunkX = minChunkX + 3;
                int minChunkZ = vbo.regionZ << 2;
                int maxChunkZ = minChunkZ + 3;
                boolean outsideBorder = (minChunkX - playerChunkX > (vanillaRenderDistChunks + 1))
                        || (maxChunkX - playerChunkX < -(vanillaRenderDistChunks + 1))
                        || (minChunkZ - playerChunkZ > (vanillaRenderDistChunks + 1))
                        || (maxChunkZ - playerChunkZ < -(vanillaRenderDistChunks + 1));
                if (outsideBorder && ageMs >= 0 && ageMs < 750L) {
                    float t = ageMs / 750.0f;
                    float easeOut = 1.0f - (1.0f - t) * (1.0f - t) * (1.0f - t);
                    animYOffset = -(1.0f - easeOut) * 36.0f;
                }
            }

            GlStateManager.pushMatrix();
            GlStateManager.translate((vbo.regionX << 6) - camX, -camY + animYOffset, (vbo.regionZ << 6) - camZ);
            setupVertexPointers(vbo.vboId);

            int minChunkX = vbo.regionX << 2;
            int maxChunkX = minChunkX + 3;
            int minChunkZ = vbo.regionZ << 2;
            int maxChunkZ = minChunkZ + 3;

            boolean fullyOutside = (minChunkX > playerChunkX + vanillaRenderDistChunks + 1)
                    || (maxChunkX < playerChunkX - (vanillaRenderDistChunks + 1))
                    || (minChunkZ > playerChunkZ + vanillaRenderDistChunks + 1)
                    || (maxChunkZ < playerChunkZ - (vanillaRenderDistChunks + 1));

            if (fullyOutside) {
                GL11.glDrawArrays(GL11.GL_QUADS, 0, vbo.solidVertexCount);
            } else {
                for (int scz = 0; scz < 4; scz++) {
                    for (int scx = 0; scx < 4; scx++) {
                        int subIdx = (scz << 2) | scx;
                        int count = vbo.subChunkSolidCount[subIdx];
                        if (count <= 0) continue;

                        int chunkX = minChunkX + scx;
                        int chunkZ = minChunkZ + scz;
                        if (shouldRenderLodSubChunk(mc.world, chunkX, chunkZ, playerChunkX, playerChunkZ, vanillaRenderDistChunks, nowMs)) {
                            boolean isInsideVanilla = Math.abs(chunkX - playerChunkX) <= vanillaRenderDistChunks && Math.abs(chunkZ - playerChunkZ) <= vanillaRenderDistChunks;
                            if (isInsideVanilla) {
                                GlStateManager.pushMatrix();
                                GlStateManager.translate(0.0f, -0.12f, 0.0f);
                                GlStateManager.enablePolygonOffset();
                                GlStateManager.doPolygonOffset(3.0f, 12.0f);
                                GL11.glDrawArrays(GL11.GL_QUADS, vbo.subChunkSolidFirst[subIdx], count);
                                GlStateManager.disablePolygonOffset();
                                GlStateManager.popMatrix();
                            } else {
                                GL11.glDrawArrays(GL11.GL_QUADS, vbo.subChunkSolidFirst[subIdx], count);
                            }
                        }
                    }
                }
            }

            GlStateManager.popMatrix();
        }

        // 7b. PASS 1b: CUTOUT MESHES (Flora billboards, torches, flowers, tall grass, glass panes)
        GlStateManager.enableAlpha();
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        GlStateManager.disableBlend();
        GL11.glDisable(GL11.GL_BLEND);

        for (LodVboEntry vbo : visibleVbos) {
            if (vbo.cutoutVertexCount <= 0) continue;

            float animYOffset = 0.0f;
            if (RlLodConfig.enableChunkAnimatorEffect) {
                if (vbo.firstSeenMs == 0L) {
                    vbo.firstSeenMs = nowMs;
                }
                long ageMs = nowMs - vbo.firstSeenMs;
                int minChunkX = vbo.regionX << 2;
                int maxChunkX = minChunkX + 3;
                int minChunkZ = vbo.regionZ << 2;
                int maxChunkZ = minChunkZ + 3;
                boolean outsideBorder = (minChunkX - playerChunkX > (vanillaRenderDistChunks + 1))
                        || (maxChunkX - playerChunkX < -(vanillaRenderDistChunks + 1))
                        || (minChunkZ - playerChunkZ > (vanillaRenderDistChunks + 1))
                        || (maxChunkZ - playerChunkZ < -(vanillaRenderDistChunks + 1));
                if (outsideBorder && ageMs >= 0 && ageMs < 750L) {
                    float t = ageMs / 750.0f;
                    float easeOut = 1.0f - (1.0f - t) * (1.0f - t) * (1.0f - t);
                    animYOffset = -(1.0f - easeOut) * 36.0f;
                }
            }

            GlStateManager.pushMatrix();
            GlStateManager.translate((vbo.regionX << 6) - camX, -camY + animYOffset, (vbo.regionZ << 6) - camZ);
            setupVertexPointers(vbo.vboId);

            int minChunkX = vbo.regionX << 2;
            int maxChunkX = minChunkX + 3;
            int minChunkZ = vbo.regionZ << 2;
            int maxChunkZ = minChunkZ + 3;

            boolean fullyOutside = (minChunkX > playerChunkX + vanillaRenderDistChunks + 1)
                    || (maxChunkX < playerChunkX - (vanillaRenderDistChunks + 1))
                    || (minChunkZ > playerChunkZ + vanillaRenderDistChunks + 1)
                    || (maxChunkZ < playerChunkZ - (vanillaRenderDistChunks + 1));

            if (fullyOutside) {
                GL11.glDrawArrays(GL11.GL_QUADS, vbo.solidVertexCount, vbo.cutoutVertexCount);
            } else {
                for (int scz = 0; scz < 4; scz++) {
                    for (int scx = 0; scx < 4; scx++) {
                        int subIdx = (scz << 2) | scx;
                        int count = vbo.subChunkCutoutCount[subIdx];
                        if (count <= 0) continue;

                        int chunkX = minChunkX + scx;
                        int chunkZ = minChunkZ + scz;
                        if (shouldRenderLodSubChunk(mc.world, chunkX, chunkZ, playerChunkX, playerChunkZ, vanillaRenderDistChunks, nowMs)) {
                            boolean isInsideVanilla = Math.abs(chunkX - playerChunkX) <= vanillaRenderDistChunks && Math.abs(chunkZ - playerChunkZ) <= vanillaRenderDistChunks;
                            if (isInsideVanilla) {
                                GlStateManager.pushMatrix();
                                GlStateManager.translate(0.0f, -0.12f, 0.0f);
                                GlStateManager.enablePolygonOffset();
                                GlStateManager.doPolygonOffset(3.0f, 12.0f);
                                GL11.glDrawArrays(GL11.GL_QUADS, vbo.subChunkCutoutFirst[subIdx], count);
                                GlStateManager.disablePolygonOffset();
                                GlStateManager.popMatrix();
                            } else {
                                GL11.glDrawArrays(GL11.GL_QUADS, vbo.subChunkCutoutFirst[subIdx], count);
                            }
                        }
                    }
                }
            }

            GlStateManager.popMatrix();
        }

        // Synchronize updated depth buffer into depthtex1 for OptiFine shaders before water pass
        if (optifineShaders) {
            LodShaderBridge.copyPreWaterDepth();
        }

        // 8. PASS 2: TRANSLUCENT WATER SURFACES (OptiFine ProgramWater with wave normals & reflections)
        if (optifineShaders) {
            LodShaderBridge.beginOptifineWaterPass(lodFogEnd, extendedProjBuffer, extendedProjInvBuffer);
        }

        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(true);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(true);

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO
        );

        for (LodVboEntry vbo : visibleVbos) {
            if (vbo.waterVertexCount <= 0) continue;

            float animYOffset = 0.0f;
            if (RlLodConfig.enableChunkAnimatorEffect) {
                if (vbo.firstSeenMs == 0L) {
                    vbo.firstSeenMs = nowMs;
                }
                long ageMs = nowMs - vbo.firstSeenMs;
                int minChunkX = vbo.regionX << 2;
                int maxChunkX = minChunkX + 3;
                int minChunkZ = vbo.regionZ << 2;
                int maxChunkZ = minChunkZ + 3;
                boolean outsideBorder = (minChunkX - playerChunkX > (vanillaRenderDistChunks + 1))
                        || (maxChunkX - playerChunkX < -(vanillaRenderDistChunks + 1))
                        || (minChunkZ - playerChunkZ > (vanillaRenderDistChunks + 1))
                        || (maxChunkZ - playerChunkZ < -(vanillaRenderDistChunks + 1));
                if (outsideBorder && ageMs >= 0 && ageMs < 750L) {
                    float t = ageMs / 750.0f;
                    float easeOut = 1.0f - (1.0f - t) * (1.0f - t) * (1.0f - t);
                    animYOffset = -(1.0f - easeOut) * 36.0f;
                }
            }

            GlStateManager.pushMatrix();
            GlStateManager.translate((vbo.regionX << 6) - camX, -camY + animYOffset, (vbo.regionZ << 6) - camZ);
            setupVertexPointers(vbo.vboId);

            int minChunkX = vbo.regionX << 2;
            int maxChunkX = minChunkX + 3;
            int minChunkZ = vbo.regionZ << 2;
            int maxChunkZ = minChunkZ + 3;

            boolean fullyOutside = (minChunkX > playerChunkX + vanillaRenderDistChunks + 1)
                    || (maxChunkX < playerChunkX - (vanillaRenderDistChunks + 1))
                    || (minChunkZ > playerChunkZ + vanillaRenderDistChunks + 1)
                    || (maxChunkZ < playerChunkZ - (vanillaRenderDistChunks + 1));

            if (fullyOutside) {
                GL11.glDrawArrays(GL11.GL_QUADS, vbo.solidVertexCount + vbo.cutoutVertexCount, vbo.waterVertexCount);
            } else {
                for (int scz = 0; scz < 4; scz++) {
                    for (int scx = 0; scx < 4; scx++) {
                        int subIdx = (scz << 2) | scx;
                        int count = vbo.subChunkWaterCount[subIdx];
                        if (count <= 0) continue;

                        int chunkX = minChunkX + scx;
                        int chunkZ = minChunkZ + scz;
                        if (shouldRenderLodSubChunk(mc.world, chunkX, chunkZ, playerChunkX, playerChunkZ, vanillaRenderDistChunks, nowMs)) {
                            boolean isInsideVanilla = Math.abs(chunkX - playerChunkX) <= vanillaRenderDistChunks && Math.abs(chunkZ - playerChunkZ) <= vanillaRenderDistChunks;
                            if (isInsideVanilla) {
                                GlStateManager.pushMatrix();
                                GlStateManager.translate(0.0f, -0.12f, 0.0f);
                                GlStateManager.enablePolygonOffset();
                                GlStateManager.doPolygonOffset(3.0f, 12.0f);
                                GL11.glDrawArrays(GL11.GL_QUADS, vbo.subChunkWaterFirst[subIdx], count);
                                GlStateManager.disablePolygonOffset();
                                GlStateManager.popMatrix();
                            } else {
                                GL11.glDrawArrays(GL11.GL_QUADS, vbo.subChunkWaterFirst[subIdx], count);
                            }
                        }
                    }
                }
            }

            GlStateManager.popMatrix();
        }

        if (optifineShaders) {
            LodShaderBridge.popOptifineProgram();
        } else {
            LodShaderBridge.endLodShaderPass(passType);
        }

        lastFrameRenderedCount = visibleVbos.size();

        // 9. Restore OpenGL State
        GlStateManager.enableCull();
        GL11.glEnable(GL11.GL_CULL_FACE);
        GlStateManager.enableAlpha();
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);

        GL11.glFogf(GL11.GL_FOG_START, oldFogStart);
        GL11.glFogf(GL11.GL_FOG_END, oldFogEnd);
        if (fogWasEnabled) {
            GlStateManager.enableFog();
            GL11.glEnable(GL11.GL_FOG);
        }

        GlStateManager.disablePolygonOffset();
        GlStateManager.doPolygonOffset(0.0f, 0.0f);
        GlStateManager.popMatrix();

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
    }

    private void uploadPendingMeshes() {
        if (pendingUploads.isEmpty()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        final int prx = (mc.player != null) ? (((int) Math.floor(mc.player.posX)) >> 6) : 0;
        final int prz = (mc.player != null) ? (((int) Math.floor(mc.player.posZ)) >> 6) : 0;

        List<LodMeshData> readyList = new ArrayList<>(pendingUploads.size() + 4);
        LodMeshData m;
        while ((m = pendingUploads.poll()) != null) {
            readyList.add(m);
        }

        if (readyList.size() > 1) {
            readyList.sort((a, b) -> {
                int distA = Math.max(Math.abs(a.regionX - prx), Math.abs(a.regionZ - prz));
                int distB = Math.max(Math.abs(b.regionX - prx), Math.abs(b.regionZ - prz));
                return Integer.compare(distA, distB);
            });
        }

        int uploaded = 0;
        int count = readyList.size();
        for (int i = 0; i < count; i++) {
            LodMeshData mesh = readyList.get(i);
            if (uploaded < MAX_VBO_UPLOADS_PER_FRAME) {
                int vboId = OpenGlHelper.glGenBuffers();
                if (vboId > 0) {
                    OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, vboId);
                    OpenGlHelper.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, mesh.vertexBuffer, OpenGlHelper.GL_STATIC_DRAW);
                    OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);

                    LodMeshBuilder.freeDirectBuffer(mesh.vertexBuffer);

                    long regionKey = LodMeshData.toRegionKey(mesh.regionX, mesh.regionZ);

                    LodVboEntry newEntry = new LodVboEntry(
                            mesh.regionX,
                            mesh.regionZ,
                            mesh.lodLevel,
                            mesh.minY,
                            mesh.maxY,
                            mesh.solidVertexCount,
                            mesh.waterVertexCount,
                            mesh.subChunkSolidFirst,
                            mesh.subChunkSolidCount,
                            mesh.subChunkWaterFirst,
                            mesh.subChunkWaterCount,
                            mesh.presentChunkMask,
                            vboId
                    );

                    LodVboEntry oldEntry = activeVbos.put(regionKey, newEntry);
                    if (oldEntry != null) {
                        newEntry.firstSeenMs = oldEntry.firstSeenMs;
                        oldEntry.deleteGlResources();
                    }

                    uploaded++;
                    continue;
                }
            }

            pendingUploads.offer(mesh);
        }
    }

    @SubscribeEvent
    public void onRenderDebugText(RenderGameOverlayEvent.Text event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings.showDebugInfo) {
            String shaderMode = LodShaderBridge.isOptifineShadersActive()
                    ? (RlLodConfig.overrideOptifineShaderFarPlane ? "OptiFine (G-Buffer)" : "OptiFine")
                    : (RlLodConfig.enableBuiltInShader ? "Built-In GLSL" : "Vanilla");

            String qualityName = (RlLodConfig.lodDetailQuality == 0) ? "High" : (RlLodConfig.lodDetailQuality == 1 ? "Balanced" : "Fast");

            event.getRight().add("");
            event.getRight().add(String.format(
                    "\u00a7b[RlLOD]\u00a7r Chunks: %d | Region VBOs: %d (Drawn: %d) | Dist: %dch | Quality: %s | Shader: %s",
                    LodCacheManager.INSTANCE.getSnapshotCache().size(),
                    activeVbos.size(),
                    lastFrameRenderedCount,
                    RlLodConfig.lodRenderDistanceChunks,
                    qualityName,
                    shaderMode
            ));
        }
    }
}
