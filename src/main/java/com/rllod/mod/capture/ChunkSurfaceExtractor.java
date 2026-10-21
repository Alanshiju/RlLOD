package com.rllod.mod.capture;

import net.minecraft.block.*;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

public final class ChunkSurfaceExtractor {

    private ChunkSurfaceExtractor() {}

    public static boolean isIgnoredClutter(IBlockState state) {
        if (state == null) return true;
        Material mat = state.getMaterial();
        if (mat == Material.AIR || mat == Material.PLANTS || mat == Material.VINE
                || mat == Material.CIRCUITS || mat == Material.CARPET || mat == Material.FIRE
                || mat == Material.REDSTONE_LIGHT || mat == Material.WEB || mat == Material.CORAL
                || mat == Material.SNOW) {
            return true;
        }
        Block block = state.getBlock();
        if (block == Blocks.SNOW_LAYER || block == Blocks.TALLGRASS || block == Blocks.DOUBLE_PLANT
                || block == Blocks.DEADBUSH || block == Blocks.RED_FLOWER || block == Blocks.YELLOW_FLOWER
                || block == Blocks.TORCH || block == Blocks.REDSTONE_TORCH || block == Blocks.LADDER) {
            return true;
        }
        ResourceLocation reg = block.getRegistryName();
        String name = (reg != null) ? reg.toString().toLowerCase() : "";
        if (name.contains("loose_rock") || name.contains("pebble") || name.contains("twig")
                || name.contains("torch") || name.contains("lantern") || name.contains("button")
                || name.contains("plate") || name.contains("rail") || name.contains("Floating")
                || name.contains("kelp") || name.contains("seagrass") || name.contains("coral")
                || name.contains("flower") || name.contains("mushroom") || name.contains("reeds")) {
            return true;
        }
        if (!state.isFullCube() && !state.isOpaqueCube() && !state.isFullBlock()) {
            return !(block instanceof BlockSlab || block instanceof BlockStairs
                    || block instanceof BlockLeaves || block instanceof BlockCactus
                    || block instanceof BlockLog || block == Blocks.GRASS_PATH
                    || name.contains("leaves") || name.contains("log")
                    || name.contains("branch") || name.contains("roof")
                    || name.contains("stair") || name.contains("slab"));
        }
        return false;
    }

    public static boolean isLeafBlock(IBlockState state) {
        if (state == null) return false;
        if (state.getMaterial() == Material.LEAVES || state.getBlock() instanceof BlockLeaves) {
            return true;
        }
        ResourceLocation reg = state.getBlock().getRegistryName();
        return reg != null && reg.toString().toLowerCase().contains("leaves");
    }

    public static boolean isWoodOrBranch(IBlockState state) {
        if (state == null) return false;
        Block block = state.getBlock();
        Material mat = state.getMaterial();
        ResourceLocation reg = block.getRegistryName();
        String name = (reg != null) ? reg.toString().toLowerCase() : "";
        if (block instanceof BlockLog || mat == Material.WOOD) {
            if (name.contains("log") || name.contains("branch") || name.contains("trunk") || name.contains("wood") || name.contains("bark")) {
                return true;
            }
        }
        return name.contains("branch") || name.contains("twig");
    }

    public static LodChunkSnapshot extract(World world, Chunk chunk) {
        final int chunkX = chunk.x;
        final int chunkZ = chunk.z;
        final int baseBlockX = chunkX << 4;
        final int baseBlockZ = chunkZ << 4;

        final long[] packedColumns = new long[512];
        final short[] groundStateIds = new short[256];
        final short[] canopyStateIds = new short[256];
        final byte[] solidBottomY = new byte[256];

        final ExtendedBlockStorage[] storageArrays = chunk.getBlockStorageArray();
        final byte[] biomeArray = chunk.getBiomeArray();
        final int maxSegmentY = Math.min(255, Math.max(0, chunk.getTopFilledSegment() + 15));
        final BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                final int colIdx = (localZ << 4) | localX;
                final int biomeId = biomeArray[colIdx] & 0xFF;

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
                    ExtendedBlockStorage storage = storageArrays[y >> 4];
                    if (storage == Chunk.NULL_BLOCK_STORAGE) {
                        if (canopyTopY > 0) canopyLocked = true;
                        y = (y & ~15);
                        continue;
                    }

                    IBlockState state = storage.get(localX, y & 15, localZ);
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

                    if (isIgnoredClutter(state)) {
                        continue;
                    }

                    if (isLeafBlock(state)) {
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
                    if (y > 50 && isWoodOrBranch(state)) {
                        boolean hasAirBelow = false;
                        for (int cy = y - 1; cy >= Math.max(1, y - 10); cy--) {
                            ExtendedBlockStorage sCheck = storageArrays[cy >> 4];
                            IBlockState stCheck = (sCheck != Chunk.NULL_BLOCK_STORAGE)
                                    ? sCheck.get(localX, cy & 15, localZ)
                                    : Blocks.AIR.getDefaultState();
                            if (stCheck.getMaterial() == Material.AIR || isIgnoredClutter(stCheck)) {
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
                        for (int sy = y - 1; sy >= 30; sy--) {
                            ExtendedBlockStorage s2 = storageArrays[sy >> 4];
                            IBlockState st2 = (s2 != Chunk.NULL_BLOCK_STORAGE) ? s2.get(localX, sy & 15, localZ) : Blocks.AIR.getDefaultState();
                            if (st2.getMaterial() == Material.AIR || isIgnoredClutter(st2) || isLeafBlock(st2) || isWoodOrBranch(st2)) {
                                airRun++;
                            } else if (st2.getMaterial() == Material.WATER) {
                                if (airRun >= 10) {
                                    lowerGroundY = sy;
                                    lowerGroundState = st2;
                                    break;
                                }
                            } else {
                                if (airRun >= 10) {
                                    lowerGroundY = sy;
                                    lowerGroundState = st2;
                                    break;
                                } else {
                                    scanBot = sy;
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
                    if (state.getBlock() instanceof BlockStairs || state.getBlock() instanceof BlockSlab) {
                        for (int wy = y - 1; wy >= Math.max(1, y - 2); wy--) {
                            ExtendedBlockStorage sWall = storageArrays[wy >> 4];
                            if (sWall != Chunk.NULL_BLOCK_STORAGE) {
                                IBlockState stWall = sWall.get(localX, wy & 15, localZ);
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

                int groundRgb = resolveBlockRgb(world, surfaceState, mPos, flags, biomeId, false);
                mPos.setPos(worldX, Math.min(255, Math.max(solidY, waterY) + 1), worldZ);
                int blockLight = Math.max(emittedLight, chunk.getLightFor(EnumSkyBlock.BLOCK, mPos));
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
                    if (isLeafBlock(canopyState) && canopyBottomY < 85 && canopyTopY <= 95) {
                        canopyBottomY = Math.max(canopyBottomY, canopyTopY - 6);
                    }
                    canopyBottomY = Math.max(solidY + 1, canopyBottomY);
                    mPos.setPos(worldX, canopyTopY, worldZ);
                    boolean isCanopyLeaf = isLeafBlock(canopyState);
                    int cFlags = isCanopyLeaf ? LodColumnPacker.FLAG_IS_FOLIAGE : 0;
                    int canopyRgb = resolveBlockRgb(world, canopyState, mPos, cFlags, biomeId, isCanopyLeaf);
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
    }

    public static int resolveBlockRgb(
            World world,
            IBlockState state,
            BlockPos pos,
            int flags,
            int biomeId,
            boolean isLeaf
    ) {
        Biome biome = Biome.getBiome(biomeId);
        if ((flags & LodColumnPacker.FLAG_HAS_WATER) != 0) {
            int waterMult = (biome != null) ? biome.getWaterColorMultiplier() : 0xFFFFFF;
            return multiplyColor(0x2F5FDE, waterMult);
        }
        if ((flags & LodColumnPacker.FLAG_IS_SNOW) != 0) {
            return 0xF4F8F8;
        }
        Block block = state.getBlock();
        if (block == Blocks.GRASS_PATH) {
            return 0x947B51;
        }
        if (isLeaf || isLeafBlock(state)) {
            int foliage = (biome != null && pos != null) ? biome.getFoliageColorAtPos(pos) : 0x48B518;
            return multiplyColor(0x646464, foliage);
        }
        if (block == Blocks.GRASS) {
            int grass = (biome != null && pos != null) ? biome.getGrassColorAtPos(pos) : 0x79C05A;
            return multiplyColor(0xA2A2A2, grass);
        }

        int spriteRgb = BlockTextureColorCache.getSpriteAverageRgb(state);
        if (spriteRgb != -1 && spriteRgb != 0) {
            return spriteRgb & 0xFFFFFF;
        }

        try {
            MapColor mc = state.getMapColor(world, pos);
            if (mc != null && mc.colorValue != 0) {
                return mc.colorValue & 0xFFFFFF;
            }
        } catch (Throwable ignored) {}
        return 0x7F7F7F;
    }

    public static int multiplyColor(int baseRgb, int multRgb) {
        int r = (((baseRgb >> 16) & 0xFF) * ((multRgb >> 16) & 0xFF)) / 255;
        int g = (((baseRgb >> 8) & 0xFF) * ((multRgb >> 8) & 0xFF)) / 255;
        int b = ((baseRgb & 0xFF) * (multRgb & 0xFF)) / 255;
        return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    private static int blendRgb(int rgb1, int rgb2, float ratio) {
        int r1 = (rgb1 >> 16) & 0xFF, g1 = (rgb1 >> 8) & 0xFF, b1 = rgb1 & 0xFF;
        int r2 = (rgb2 >> 16) & 0xFF, g2 = (rgb2 >> 8) & 0xFF, b2 = rgb2 & 0xFF;
        int r = (int) (r1 * (1.0f - ratio) + r2 * ratio);
        int g = (int) (g1 * (1.0f - ratio) + g2 * ratio);
        int b = (int) (b1 * (1.0f - ratio) + b2 * ratio);
        return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }
}
