package com.rllod.mod.meshing;

import com.rllod.mod.capture.BlockTextureColorCache;
import com.rllod.mod.capture.BlockTextureColorCache.SpriteUvInfo;
import com.rllod.mod.capture.LodCacheManager;
import com.rllod.mod.capture.LodChunkSnapshot;
import com.rllod.mod.capture.LodColumnPacker;
import com.rllod.mod.config.RlLodConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import sun.nio.ch.DirectBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

public final class LodMeshBuilder {

    private static final float SHADE_TOP = 1.00f;
    private static final float SHADE_NS  = 0.82f;
    private static final float SHADE_EW  = 0.68f;
    private static final float SHADE_BOT = 0.55f;

    private static final ThreadLocal<ByteBuffer> SOLID_SCRATCH = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(4 * 1024 * 1024).order(ByteOrder.nativeOrder())
    );
    private static final ThreadLocal<ByteBuffer> WATER_SCRATCH = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(1024 * 1024).order(ByteOrder.nativeOrder())
    );

    private static final class GridScratch {
        final int[] solidY = new int[4096];
        final int[] waterY = new int[4096];
        final int[] groundRgb = new int[4096];
        final int[] skyLight = new int[4096];
        final int[] blockLight = new int[4096];
        final int[] stateId = new int[4096];
        final boolean[] valid = new boolean[4096];

        final int[] canopyTopY = new int[4096];
        final int[] canopyBotY = new int[4096];
        final int[] canopyRgb = new int[4096];
        final int[] canopyStateId = new int[4096];

        final boolean[] merged = new boolean[4096];
        final boolean[] waterMerged = new boolean[4096];

        void clear(int len) {
            Arrays.fill(solidY, 0, len, 0);
            Arrays.fill(waterY, 0, len, 0);
            Arrays.fill(groundRgb, 0, len, 0);
            Arrays.fill(skyLight, 0, len, 15);
            Arrays.fill(blockLight, 0, len, 0);
            Arrays.fill(stateId, 0, len, 0);
            Arrays.fill(valid, 0, len, false);
            Arrays.fill(canopyTopY, 0, len, 0);
            Arrays.fill(canopyBotY, 0, len, 0);
            Arrays.fill(canopyRgb, 0, len, 0);
            Arrays.fill(canopyStateId, 0, len, 0);
            Arrays.fill(merged, 0, len, false);
            Arrays.fill(waterMerged, 0, len, false);
        }
    }

    private static final ThreadLocal<GridScratch> TL_GRID = ThreadLocal.withInitial(GridScratch::new);

    private LodMeshBuilder() {}

    public static void freeDirectBuffer(ByteBuffer buf) {
        if (buf == null || !buf.isDirect()) return;
        try {
            if (buf instanceof DirectBuffer) {
                sun.misc.Cleaner cleaner = ((DirectBuffer) buf).cleaner();
                if (cleaner != null) cleaner.clean();
            }
        } catch (Throwable ignored) {}
    }

    public static LodMeshData buildRegionMesh(
            int regionX,
            int regionZ,
            int lodLevel,
            LodChunkSnapshot[] chunks4x4
    ) {
        int presentMask = 0;
        LodChunkSnapshot firstValid = null;
        for (int i = 0; i < 16; i++) {
            if (chunks4x4[i] != null) {
                presentMask |= (1 << i);
                if (firstValid == null) firstValid = chunks4x4[i];
            }
        }
        if (presentMask == 0 || firstValid == null) {
            return new LodMeshData(
                    regionX, regionZ, lodLevel, 0, 64, 0, 0,
                    new int[16], new int[16], new int[16], new int[16], 0,
                    ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
            );
        }

        // Fill any missing sub-chunk slot from the nearest available sub-chunk in this 4x4 region so there are ZERO holes
        LodChunkSnapshot[] workingChunks = new LodChunkSnapshot[16];
        for (int i = 0; i < 16; i++) {
            if (chunks4x4[i] != null) {
                workingChunks[i] = chunks4x4[i];
            } else {
                int ix = i & 3, iz = i >> 2;
                LodChunkSnapshot best = firstValid;
                int bestDist = 999;
                for (int j = 0; j < 16; j++) {
                    if (chunks4x4[j] != null) {
                        int dx = (j & 3) - ix, dz = (j >> 2) - iz;
                        int d = dx * dx + dz * dz;
                        if (d < bestDist) {
                            bestDist = d;
                            best = chunks4x4[j];
                        }
                    }
                }
                workingChunks[i] = synthesizeFallbackSnapshot((regionX << 2) + ix, (regionZ << 2) + iz, best);
            }
        }

        final int effectiveLod = Math.max(0, Math.min(3, lodLevel));
        final int step = 1 << effectiveLod; // 1, 2, 4, or 8
        final int gridDim = 64 / step;      // 64, 32, 16, or 8
        final int cellsPerChunk = 16 / step;
        final int totalCells = gridDim * gridDim;

        GridScratch g = TL_GRID.get();
        g.clear(totalCells);

        float whiteU = 0.5f, whiteV = 0.5f;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.getTextureMapBlocks() != null) {
                TextureAtlasSprite snow = mc.getTextureMapBlocks().getAtlasSprite("minecraft:blocks/snow");
                if (snow != null) {
                    whiteU = snow.getInterpolatedU(8.0);
                    whiteV = snow.getInterpolatedV(8.0);
                }
            }
        } catch (Throwable ignored) {}

        int minY = 255;
        int maxY = 0;
        final boolean preserveLandmarks = RlLodConfig.preserveRlcraftLandmarks;

        // =====================================================
        // STEP 1: POPULATE REGION GRID (GROUND, WATER, CANOPY)
        // =====================================================
        for (int gz = 0; gz < gridDim; gz++) {
            final int regionBlockZ = gz * step;
            final int scz = regionBlockZ >> 4;
            final int localZ = regionBlockZ & 15;
            for (int gx = 0; gx < gridDim; gx++) {
                final int regionBlockX = gx * step;
                final int scx = regionBlockX >> 4;
                final int localX = regionBlockX & 15;

                LodChunkSnapshot chunk = workingChunks[(scz << 2) | scx];
                int idx = gz * gridDim + gx;

                long sampledGround = downsampleGroundCell(chunk, localX, localZ, step, preserveLandmarks);
                int sY = LodColumnPacker.unpackSolidY(sampledGround);
                int wY = LodColumnPacker.unpackWaterY(sampledGround);
                if (sY <= 0) sY = 62;

                g.valid[idx] = true;
                g.solidY[idx] = sY;
                g.waterY[idx] = (wY > sY) ? wY : 0;
                g.groundRgb[idx] = LodColumnPacker.unpackRgb(sampledGround);
                g.skyLight[idx] = Math.max(13, LodColumnPacker.unpackSkyLight(sampledGround));
                g.blockLight[idx] = LodColumnPacker.unpackBlockLight(sampledGround);

                if (step == 1) {
                    int colIdx = (localZ << 4) | localX;
                    g.stateId[idx] = (chunk.groundStateIds != null) ? (chunk.groundStateIds[colIdx] & 0xFFFF) : 0;
                    g.canopyStateId[idx] = (chunk.canopyStateIds != null) ? (chunk.canopyStateIds[colIdx] & 0xFFFF) : 0;
                }

                // Unpack Canopy / Floating Structure Layer (packed at 256..511: solidY slot = canopyTopY, waterY slot = canopyBottomY)
                long sampledCanopy = downsampleCanopyCell(chunk, localX, localZ, step);
                if (sampledCanopy != 0L) {
                    int cTop = LodColumnPacker.unpackSolidY(sampledCanopy);
                    int cBot = LodColumnPacker.unpackWaterY(sampledCanopy);
                    if (cTop > sY && cTop >= cBot && cBot > 0) {
                        g.canopyTopY[idx] = cTop;
                        g.canopyBotY[idx] = Math.max(sY + 1, cBot);
                        g.canopyRgb[idx] = LodColumnPacker.unpackRgb(sampledCanopy);
                        if (cTop + 1 > maxY) maxY = cTop + 1;
                    }
                }

                if (sY < minY) minY = sY;
                int topEff = Math.max(sY, g.waterY[idx]);
                if (topEff + 1 > maxY) maxY = topEff + 1;
            }
        }

        ByteBuffer solidBuf = SOLID_SCRATCH.get();
        ByteBuffer waterBuf = WATER_SCRATCH.get();
        solidBuf.clear();
        waterBuf.clear();

        int solidVerts = 0;
        int waterVerts = 0;

        int[] subChunkSolidFirst = new int[16];
        int[] subChunkSolidCount = new int[16];
        int[] subChunkWaterFirst = new int[16];
        int[] subChunkWaterCount = new int[16];

        ConcurrentHashMap<Long, LodChunkSnapshot> globalCache = LodCacheManager.INSTANCE.getSnapshotCache();

        // =====================================================
        // STEP 2: BUILD SOLID TERRAIN, SKIRTS & CANOPIES PER SUB-CHUNK
        // =====================================================
        for (int scz = 0; scz < 4; scz++) {
            final int minGz = scz * cellsPerChunk;
            final int maxGz = minGz + cellsPerChunk;
            for (int scx = 0; scx < 4; scx++) {
                final int minGx = scx * cellsPerChunk;
                final int maxGx = minGx + cellsPerChunk;
                final int subIdx = (scz << 2) | scx;

                subChunkSolidFirst[subIdx] = solidVerts;

                // 2A. Ground Top Quads + Vertical Skirts
                for (int gz = minGz; gz < maxGz; gz++) {
                    for (int gx = minGx; gx < maxGx; gx++) {
                        int idx = gz * gridDim + gx;
                        if (!g.valid[idx]) continue;

                        int baseY = g.solidY[idx];
                        int baseRgb = g.groundRgb[idx];
                        short lu = (short) (Math.min(15, g.blockLight[idx]) << 4);
                        short lv = (short) (Math.min(15, g.skyLight[idx]) << 4);

                        float grain = computeGrain(gx * step, baseY, gz * step);

                        // Top Face (greedy merged on LOD >= 1, individual 1x1 on LOD 0)
                        if (!g.merged[idx]) {
                            int width = 1;
                            int depth = 1;
                            if (effectiveLod >= 1) {
                                while (gx + width < maxGx) {
                                    int nIdx = gz * gridDim + (gx + width);
                                    if (g.merged[nIdx] || g.solidY[nIdx] != baseY || !colorsClose(baseRgb, g.groundRgb[nIdx])) {
                                        break;
                                    }
                                    width++;
                                }
                                outerZ:
                                while (gz + depth < maxGz) {
                                    for (int dx = 0; dx < width; dx++) {
                                        int nIdx = (gz + depth) * gridDim + (gx + dx);
                                        if (g.merged[nIdx] || g.solidY[nIdx] != baseY || !colorsClose(baseRgb, g.groundRgb[nIdx])) {
                                            break outerZ;
                                        }
                                    }
                                    depth++;
                                }
                            }
                            for (int dz = 0; dz < depth; dz++) {
                                for (int dx = 0; dx < width; dx++) {
                                    g.merged[(gz + dz) * gridDim + (gx + dx)] = true;
                                }
                            }

                            float x0 = gx * step;
                            float x1 = (gx + width) * step;
                            float z0 = gz * step;
                            float z1 = (gz + depth) * step;
                            float yTop = baseY + 1.0f;

                            float u0 = whiteU, v0 = whiteV, u1 = whiteU, v1 = whiteV;
                            int topRenderRgb = baseRgb;
                            if (effectiveLod == 0 && width == 1 && depth == 1 && g.stateId[idx] > 0) {
                                SpriteUvInfo uv = BlockTextureColorCache.getSpriteUvInfo(g.stateId[idx]);
                                if (uv != null) {
                                    u0 = uv.minU; v0 = uv.minV; u1 = uv.maxU; v1 = uv.maxV;
                                    topRenderRgb = uv.upHasTint ? boostRgb(baseRgb, 1.42f) : 0xFFFFFF;
                                }
                            }

                            int c00 = packRgba(topRenderRgb, SHADE_TOP * grain * cornerAo(g, gridDim, gx, gz, -1, -1, baseY), 255);
                            int c01 = packRgba(topRenderRgb, SHADE_TOP * grain * cornerAo(g, gridDim, gx, gz, -1,  1, baseY), 255);
                            int c11 = packRgba(topRenderRgb, SHADE_TOP * grain * cornerAo(g, gridDim, gx, gz,  1,  1, baseY), 255);
                            int c10 = packRgba(topRenderRgb, SHADE_TOP * grain * cornerAo(g, gridDim, gx, gz,  1, -1, baseY), 255);

                            if (solidBuf.remaining() >= 128) {
                                putVertex(solidBuf, x0, yTop, z0, u0, v0, c00, lu, lv, (byte) 0, (byte) 127, (byte) 0);
                                putVertex(solidBuf, x0, yTop, z1, u0, v1, c01, lu, lv, (byte) 0, (byte) 127, (byte) 0);
                                putVertex(solidBuf, x1, yTop, z1, u1, v1, c11, lu, lv, (byte) 0, (byte) 127, (byte) 0);
                                putVertex(solidBuf, x1, yTop, z0, u1, v0, c10, lu, lv, (byte) 0, (byte) 127, (byte) 0);
                                solidVerts += 4;
                            }
                        }

                        // Vertical Skirts (NORTH, SOUTH, WEST, EAST) - Guaranteed Counter-Clockwise (CCW) Winding!
                        int northY = getNeighborSolidY(g, gridDim, step, regionX, regionZ, gx, gz - 1, baseY, globalCache);
                        int southY = getNeighborSolidY(g, gridDim, step, regionX, regionZ, gx, gz + 1, baseY, globalCache);
                        int westY  = getNeighborSolidY(g, gridDim, step, regionX, regionZ, gx - 1, gz, baseY, globalCache);
                        int eastY  = getNeighborSolidY(g, gridDim, step, regionX, regionZ, gx + 1, gz, baseY, globalCache);

                        float x0 = gx * step;
                        float x1 = (gx + 1) * step;
                        float z0 = gz * step;
                        float z1 = (gz + 1) * step;

                        SpriteUvInfo skirtUv = (effectiveLod == 0 && g.stateId[idx] > 0)
                                ? BlockTextureColorCache.getSpriteUvInfo(g.stateId[idx]) : null;

                        solidVerts += emitVerticalSkirt(solidBuf, effectiveLod, skirtUv, baseY, northY, x0, x1, z0, z1, baseRgb, lu, lv, 0, whiteU, whiteV);
                        solidVerts += emitVerticalSkirt(solidBuf, effectiveLod, skirtUv, baseY, southY, x0, x1, z0, z1, baseRgb, lu, lv, 1, whiteU, whiteV);
                        solidVerts += emitVerticalSkirt(solidBuf, effectiveLod, skirtUv, baseY, westY,  x0, x1, z0, z1, baseRgb, lu, lv, 2, whiteU, whiteV);
                        solidVerts += emitVerticalSkirt(solidBuf, effectiveLod, skirtUv, baseY, eastY,  x0, x1, z0, z1, baseRgb, lu, lv, 3, whiteU, whiteV);

                        // 2B. Floating Canopy / Structure Box ([canopyBotY .. canopyTopY + 1])
                        int cTop = g.canopyTopY[idx];
                        int cBot = g.canopyBotY[idx];
                        if (cTop > baseY && cTop >= cBot && cBot > 0 && solidBuf.remaining() >= 384) {
                            float cyTop = cTop + 1.0f;
                            float cyBot = cBot;
                            int cRgb = g.canopyRgb[idx];

                            float cu0 = whiteU, cv0 = whiteV, cu1 = whiteU, cv1 = whiteV;
                            int cRenderRgb = cRgb;
                            if (effectiveLod == 0 && g.canopyStateId[idx] > 0) {
                                SpriteUvInfo cUv = BlockTextureColorCache.getSpriteUvInfo(g.canopyStateId[idx]);
                                if (cUv != null) {
                                    cu0 = cUv.minU; cv0 = cUv.minV; cu1 = cUv.maxU; cv1 = cUv.maxV;
                                    cRenderRgb = cUv.upHasTint ? boostRgb(cRgb, 1.48f) : 0xFFFFFF;
                                }
                            }

                            int cTopCol = packRgba(cRenderRgb, SHADE_TOP * grain, 255);
                            int cBotCol = packRgba(cRenderRgb, SHADE_BOT * grain, 255);
                            int cNsCol  = packRgba(cRenderRgb, SHADE_NS * grain, 255);
                            int cEwCol  = packRgba(cRenderRgb, SHADE_EW * grain, 255);
                            short clv = (short) 240;

                            // Canopy Top (+Y)
                            putVertex(solidBuf, x0, cyTop, z0, cu0, cv0, cTopCol, lu, clv, (byte) 0, (byte) 127, (byte) 0);
                            putVertex(solidBuf, x0, cyTop, z1, cu0, cv1, cTopCol, lu, clv, (byte) 0, (byte) 127, (byte) 0);
                            putVertex(solidBuf, x1, cyTop, z1, cu1, cv1, cTopCol, lu, clv, (byte) 0, (byte) 127, (byte) 0);
                            putVertex(solidBuf, x1, cyTop, z0, cu1, cv0, cTopCol, lu, clv, (byte) 0, (byte) 127, (byte) 0);
                            solidVerts += 4;

                            // Canopy Bottom (-Y, if floating above ground)
                            if (cBot > baseY + 1) {
                                putVertex(solidBuf, x0, cyBot, z1, cu0, cv1, cBotCol, lu, clv, (byte) 0, (byte) -127, (byte) 0);
                                putVertex(solidBuf, x0, cyBot, z0, cu0, cv0, cBotCol, lu, clv, (byte) 0, (byte) -127, (byte) 0);
                                putVertex(solidBuf, x1, cyBot, z0, cu1, cv0, cBotCol, lu, clv, (byte) 0, (byte) -127, (byte) 0);
                                putVertex(solidBuf, x1, cyBot, z1, cu1, cv1, cBotCol, lu, clv, (byte) 0, (byte) -127, (byte) 0);
                                solidVerts += 4;
                            }

                            // Canopy 4 Sides (strictly between cyBot and cyTop — NEVER extending down to baseY!)
                            int nCTop = (gz > 0) ? g.canopyTopY[(gz - 1) * gridDim + gx] : 0;
                            int sCTop = (gz + 1 < gridDim) ? g.canopyTopY[(gz + 1) * gridDim + gx] : 0;
                            int wCTop = (gx > 0) ? g.canopyTopY[gz * gridDim + (gx - 1)] : 0;
                            int eCTop = (gx + 1 < gridDim) ? g.canopyTopY[gz * gridDim + (gx + 1)] : 0;

                            if (nCTop < cTop) {
                                float sb = Math.max(cyBot, (nCTop > 0) ? (nCTop + 1.0f) : cyBot);
                                putVertex(solidBuf, x1, cyTop, z0, cu1, cv0, cNsCol, lu, clv, (byte) 0, (byte) 0, (byte) -127);
                                putVertex(solidBuf, x1, sb,    z0, cu1, cv1, cNsCol, lu, clv, (byte) 0, (byte) 0, (byte) -127);
                                putVertex(solidBuf, x0, sb,    z0, cu0, cv1, cNsCol, lu, clv, (byte) 0, (byte) 0, (byte) -127);
                                putVertex(solidBuf, x0, cyTop, z0, cu0, cv0, cNsCol, lu, clv, (byte) 0, (byte) 0, (byte) -127);
                                solidVerts += 4;
                            }
                            if (sCTop < cTop) {
                                float sb = Math.max(cyBot, (sCTop > 0) ? (sCTop + 1.0f) : cyBot);
                                putVertex(solidBuf, x0, cyTop, z1, cu0, cv0, cNsCol, lu, clv, (byte) 0, (byte) 0, (byte) 127);
                                putVertex(solidBuf, x0, sb,    z1, cu0, cv1, cNsCol, lu, clv, (byte) 0, (byte) 0, (byte) 127);
                                putVertex(solidBuf, x1, sb,    z1, cu1, cv1, cNsCol, lu, clv, (byte) 0, (byte) 0, (byte) 127);
                                putVertex(solidBuf, x1, cyTop, z1, cu1, cv0, cNsCol, lu, clv, (byte) 0, (byte) 0, (byte) 127);
                                solidVerts += 4;
                            }
                            if (wCTop < cTop) {
                                float sb = Math.max(cyBot, (wCTop > 0) ? (wCTop + 1.0f) : cyBot);
                                putVertex(solidBuf, x0, cyTop, z0, cu0, cv0, cEwCol, lu, clv, (byte) -127, (byte) 0, (byte) 0);
                                putVertex(solidBuf, x0, sb,    z0, cu0, cv1, cEwCol, lu, clv, (byte) -127, (byte) 0, (byte) 0);
                                putVertex(solidBuf, x0, sb,    z1, cu1, cv1, cEwCol, lu, clv, (byte) -127, (byte) 0, (byte) 0);
                                putVertex(solidBuf, x0, cyTop, z1, cu1, cv0, cEwCol, lu, clv, (byte) -127, (byte) 0, (byte) 0);
                                solidVerts += 4;
                            }
                            if (eCTop < cTop) {
                                float sb = Math.max(cyBot, (eCTop > 0) ? (eCTop + 1.0f) : cyBot);
                                putVertex(solidBuf, x1, cyTop, z1, cu1, cv0, cEwCol, lu, clv, (byte) 127, (byte) 0, (byte) 0);
                                putVertex(solidBuf, x1, sb,    z1, cu1, cv1, cEwCol, lu, clv, (byte) 127, (byte) 0, (byte) 0);
                                putVertex(solidBuf, x1, sb,    z0, cu0, cv1, cEwCol, lu, clv, (byte) 127, (byte) 0, (byte) 0);
                                putVertex(solidBuf, x1, cyTop, z0, cu0, cv0, cEwCol, lu, clv, (byte) 127, (byte) 0, (byte) 0);
                                solidVerts += 4;
                            }
                        }
                    }
                }

                subChunkSolidCount[subIdx] = solidVerts - subChunkSolidFirst[subIdx];

                // 2C. Translucent Water Pass for this sub-chunk
                subChunkWaterFirst[subIdx] = waterVerts;
                for (int gz = minGz; gz < maxGz; gz++) {
                    for (int gx = minGx; gx < maxGx; gx++) {
                        int idx = gz * gridDim + gx;
                        int wY = g.waterY[idx];
                        if (wY <= 0 || g.waterMerged[idx]) continue;

                        int width = 1;
                        while (gx + width < maxGx) {
                            int nIdx = gz * gridDim + (gx + width);
                            if (g.waterMerged[nIdx] || g.waterY[nIdx] != wY) break;
                            width++;
                        }
                        int depth = 1;
                        outerWaterZ:
                        while (gz + depth < maxGz) {
                            for (int dx = 0; dx < width; dx++) {
                                int nIdx = (gz + depth) * gridDim + (gx + dx);
                                if (g.waterMerged[nIdx] || g.waterY[nIdx] != wY) break outerWaterZ;
                            }
                            depth++;
                        }
                        for (int dz = 0; dz < depth; dz++) {
                            for (int dx = 0; dx < width; dx++) {
                                g.waterMerged[(gz + dz) * gridDim + (gx + dx)] = true;
                            }
                        }

                        float x0 = gx * step;
                        float x1 = (gx + width) * step;
                        float z0 = gz * step;
                        float z1 = (gz + depth) * step;
                        float yWater = wY + 0.875f;

                        int wCol = packRgba(0x2F5FDE, 1.0f, 210);
                        short lu = (short) (Math.min(15, g.blockLight[idx]) << 4);
                        short lv = (short) 240;

                        if (waterBuf.remaining() >= 128) {
                            putVertex(waterBuf, x0, yWater, z0, whiteU, whiteV, wCol, lu, lv, (byte) 0, (byte) 127, (byte) 0);
                            putVertex(waterBuf, x0, yWater, z1, whiteU, whiteV, wCol, lu, lv, (byte) 0, (byte) 127, (byte) 0);
                            putVertex(waterBuf, x1, yWater, z1, whiteU, whiteV, wCol, lu, lv, (byte) 0, (byte) 127, (byte) 0);
                            putVertex(waterBuf, x1, yWater, z0, whiteU, whiteV, wCol, lu, lv, (byte) 0, (byte) 127, (byte) 0);
                            waterVerts += 4;
                        }
                    }
                }
                subChunkWaterCount[subIdx] = waterVerts - subChunkWaterFirst[subIdx];
            }
        }

        // Offset water indices by solidVerts so both live in a single combined VBO
        for (int i = 0; i < 16; i++) {
            subChunkWaterFirst[i] += solidVerts;
        }

        solidBuf.flip();
        waterBuf.flip();
        ByteBuffer combined = ByteBuffer.allocateDirect(solidBuf.remaining() + waterBuf.remaining())
                .order(ByteOrder.nativeOrder());
        combined.put(solidBuf);
        combined.put(waterBuf);
        combined.flip();

        if (minY > maxY) {
            minY = 0;
            maxY = 64;
        }

        return new LodMeshData(
                regionX, regionZ, lodLevel, minY, maxY,
                solidVerts, waterVerts,
                subChunkSolidFirst, subChunkSolidCount,
                subChunkWaterFirst, subChunkWaterCount,
                presentMask, combined
        );
    }

    private static int emitVerticalSkirt(
            ByteBuffer solidBuf,
            int effectiveLod,
            SpriteUvInfo uv,
            int baseY,
            int neighborY,
            float x0, float x1, float z0, float z1,
            int baseRgb,
            short lu, short lv,
            int facing, // 0 = North, 1 = South, 2 = West, 3 = East
            float whiteU, float whiteV
    ) {
        if (neighborY >= baseY) {
            return 0;
        }

        boolean isNS = (facing == 0 || facing == 1);
        int vertsAdded = 0;

        if (effectiveLod == 0 && uv != null && (baseY - neighborY) <= 32) {
            float su0 = uv.sideMinU;
            float su1 = uv.sideMaxU;
            float sv0 = uv.sideMinV;
            float sv1 = uv.sideMaxV;

            for (int y = neighborY + 1; y <= baseY; y++) {
                if (solidBuf.remaining() < 128) {
                    break;
                }
                float yb = (float) y;
                float yt = (float) (y + 1);

                float uA, uB, vA, vB;
                int quadBaseRgb;
                if (uv.isGrass && y < baseY) {
                    uA = whiteU;
                    uB = whiteU;
                    vA = whiteV;
                    vB = whiteV;
                    quadBaseRgb = 0x866043;
                } else {
                    uA = su0;
                    uB = su1;
                    vA = sv0;
                    vB = sv1;
                    quadBaseRgb = uv.sideHasTint ? boostRgb(baseRgb, 1.42f) : 0xFFFFFF;
                }

                float shade = (isNS ? SHADE_NS : SHADE_EW) * computeGrain((int) x0, y, (int) z0);
                int quadColor = packRgba(quadBaseRgb, shade, 255);

                switch (facing) {
                    case 0: // North (-Z)
                        putVertex(solidBuf, x1, yt, z0, uB, vA, quadColor, lu, lv, (byte) 0, (byte) 0, (byte) -127);
                        putVertex(solidBuf, x1, yb, z0, uB, vB, quadColor, lu, lv, (byte) 0, (byte) 0, (byte) -127);
                        putVertex(solidBuf, x0, yb, z0, uA, vB, quadColor, lu, lv, (byte) 0, (byte) 0, (byte) -127);
                        putVertex(solidBuf, x0, yt, z0, uA, vA, quadColor, lu, lv, (byte) 0, (byte) 0, (byte) -127);
                        break;
                    case 1: // South (+Z)
                        putVertex(solidBuf, x0, yt, z1, uA, vA, quadColor, lu, lv, (byte) 0, (byte) 0, (byte) 127);
                        putVertex(solidBuf, x0, yb, z1, uA, vB, quadColor, lu, lv, (byte) 0, (byte) 0, (byte) 127);
                        putVertex(solidBuf, x1, yb, z1, uB, vB, quadColor, lu, lv, (byte) 0, (byte) 0, (byte) 127);
                        putVertex(solidBuf, x1, yt, z1, uB, vA, quadColor, lu, lv, (byte) 0, (byte) 0, (byte) 127);
                        break;
                    case 2: // West (-X)
                        putVertex(solidBuf, x0, yt, z0, uA, vA, quadColor, lu, lv, (byte) -127, (byte) 0, (byte) 0);
                        putVertex(solidBuf, x0, yb, z0, uA, vB, quadColor, lu, lv, (byte) -127, (byte) 0, (byte) 0);
                        putVertex(solidBuf, x0, yb, z1, uB, vB, quadColor, lu, lv, (byte) -127, (byte) 0, (byte) 0);
                        putVertex(solidBuf, x0, yt, z1, uB, vA, quadColor, lu, lv, (byte) -127, (byte) 0, (byte) 0);
                        break;
                    case 3: // East (+X)
                        putVertex(solidBuf, x1, yt, z1, uB, vA, quadColor, lu, lv, (byte) 127, (byte) 0, (byte) 0);
                        putVertex(solidBuf, x1, yb, z1, uB, vB, quadColor, lu, lv, (byte) 127, (byte) 0, (byte) 0);
                        putVertex(solidBuf, x1, yb, z0, uA, vB, quadColor, lu, lv, (byte) 127, (byte) 0, (byte) 0);
                        putVertex(solidBuf, x1, yt, z0, uA, vA, quadColor, lu, lv, (byte) 127, (byte) 0, (byte) 0);
                        break;
                }
                vertsAdded += 4;
            }
        } else {
            if (solidBuf.remaining() >= 128) {
                float yTop = baseY + 1.0f;
                float yBot = neighborY + 1.0f;
                float grain = computeGrain((int) x0, baseY, (int) z0);
                int fastColor = packRgba(baseRgb, (isNS ? SHADE_NS : SHADE_EW) * grain, 255);

                switch (facing) {
                    case 0: // North (-Z)
                        putVertex(solidBuf, x1, yTop, z0, whiteU, whiteV, fastColor, lu, lv, (byte) 0, (byte) 0, (byte) -127);
                        putVertex(solidBuf, x1, yBot, z0, whiteU, whiteV, fastColor, lu, lv, (byte) 0, (byte) 0, (byte) -127);
                        putVertex(solidBuf, x0, yBot, z0, whiteU, whiteV, fastColor, lu, lv, (byte) 0, (byte) 0, (byte) -127);
                        putVertex(solidBuf, x0, yTop, z0, whiteU, whiteV, fastColor, lu, lv, (byte) 0, (byte) 0, (byte) -127);
                        break;
                    case 1: // South (+Z)
                        putVertex(solidBuf, x0, yTop, z1, whiteU, whiteV, fastColor, lu, lv, (byte) 0, (byte) 0, (byte) 127);
                        putVertex(solidBuf, x0, yBot, z1, whiteU, whiteV, fastColor, lu, lv, (byte) 0, (byte) 0, (byte) 127);
                        putVertex(solidBuf, x1, yBot, z1, whiteU, whiteV, fastColor, lu, lv, (byte) 0, (byte) 0, (byte) 127);
                        putVertex(solidBuf, x1, yTop, z1, whiteU, whiteV, fastColor, lu, lv, (byte) 0, (byte) 0, (byte) 127);
                        break;
                    case 2: // West (-X)
                        putVertex(solidBuf, x0, yTop, z0, whiteU, whiteV, fastColor, lu, lv, (byte) -127, (byte) 0, (byte) 0);
                        putVertex(solidBuf, x0, yBot, z0, whiteU, whiteV, fastColor, lu, lv, (byte) -127, (byte) 0, (byte) 0);
                        putVertex(solidBuf, x0, yBot, z1, whiteU, whiteV, fastColor, lu, lv, (byte) -127, (byte) 0, (byte) 0);
                        putVertex(solidBuf, x0, yTop, z1, whiteU, whiteV, fastColor, lu, lv, (byte) -127, (byte) 0, (byte) 0);
                        break;
                    case 3: // East (+X)
                        putVertex(solidBuf, x1, yTop, z1, whiteU, whiteV, fastColor, lu, lv, (byte) 127, (byte) 0, (byte) 0);
                        putVertex(solidBuf, x1, yBot, z1, whiteU, whiteV, fastColor, lu, lv, (byte) 127, (byte) 0, (byte) 0);
                        putVertex(solidBuf, x1, yBot, z0, whiteU, whiteV, fastColor, lu, lv, (byte) 127, (byte) 0, (byte) 0);
                        putVertex(solidBuf, x1, yTop, z0, whiteU, whiteV, fastColor, lu, lv, (byte) 127, (byte) 0, (byte) 0);
                        break;
                }
                vertsAdded += 4;
            }
        }

        return vertsAdded;
    }

    private static int getNeighborSolidY(
            GridScratch g,
            int gridDim,
            int step,
            int regionX,
            int regionZ,
            int nx,
            int nz,
            int selfY,
            ConcurrentHashMap<Long, LodChunkSnapshot> globalCache
    ) {
        if (nx >= 0 && nx < gridDim && nz >= 0 && nz < gridDim) {
            int nIdx = nz * gridDim + nx;
            return g.valid[nIdx] ? g.solidY[nIdx] : selfY;
        }
        // Cross-region neighbor lookup in globalCache so 64x64 region borders have no gaps!
        int worldBlockX = (regionX << 6) + nx * step;
        int worldBlockZ = (regionZ << 6) + nz * step;
        int adjChunkX = worldBlockX >> 4;
        int adjChunkZ = worldBlockZ >> 4;
        LodChunkSnapshot adj = globalCache.get(LodChunkSnapshot.toChunkKey(adjChunkX, adjChunkZ));
        if (adj != null) {
            long col = adj.getColumn(worldBlockX & 15, worldBlockZ & 15);
            int adjY = LodColumnPacker.unpackSolidY(col);
            if (adjY > 0) return adjY;
        }
        return selfY;
    }

    private static float cornerAo(GridScratch g, int gridDim, int gx, int gz, int dx, int dz, int baseY) {
        int occ = 0;
        if (gx + dx >= 0 && gx + dx < gridDim && g.solidY[gz * gridDim + (gx + dx)] > baseY) occ++;
        if (gz + dz >= 0 && gz + dz < gridDim && g.solidY[(gz + dz) * gridDim + gx] > baseY) occ++;
        if (gx + dx >= 0 && gx + dx < gridDim && gz + dz >= 0 && gz + dz < gridDim
                && g.solidY[(gz + dz) * gridDim + (gx + dx)] > baseY) occ++;
        return 1.0f - occ * 0.11f;
    }

    private static LodChunkSnapshot synthesizeFallbackSnapshot(int chunkX, int chunkZ, LodChunkSnapshot source) {
        long[] cols = new long[512];
        System.arraycopy(source.columns, 0, cols, 0, 256);
        return new LodChunkSnapshot(chunkX, chunkZ, cols, new short[256], new short[256], new byte[256]);
    }

    private static long downsampleGroundCell(
            LodChunkSnapshot chunk,
            int startX,
            int startZ,
            int step,
            boolean preserveLandmarks
    ) {
        if (step <= 1) {
            return chunk.getColumn(startX, startZ);
        }
        int sumSolidY = 0, sumWaterY = 0;
        int sumR = 0, sumG = 0, sumB = 0;
        int maxSky = 0, maxBlock = 0;
        int minY = 255, maxY = 0;
        long heroCol = 0L;
        int heroScore = -1;
        int count = 0, waterCount = 0;

        for (int dz = 0; dz < step && (startZ + dz) < 16; dz++) {
            for (int dx = 0; dx < step && (startX + dx) < 16; dx++) {
                long col = chunk.getColumn(startX + dx, startZ + dz);
                int sY = LodColumnPacker.unpackSolidY(col);
                int wY = LodColumnPacker.unpackWaterY(col);
                if (sY <= 0) continue;

                count++;
                sumSolidY += sY;
                if (wY > sY) {
                    waterCount++;
                    sumWaterY += wY;
                }
                if (sY < minY) minY = sY;
                if (sY > maxY) maxY = sY;

                int rgb = LodColumnPacker.unpackRgb(col);
                sumR += (rgb >> 16) & 0xFF;
                sumG += (rgb >> 8) & 0xFF;
                sumB += rgb & 0xFF;

                int sky = LodColumnPacker.unpackSkyLight(col);
                int block = LodColumnPacker.unpackBlockLight(col);
                if (sky > maxSky) maxSky = sky;
                if (block > maxBlock) maxBlock = block;

                int score = sY + block * 3;
                if (score > heroScore) {
                    heroScore = score;
                    heroCol = col;
                }
            }
        }
        if (count == 0) return 0L;
        if (preserveLandmarks && ((maxY - minY) >= 6 || maxBlock >= 8)) {
            return heroCol;
        }
        int avgSolidY = sumSolidY / count;
        int avgWaterY = (waterCount * 2 >= count) ? (sumWaterY / waterCount) : 0;
        int avgRgb = (((sumR / count) & 0xFF) << 16) | (((sumG / count) & 0xFF) << 8) | ((sumB / count) & 0xFF);
        int flags = (avgWaterY > avgSolidY) ? LodColumnPacker.FLAG_HAS_WATER : 0;
        int biomeId = LodColumnPacker.unpackBiomeId(heroCol);
        return LodColumnPacker.pack(avgSolidY, avgWaterY, avgRgb, maxSky, maxBlock, flags, biomeId);
    }

    private static long downsampleCanopyCell(LodChunkSnapshot chunk, int startX, int startZ, int step) {
        if (chunk.columns.length < 512) return 0L;
        if (step <= 1) {
            return chunk.columns[256 + ((startZ << 4) | (startX & 15))];
        }
        int maxTopY = 0;
        int minBotY = 255;
        int sumR = 0, sumG = 0, sumB = 0;
        int count = 0;
        for (int dz = 0; dz < step && (startZ + dz) < 16; dz++) {
            for (int dx = 0; dx < step && (startX + dx) < 16; dx++) {
                long cCol = chunk.columns[256 + (((startZ + dz) << 4) | (startX + dx))];
                if (cCol == 0L) continue;
                int cTop = LodColumnPacker.unpackSolidY(cCol);
                int cBot = LodColumnPacker.unpackWaterY(cCol);
                if (cTop <= 0 || cTop < cBot) continue;
                count++;
                if (cTop > maxTopY) maxTopY = cTop;
                if (cBot < minBotY) minBotY = cBot;
                int rgb = LodColumnPacker.unpackRgb(cCol);
                sumR += (rgb >> 16) & 0xFF;
                sumG += (rgb >> 8) & 0xFF;
                sumB += rgb & 0xFF;
            }
        }
        if (count * 2 < step * step || maxTopY <= 0) return 0L;
        int avgRgb = (((sumR / count) & 0xFF) << 16) | (((sumG / count) & 0xFF) << 8) | ((sumB / count) & 0xFF);
        return LodColumnPacker.pack(maxTopY, minBotY, avgRgb, 15, 0, LodColumnPacker.FLAG_IS_FOLIAGE, 0);
    }

    private static float computeGrain(int wx, int wy, int wz) {
        int h = (wx * 73428767) ^ (wz * 912931) ^ (wy * 433494437);
        return 0.96f + ((h & 0x1F) / 31.0f) * 0.08f;
    }

    private static int boostRgb(int rgb, float boost) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * boost));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * boost));
        int b = Math.min(255, (int) ((rgb & 0xFF) * boost));
        return (r << 16) | (g << 8) | b;
    }

    private static boolean colorsClose(int rgb1, int rgb2) {
        int r1 = (rgb1 >> 16) & 0xFF, g1 = (rgb1 >> 8) & 0xFF, b1 = rgb1 & 0xFF;
        int r2 = (rgb2 >> 16) & 0xFF, g2 = (rgb2 >> 8) & 0xFF, b2 = rgb2 & 0xFF;
        return Math.abs(r1 - r2) <= 6 && Math.abs(g1 - g2) <= 6 && Math.abs(b1 - b2) <= 6;
    }

    private static int packRgba(int rgb, float shade, int alpha) {
        int r = Math.min(255, Math.max(0, (int) (((rgb >> 16) & 0xFF) * shade)));
        int g = Math.min(255, Math.max(0, (int) (((rgb >> 8) & 0xFF) * shade)));
        int b = Math.min(255, Math.max(0, (int) ((rgb & 0xFF) * shade)));
        return (r & 0xFF) | ((g & 0xFF) << 8) | ((b & 0xFF) << 16) | ((alpha & 0xFF) << 24);
    }

    private static void putVertex(
            ByteBuffer buf,
            float x, float y, float z,
            float u, float v,
            int rgbaLE,
            short lu, short lv,
            byte nx, byte ny, byte nz
    ) {
        buf.putFloat(x);
        buf.putFloat(y);
        buf.putFloat(z);
        buf.putFloat(u);
        buf.putFloat(v);
        buf.putInt(rgbaLE);
        buf.putShort(lu);
        buf.putShort(lv);
        buf.put(nx);
        buf.put(ny);
        buf.put(nz);
        buf.put((byte) 0);
    }
}
