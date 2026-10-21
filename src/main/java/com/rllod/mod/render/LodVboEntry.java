package com.rllod.mod.render;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.math.AxisAlignedBB;

public final class LodVboEntry {
    public final int regionX;
    public final int regionZ;
    public final int lodLevel;
    public final int vboId;
    public final int solidVertexCount;
    public final int cutoutVertexCount;
    public final int waterVertexCount;
    public final int totalVertexCount;
    public final AxisAlignedBB boundingBox;
    public final int[] subChunkSolidFirst;
    public final int[] subChunkSolidCount;
    public final int[] subChunkCutoutFirst;
    public final int[] subChunkCutoutCount;
    public final int[] subChunkWaterFirst;
    public final int[] subChunkWaterCount;
    public final int exactSubChunkMask;
    public final int presentChunkMask;
    public long firstSeenMs = 0L;

    public LodVboEntry(
            int regionX,
            int regionZ,
            int lodLevel,
            int minY,
            int maxY,
            int solidVertexCount,
            int waterVertexCount,
            int[] subChunkSolidFirst,
            int[] subChunkSolidCount,
            int[] subChunkWaterFirst,
            int[] subChunkWaterCount,
            int presentChunkMask,
            int vboId
    ) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.lodLevel = lodLevel;
        this.vboId = vboId;
        this.solidVertexCount = solidVertexCount;
        this.cutoutVertexCount = 0;
        this.waterVertexCount = waterVertexCount;
        this.totalVertexCount = solidVertexCount + waterVertexCount;
        this.subChunkSolidFirst = subChunkSolidFirst;
        this.subChunkSolidCount = subChunkSolidCount;
        this.subChunkCutoutFirst = new int[16];
        this.subChunkCutoutCount = new int[16];
        this.subChunkWaterFirst = subChunkWaterFirst;
        this.subChunkWaterCount = subChunkWaterCount;
        this.exactSubChunkMask = 0;
        this.presentChunkMask = presentChunkMask;
        double minX = regionX << 6; // 64 blocks per 4x4 region
        double minZ = regionZ << 6;
        this.boundingBox = new AxisAlignedBB(minX, minY, minZ, minX + 64.0, maxY, minZ + 64.0);
    }

    public LodVboEntry(
            int regionX,
            int regionZ,
            int lodLevel,
            int vboId,
            int minY,
            int maxY,
            int solidVertexCount,
            int waterVertexCount,
            int[] subChunkSolidFirst,
            int[] subChunkSolidCount,
            int[] subChunkWaterFirst,
            int[] subChunkWaterCount,
            int presentChunkMask
    ) {
        this(regionX, regionZ, lodLevel, minY, maxY, solidVertexCount, waterVertexCount,
                subChunkSolidFirst, subChunkSolidCount, subChunkWaterFirst, subChunkWaterCount, presentChunkMask, vboId);
    }

    public LodVboEntry(
            int regionX,
            int regionZ,
            int lodLevel,
            int vboId,
            int solidVertexCount,
            int cutoutVertexCount,
            int waterVertexCount,
            int minY,
            int maxY,
            int[] subChunkSolidFirst,
            int[] subChunkSolidCount,
            int[] subChunkCutoutFirst,
            int[] subChunkCutoutCount,
            int[] subChunkWaterFirst,
            int[] subChunkWaterCount,
            int exactSubChunkMask,
            int presentChunkMask
    ) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.lodLevel = lodLevel;
        this.vboId = vboId;
        this.solidVertexCount = solidVertexCount;
        this.cutoutVertexCount = cutoutVertexCount;
        this.waterVertexCount = waterVertexCount;
        this.totalVertexCount = solidVertexCount + cutoutVertexCount + waterVertexCount;
        this.subChunkSolidFirst = subChunkSolidFirst;
        this.subChunkSolidCount = subChunkSolidCount;
        this.subChunkCutoutFirst = subChunkCutoutFirst;
        this.subChunkCutoutCount = subChunkCutoutCount;
        this.subChunkWaterFirst = subChunkWaterFirst;
        this.subChunkWaterCount = subChunkWaterCount;
        this.exactSubChunkMask = exactSubChunkMask;
        this.presentChunkMask = presentChunkMask;
        double minX = regionX << 6; // 64 blocks per 4x4 region
        double minZ = regionZ << 6;
        this.boundingBox = new AxisAlignedBB(minX, minY, minZ, minX + 64.0, maxY, minZ + 64.0);
    }

    public LodVboEntry(
            int regionX,
            int regionZ,
            int lodLevel,
            int vboId,
            int solidVertexCount,
            int cutoutVertexCount,
            int waterVertexCount,
            int minY,
            int maxY,
            int[] subChunkSolidFirst,
            int[] subChunkSolidCount,
            int[] subChunkCutoutFirst,
            int[] subChunkCutoutCount,
            int[] subChunkWaterFirst,
            int[] subChunkWaterCount,
            int exactSubChunkMask
    ) {
        this(regionX, regionZ, lodLevel, vboId, solidVertexCount, cutoutVertexCount, waterVertexCount,
                minY, maxY, subChunkSolidFirst, subChunkSolidCount, subChunkCutoutFirst, subChunkCutoutCount,
                subChunkWaterFirst, subChunkWaterCount, exactSubChunkMask, 0);
    }

    public LodVboEntry(
            int regionX,
            int regionZ,
            int lodLevel,
            int vboId,
            int solidVertexCount,
            int cutoutVertexCount,
            int waterVertexCount,
            int minY,
            int maxY,
            int[] subChunkSolidFirst,
            int[] subChunkSolidCount,
            int[] subChunkCutoutFirst,
            int[] subChunkCutoutCount,
            int[] subChunkWaterFirst,
            int[] subChunkWaterCount
    ) {
        this(regionX, regionZ, lodLevel, vboId, solidVertexCount, cutoutVertexCount, waterVertexCount,
                minY, maxY, subChunkSolidFirst, subChunkSolidCount, subChunkCutoutFirst, subChunkCutoutCount,
                subChunkWaterFirst, subChunkWaterCount, 0, 0);
    }

    public void deleteGlResources() {
        if (this.vboId > 0) {
            OpenGlHelper.glDeleteBuffers(this.vboId);
        }
    }
}
