package com.rllod.mod.capture;

public final class LodChunkSnapshot {
    public final int chunkX;
    public final int chunkZ;
    public final long timestampMs;

    /**
     * 512 packed 64-bit column entries:
     * - 0..255:   Primary Ground / Water layer indexed by (localZ << 4) | localX.
     * - 256..511: Upper Canopy / Roof Overhang layer indexed by 256 + (localZ << 4) | localX.
     */
    public final long[] columns;

    /**
     * Block state IDs (Block.BLOCK_STATE_IDS.get(state)) for 16x16 ground and canopy blocks:
     * Used for real 16x16 TextureAtlasSprite UV mappings on LOD 0 and LOD 1.
     */
    public final short[] groundStateIds;
    public final short[] canopyStateIds;
    public final byte[] solidBottomY;
    public final boolean hasComplexSkyStructure;

    public LodChunkSnapshot(int chunkX, int chunkZ, long[] columns) {
        this(chunkX, chunkZ, columns, new short[256], new short[256], new byte[256], false);
    }

    public LodChunkSnapshot(int chunkX, int chunkZ, long[] columns, short[] groundStateIds, short[] canopyStateIds) {
        this(chunkX, chunkZ, columns, groundStateIds, canopyStateIds, new byte[256], false);
    }

    public LodChunkSnapshot(int chunkX, int chunkZ, long[] columns, short[] groundStateIds, short[] canopyStateIds, byte[] solidBottomY) {
        this(chunkX, chunkZ, columns, groundStateIds, canopyStateIds, solidBottomY, false);
    }

    public LodChunkSnapshot(int chunkX, int chunkZ, long[] columns, short[] groundStateIds, short[] canopyStateIds, byte[] solidBottomY, boolean hasComplexSkyStructure) {
        if (columns == null) {
            throw new IllegalArgumentException("columns array cannot be null");
        }
        if (columns.length == 512) {
            this.columns = columns;
        } else if (columns.length == 256) {
            this.columns = new long[512];
            System.arraycopy(columns, 0, this.columns, 0, 256);
        } else {
            throw new IllegalArgumentException("columns array must have length 256 or 512, got " + columns.length);
        }
        this.groundStateIds = (groundStateIds != null && groundStateIds.length == 256) ? groundStateIds : new short[256];
        this.canopyStateIds = (canopyStateIds != null && canopyStateIds.length == 256) ? canopyStateIds : new short[256];
        this.solidBottomY = (solidBottomY != null && solidBottomY.length == 256) ? solidBottomY : new byte[256];
        this.hasComplexSkyStructure = hasComplexSkyStructure;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.timestampMs = System.currentTimeMillis();
    }

    public long getColumn(int localX, int localZ) {
        return this.columns[((localZ & 15) << 4) | (localX & 15)];
    }

    public long getCanopy(int localX, int localZ) {
        return this.columns[256 + (((localZ & 15) << 4) | (localX & 15))];
    }

    public short getGroundStateId(int localX, int localZ) {
        return this.groundStateIds[((localZ & 15) << 4) | (localX & 15)];
    }

    public short getCanopyStateId(int localX, int localZ) {
        return this.canopyStateIds[((localZ & 15) << 4) | (localX & 15)];
    }

    public byte getSolidBottomY(int localX, int localZ) {
        return this.solidBottomY[((localZ & 15) << 4) | (localX & 15)];
    }

    public static long toChunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }
}
