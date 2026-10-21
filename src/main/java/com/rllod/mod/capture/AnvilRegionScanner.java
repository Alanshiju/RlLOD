package com.rllod.mod.capture;

import com.rllod.mod.RlLodMod;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.storage.RegionFileCache;

import java.io.DataInputStream;
import java.io.File;

public final class AnvilRegionScanner {

    private AnvilRegionScanner() {}

    public static File getDimensionDirectory(File worldDir, int dimensionId) {
        if (worldDir == null) {
            return null;
        }
        if (dimensionId == 0) {
            return worldDir;
        }
        File dimDir = new File(worldDir, "DIM" + dimensionId);
        if (dimDir.exists() && dimDir.isDirectory()) {
            return dimDir;
        }
        return worldDir;
    }

    public static LodChunkSnapshot scanChunk(File dimDir, int chunkX, int chunkZ) {
        if (dimDir == null) {
            return null;
        }

        DataInputStream dis = null;
        try {
            dis = RegionFileCache.getChunkInputStream(dimDir, chunkX, chunkZ);
            if (dis == null) {
                return null;
            }

            NBTTagCompound root = CompressedStreamTools.read(dis);
            if (root == null || !root.hasKey("Level", 10)) {
                return null;
            }

            NBTTagCompound level = root.getCompoundTag("Level");
            NBTTagList sections = level.getTagList("Sections", 10);
            byte[] biomes = level.hasKey("Biomes", 7) ? level.getByteArray("Biomes") : new byte[256];

            NBTTagCompound[] sectionMap = new NBTTagCompound[16];
            for (int i = 0; i < sections.tagCount(); i++) {
                NBTTagCompound s = sections.getCompoundTagAt(i);
                int sy = s.getByte("Y") & 0xFF;
                if (sy >= 0 && sy < 16) {
                    sectionMap[sy] = s;
                }
            }

            byte[][] sectionBlocks = new byte[16][];
            byte[][] sectionData = new byte[16][];
            byte[][] sectionAdd = new byte[16][];
            byte[][] sectionBlockLight = new byte[16][];

            int maxSegmentY = 0;
            for (int sy = 0; sy < 16; sy++) {
                NBTTagCompound s = sectionMap[sy];
                if (s != null) {
                    if (s.hasKey("Blocks", 7)) {
                        sectionBlocks[sy] = s.getByteArray("Blocks");
                        maxSegmentY = Math.max(maxSegmentY, (sy << 4) | 15);
                    }
                    if (s.hasKey("Data", 7)) sectionData[sy] = s.getByteArray("Data");
                    if (s.hasKey("Add", 7)) sectionAdd[sy] = s.getByteArray("Add");
                    if (s.hasKey("BlockLight", 7)) sectionBlockLight[sy] = s.getByteArray("BlockLight");
                }
            }

            if (maxSegmentY == 0) {
                maxSegmentY = 255;
            }

            final int baseBlockX = chunkX << 4;
            final int baseBlockZ = chunkZ << 4;

            final long[] packedColumns = new long[512];
            final short[] groundStateIds = new short[256];
            final short[] canopyStateIds = new short[256];
            final byte[] solidBottomY = new byte[256];
            final BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    final int colIdx = (localZ << 4) | localX;
                    final int biomeId = (biomes.length > colIdx) ? (biomes[colIdx] & 0xFF) : 0;

                    int solidY = 0;
                    int bottomOfSolidY = 0;
                    int waterY = 0;
                    int flags = 0;
                    int emittedLight = 0;
                    IBlockState surfaceState = Blocks.STONE.getDefaultState();

                    int canopyTopY = 0;
                    int canopyBottomY = 0;
                    IBlockState canopyState = null;
                    boolean canopyLocked = false;
                    int airBelowCanopy = 0;

                    boolean pendingSnowLayer = false;
                    boolean canopyHasSnow = false;

                    for (int y = maxSegmentY; y >= 1; y--) {
                        int sy = y >> 4;
                        if (sy >= sectionBlocks.length || sectionBlocks[sy] == null) {
                            if (canopyTopY > 0) canopyLocked = true;
                            y = (y & ~15);
                            continue;
                        }

                        IBlockState state = getBlockState(sectionBlocks, sectionData, sectionAdd, localX, y, localZ);
                        Material mat = state.getMaterial();
                        if (mat == Material.AIR) {
                            if (canopyTopY > 0 && !canopyLocked) {
                                airBelowCanopy++;
                                if (airBelowCanopy >= 2) {
                                    canopyLocked = true;
                                }
                            }
                            continue;
                        }

                        int lv = state.getLightValue();
                        if (lv > emittedLight) emittedLight = lv;

                        Block block = state.getBlock();
                        if (block == Blocks.SNOW_LAYER || mat == Material.SNOW || mat == Material.CRAFTED_SNOW) {
                            pendingSnowLayer = true;
                            continue;
                        }
                        if (block == Blocks.TALLGRASS || block == Blocks.DOUBLE_PLANT) {
                            flags |= LodColumnPacker.FLAG_HAS_TALLGRASS;
                            continue;
                        }

                        if (mat == Material.WATER) {
                            pendingSnowLayer = false;
                            if (solidY == 0 && waterY == 0 && y <= 65) {
                                waterY = y;
                                flags |= LodColumnPacker.FLAG_HAS_WATER;
                            }
                            continue;
                        }

                        if (ChunkSurfaceExtractor.isIgnoredClutter(state)) {
                            continue;
                        }

                        if (ChunkSurfaceExtractor.isLeafBlock(state)) {
                            if (pendingSnowLayer) {
                                canopyHasSnow = true;
                                pendingSnowLayer = false;
                            }
                            if (!canopyLocked) {
                                if (canopyTopY == 0) {
                                    canopyTopY = y;
                                    canopyState = state;
                                }
                                canopyBottomY = y;
                                airBelowCanopy = 0;
                            }
                            continue;
                        }

                        // Tree branch / limb check: if there is ANY air block within 1 to 10 blocks below, treat as canopy
                        if (y > 50 && ChunkSurfaceExtractor.isWoodOrBranch(state)) {
                            boolean hasAirBelow = false;
                            for (int cy = y - 1; cy >= Math.max(1, y - 10); cy--) {
                                int csy = cy >> 4;
                                IBlockState stCheck = (csy < sectionBlocks.length && sectionBlocks[csy] != null)
                                        ? getBlockState(sectionBlocks, sectionData, sectionAdd, localX, cy, localZ)
                                        : Blocks.AIR.getDefaultState();
                                if (stCheck.getMaterial() == Material.AIR || ChunkSurfaceExtractor.isIgnoredClutter(stCheck)) {
                                    hasAirBelow = true;
                                    break;
                                }
                            }
                            if (hasAirBelow) {
                                if (pendingSnowLayer) {
                                    canopyHasSnow = true;
                                    pendingSnowLayer = false;
                                }
                                if (!canopyLocked) {
                                    if (canopyTopY == 0) {
                                        canopyTopY = y;
                                        canopyState = state;
                                    }
                                    canopyBottomY = y;
                                    airBelowCanopy = 0;
                                }
                                continue;
                            }
                        }

                        // Hit solid ground block!
                        if (pendingSnowLayer) {
                            flags |= LodColumnPacker.FLAG_IS_SNOW;
                            pendingSnowLayer = false;
                        }

                        // Check if this high block (> 88) is a floating sky structure over a >= 10-block open sky gap
                        if (y > 88) {
                            int scanBot = y;
                            int airRun = 0;
                            int lowerGroundY = 0;
                            IBlockState lowerGroundState = null;
                            for (int sy2 = y - 1; sy2 >= 30; sy2--) {
                                int sIdx = sy2 >> 4;
                                IBlockState st2 = (sIdx < sectionBlocks.length && sectionBlocks[sIdx] != null)
                                        ? getBlockState(sectionBlocks, sectionData, sectionAdd, localX, sy2, localZ)
                                        : Blocks.AIR.getDefaultState();
                                if (st2.getMaterial() == Material.AIR || ChunkSurfaceExtractor.isIgnoredClutter(st2) || ChunkSurfaceExtractor.isLeafBlock(st2) || ChunkSurfaceExtractor.isWoodOrBranch(st2)) {
                                    airRun++;
                                } else if (st2.getMaterial() == Material.WATER) {
                                    if (airRun >= 10) {
                                        lowerGroundY = sy2;
                                        lowerGroundState = st2;
                                        break;
                                    }
                                } else {
                                    if (airRun >= 10) {
                                        lowerGroundY = sy2;
                                        lowerGroundState = st2;
                                        break;
                                    } else {
                                        scanBot = sy2;
                                        airRun = 0;
                                    }
                                }
                            }

                            if (airRun >= 10 && lowerGroundY > 0) {
                                // True floating sky structure! Put [scanBot..top] into canopy/floating box, and lowerGroundY into solidY!
                                if (canopyTopY == 0) {
                                    canopyTopY = y;
                                    canopyState = state;
                                } else {
                                    canopyTopY = Math.max(canopyTopY, y);
                                }
                                canopyBottomY = scanBot;
                                solidY = lowerGroundY;
                                bottomOfSolidY = 1;
                                surfaceState = lowerGroundState;
                                break;
                            }
                        }

                        solidY = y;
                        surfaceState = state;
                        bottomOfSolidY = 1;
                        if (state.getBlock() instanceof net.minecraft.block.BlockStairs || state.getBlock() instanceof net.minecraft.block.BlockSlab) {
                            for (int wy = y - 1; wy >= Math.max(1, y - 2); wy--) {
                                int wsy = wy >> 4;
                                if (wsy < sectionBlocks.length && sectionBlocks[wsy] != null) {
                                    IBlockState stWall = getBlockState(sectionBlocks, sectionData, sectionAdd, localX, wy, localZ);
                                    if (stWall.isFullBlock() || stWall.isOpaqueCube() || stWall.isFullCube()) {
                                        break;
                                    }
                                }
                            }
                        }
                        break;
                    }

                    if (solidY <= 0) {
                        solidY = 62;
                        surfaceState = Blocks.STONE.getDefaultState();
                    }
                    if (waterY <= solidY) {
                        waterY = 0;
                        flags &= ~LodColumnPacker.FLAG_HAS_WATER;
                    }

                    final int worldX = baseBlockX + localX;
                    final int worldZ = baseBlockZ + localZ;
                    mPos.setPos(worldX, solidY, worldZ);

                    int groundRgb = ChunkSurfaceExtractor.resolveBlockRgb(null, surfaceState, mPos, flags, biomeId, false);
                    int lightAtY = Math.min(255, Math.max(solidY, waterY) + 1);
                    int blockLight = Math.max(emittedLight, getSectionBlockLight(sectionBlockLight, localX, lightAtY, localZ));
                    int skyLight = (canopyTopY > solidY) ? 13 : 15;

                    packedColumns[colIdx] = LodColumnPacker.pack(
                            solidY,
                            waterY,
                            groundRgb,
                            skyLight,
                            blockLight,
                            flags,
                            biomeId
                    );
                    groundStateIds[colIdx] = (short) Block.BLOCK_STATE_IDS.get(surfaceState);
                    solidBottomY[colIdx] = (byte) Math.min(solidY, Math.max(1, bottomOfSolidY));

                    if (canopyTopY > solidY && canopyState != null) {
                        // Only apply -6 leaf thickness cap on normal ground trees (canopyTopY <= 95 and canopyBottomY < 85)
                        if (ChunkSurfaceExtractor.isLeafBlock(canopyState) && canopyBottomY < 85 && canopyTopY <= 95) {
                            canopyBottomY = Math.max(canopyBottomY, canopyTopY - 6);
                        }
                        canopyBottomY = Math.max(solidY + 1, canopyBottomY);
                        mPos.setPos(worldX, canopyTopY, worldZ);
                        boolean isCanopyLeaf = ChunkSurfaceExtractor.isLeafBlock(canopyState);
                        int cFlags = isCanopyLeaf ? LodColumnPacker.FLAG_IS_FOLIAGE : 0;
                        int canopyRgb = ChunkSurfaceExtractor.resolveBlockRgb(null, canopyState, mPos, cFlags, biomeId, isCanopyLeaf);
                        if (canopyHasSnow) {
                            canopyRgb = blendRgb(canopyRgb, 0xF4F8F8, 0.25f);
                        }
                        packedColumns[256 + colIdx] = LodColumnPacker.pack(
                                canopyTopY,
                                canopyBottomY,
                                canopyRgb,
                                15,
                                blockLight,
                                cFlags,
                                biomeId
                        );
                        canopyStateIds[colIdx] = (short) Block.BLOCK_STATE_IDS.get(canopyState);
                    } else {
                        packedColumns[256 + colIdx] = 0L;
                        canopyStateIds[colIdx] = 0;
                    }
                }
            }

            // Post-Pass Outlier Pillar Filter: eliminates 1-block chains/waterfalls hanging under floating sky structures
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int colIdx = (lz << 4) | lx;
                    long col = packedColumns[colIdx];
                    int solidY = LodColumnPacker.unpackSolidY(col);
                    if (solidY >= 95) {
                        int floatingNeighbors = 0;
                        int sumNeighborGroundY = 0;
                        int minNeighborCanopyBot = 255;
                        for (int dz = -2; dz <= 2; dz++) {
                            int nz = lz + dz;
                            if (nz < 0 || nz >= 16) continue;
                            for (int dx = -2; dx <= 2; dx++) {
                                if (dx == 0 && dz == 0) continue;
                                int nx = lx + dx;
                                if (nx < 0 || nx >= 16) continue;
                                int nIdx = (nz << 4) | nx;
                                long nCanopy = packedColumns[256 + nIdx];
                                if (nCanopy != 0L) {
                                    int nTop = LodColumnPacker.unpackCanopyTopY(nCanopy);
                                    int nBot = LodColumnPacker.unpackCanopyBottomY(nCanopy);
                                    int nSolid = LodColumnPacker.unpackSolidY(packedColumns[nIdx]);
                                    if (nTop >= 95 && nSolid <= 85) {
                                        floatingNeighbors++;
                                        sumNeighborGroundY += nSolid;
                                        if (nBot < minNeighborCanopyBot) minNeighborCanopyBot = nBot;
                                    }
                                }
                            }
                        }
                        if (floatingNeighbors >= 3) {
                            int avgGroundY = sumNeighborGroundY / floatingNeighbors;
                            if (avgGroundY <= 0) avgGroundY = 63;
                            int targetCanopyBot = (minNeighborCanopyBot < 255) ? minNeighborCanopyBot : Math.min(solidY, 95);
                            long oldCanopy = packedColumns[256 + colIdx];
                            int newCanopyTop = (oldCanopy != 0L) ? Math.max(solidY, LodColumnPacker.unpackCanopyTopY(oldCanopy)) : solidY;
                            int newCanopyBot = targetCanopyBot;
                            int canopyRgb = (oldCanopy != 0L) ? LodColumnPacker.unpackCanopyRgb(oldCanopy) : LodColumnPacker.unpackRgb(col);
                            int cFlags = (oldCanopy != 0L) ? LodColumnPacker.unpackFlags(oldCanopy) : 0;
                            int biomeId = LodColumnPacker.unpackBiomeId(col);
                            packedColumns[256 + colIdx] = LodColumnPacker.pack(
                                    newCanopyTop,
                                    newCanopyBot,
                                    canopyRgb,
                                    15,
                                    LodColumnPacker.unpackBlockLight(col),
                                    cFlags,
                                    biomeId
                            );
                            int gFlags = LodColumnPacker.unpackFlags(col) & ~LodColumnPacker.FLAG_IS_SNOW;
                            packedColumns[colIdx] = LodColumnPacker.pack(
                                    avgGroundY,
                                    0,
                                    LodColumnPacker.unpackRgb(col),
                                    15,
                                    0,
                                    gFlags,
                                    biomeId
                            );
                            solidBottomY[colIdx] = (byte) Math.max(1, avgGroundY - 4);
                        }
                    }
                }
            }

            return new LodChunkSnapshot(chunkX, chunkZ, packedColumns, groundStateIds, canopyStateIds, solidBottomY);
        } catch (Throwable t) {
            RlLodMod.LOGGER.debug("[RlLOD] Anvil scanner error reading chunk ({}, {})", chunkX, chunkZ, t);
            return null;
        } finally {
            if (dis != null) {
                try {
                    dis.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static IBlockState getBlockState(byte[][] sectionBlocks, byte[][] sectionData, byte[][] sectionAdd, int lx, int y, int lz) {
        if (y < 0 || y > 255) return Blocks.AIR.getDefaultState();
        int sy = y >> 4;
        if (sy >= sectionBlocks.length) return Blocks.AIR.getDefaultState();
        byte[] blocks = sectionBlocks[sy];
        byte[] data = sectionData != null && sy < sectionData.length ? sectionData[sy] : null;
        if (blocks == null || data == null) return Blocks.AIR.getDefaultState();
        byte[] add = sectionAdd != null && sy < sectionAdd.length ? sectionAdd[sy] : null;
        int dy = y & 15;
        int blockIdx = (dy << 8) | (lz << 4) | lx;
        int id = blocks[blockIdx] & 0xFF;
        if (add != null && (blockIdx / 2) < add.length) {
            int addNibble = (blockIdx % 2 == 0)
                    ? (add[blockIdx / 2] & 0x0F)
                    : ((add[blockIdx / 2] >>> 4) & 0x0F);
            id |= (addNibble << 8);
        }
        int meta = (blockIdx / 2 < data.length)
                ? ((blockIdx % 2 == 0) ? (data[blockIdx / 2] & 0x0F) : ((data[blockIdx / 2] >>> 4) & 0x0F))
                : 0;
        Block block = Block.getBlockById(id);
        return block.getStateFromMeta(meta);
    }

    private static int getSectionBlockLight(byte[][] sectionBlockLight, int lx, int y, int lz) {
        if (sectionBlockLight == null || y < 0 || y > 255) return 0;
        int sy = y >> 4;
        if (sy >= sectionBlockLight.length) return 0;
        byte[] bl = sectionBlockLight[sy];
        if (bl == null) return 0;
        int dy = y & 15;
        int blockIdx = (dy << 8) | (lz << 4) | lx;
        if ((blockIdx / 2) >= bl.length) return 0;
        return (blockIdx % 2 == 0) ? (bl[blockIdx / 2] & 0x0F) : ((bl[blockIdx / 2] >>> 4) & 0x0F);
    }

    private static int blendRgb(int rgb1, int rgb2, float ratio) {
        int r1 = (rgb1 >> 16) & 0xFF, g1 = (rgb1 >> 8) & 0xFF, b1 = rgb1 & 0xFF;
        int r2 = (rgb2 >> 16) & 0xFF, g2 = (rgb2 >> 8) & 0xFF, b2 = rgb2 & 0xFF;
        int r = (int) (r1 * (1.0f - ratio) + r2 * ratio);
        int g = (int) (g1 * (1.0f - ratio) + g2 * ratio);
        int b = (int) (b1 * (1.0f - ratio) + b2 * ratio);
        return (r << 16) | (g << 8) | b;
    }
}
