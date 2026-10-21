package com.rllod.mod.meshing;

import java.nio.ByteBuffer;

public final class LodMeshData {
    // 32-byte vertex stride:
    // Pos: 3 floats (12 bytes)
    // UV: 2 floats (8 bytes)
    // Color: 4 unsigned bytes (4 bytes)
    // Light: 2 shorts (4 bytes)
    // Normal: 3 bytes + 1 byte pad (4 bytes)
    public static final int STRIDE_BYTES = 32;

    public final int regionX;
    public final int regionZ;
    public final int lodLevel;
    public final int minY;
    public final int maxY;
    public final int solidVertexCount;
    public final int cutoutVertexCount;
    public final int waterVertexCount;
    public final int totalVertexCount;
    public final int vertexCount;
    public final ByteBuffer vertexBuffer;
    public final int[] subChunkSolidFirst;
    public final int[] subChunkSolidCount;
    public final int[] subChunkCutoutFirst;
    public final int[] subChunkCutoutCount;
    public final int[] subChunkWaterFirst;
    public final int[] subChunkWaterCount;
    public final int exactSubChunkMask;
    public final int presentChunkMask;

    public LodMeshData(
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
            ByteBuffer vertexBuffer
    ) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.lodLevel = lodLevel;
        this.minY = minY;
        this.maxY = maxY;
        this.solidVertexCount = solidVertexCount;
        this.cutoutVertexCount = 0;
        this.waterVertexCount = waterVertexCount;
        this.totalVertexCount = solidVertexCount + waterVertexCount;
        this.vertexCount = this.totalVertexCount;
        this.vertexBuffer = vertexBuffer;
        this.subChunkSolidFirst = subChunkSolidFirst;
        this.subChunkSolidCount = subChunkSolidCount;
        this.subChunkCutoutFirst = new int[16];
        this.subChunkCutoutCount = new int[16];
        this.subChunkWaterFirst = subChunkWaterFirst;
        this.subChunkWaterCount = subChunkWaterCount;
        this.exactSubChunkMask = 0;
        this.presentChunkMask = presentChunkMask;
    }

    public LodMeshData(
            int regionX,
            int regionZ,
            int lodLevel,
            int minY,
            int maxY,
            int solidVertexCount,
            int cutoutVertexCount,
            int waterVertexCount,
            ByteBuffer vertexBuffer,
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
        this.minY = minY;
        this.maxY = maxY;
        this.solidVertexCount = solidVertexCount;
        this.cutoutVertexCount = cutoutVertexCount;
        this.waterVertexCount = waterVertexCount;
        this.totalVertexCount = solidVertexCount + cutoutVertexCount + waterVertexCount;
        this.vertexCount = this.totalVertexCount;
        this.vertexBuffer = vertexBuffer;
        this.subChunkSolidFirst = subChunkSolidFirst;
        this.subChunkSolidCount = subChunkSolidCount;
        this.subChunkCutoutFirst = subChunkCutoutFirst;
        this.subChunkCutoutCount = subChunkCutoutCount;
        this.subChunkWaterFirst = subChunkWaterFirst;
        this.subChunkWaterCount = subChunkWaterCount;
        this.exactSubChunkMask = exactSubChunkMask;
        this.presentChunkMask = presentChunkMask;
    }

    public LodMeshData(
            int regionX,
            int regionZ,
            int lodLevel,
            int minY,
            int maxY,
            int solidVertexCount,
            int cutoutVertexCount,
            int waterVertexCount,
            ByteBuffer vertexBuffer,
            int[] subChunkSolidFirst,
            int[] subChunkSolidCount,
            int[] subChunkCutoutFirst,
            int[] subChunkCutoutCount,
            int[] subChunkWaterFirst,
            int[] subChunkWaterCount,
            int exactSubChunkMask
    ) {
        this(regionX, regionZ, lodLevel, minY, maxY, solidVertexCount, cutoutVertexCount, waterVertexCount,
                vertexBuffer, subChunkSolidFirst, subChunkSolidCount, subChunkCutoutFirst, subChunkCutoutCount,
                subChunkWaterFirst, subChunkWaterCount, exactSubChunkMask, 0);
    }

    public LodMeshData(
            int regionX,
            int regionZ,
            int lodLevel,
            int minY,
            int maxY,
            int solidVertexCount,
            int cutoutVertexCount,
            int waterVertexCount,
            ByteBuffer vertexBuffer,
            int[] subChunkSolidFirst,
            int[] subChunkSolidCount,
            int[] subChunkCutoutFirst,
            int[] subChunkCutoutCount,
            int[] subChunkWaterFirst,
            int[] subChunkWaterCount
    ) {
        this(regionX, regionZ, lodLevel, minY, maxY, solidVertexCount, cutoutVertexCount, waterVertexCount,
                vertexBuffer, subChunkSolidFirst, subChunkSolidCount, subChunkCutoutFirst, subChunkCutoutCount,
                subChunkWaterFirst, subChunkWaterCount, 0, 0);
    }

    public static long toRegionKey(int regionX, int regionZ) {
        return ((long) regionX & 0xFFFFFFFFL) | (((long) regionZ & 0xFFFFFFFFL) << 32);
    }
}
