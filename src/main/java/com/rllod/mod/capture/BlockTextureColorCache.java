package com.rllod.mod.capture;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockTextureColorCache {

    public static final class SpriteUvInfo {
        public final float upMinU, upMaxU, upMinV, upMaxV;
        public final boolean upNeedsBiomeTint;
        public final float sideMinU, sideMaxU, sideMinV, sideMaxV;
        public final boolean sideNeedsBiomeTint;
        public final float colorBoost;
        public final int averageRgb;
        public final boolean isLeaves;
        public final boolean isGrass;
        public final boolean isAvailable;

        // Backward-compatibility aliases
        public final float minU, maxU, minV, maxV;
        public final boolean upHasTint;
        public final boolean sideHasTint;
        public final boolean hasTint;

        public SpriteUvInfo(float upMinU, float upMaxU, float upMinV, float upMaxV, boolean upNeedsBiomeTint,
                            float sideMinU, float sideMaxU, float sideMinV, float sideMaxV, boolean sideNeedsBiomeTint,
                            float colorBoost, int averageRgb, boolean isLeaves, boolean isGrass, boolean isAvailable) {
            this.upMinU = upMinU;
            this.upMaxU = upMaxU;
            this.upMinV = upMinV;
            this.upMaxV = upMaxV;
            this.upNeedsBiomeTint = upNeedsBiomeTint;
            this.sideMinU = sideMinU;
            this.sideMaxU = sideMaxU;
            this.sideMinV = sideMinV;
            this.sideMaxV = sideMaxV;
            this.sideNeedsBiomeTint = sideNeedsBiomeTint;
            this.colorBoost = colorBoost;
            this.averageRgb = averageRgb;
            this.isLeaves = isLeaves;
            this.isGrass = isGrass;
            this.isAvailable = isAvailable;

            this.minU = upMinU;
            this.maxU = upMaxU;
            this.minV = upMinV;
            this.maxV = upMaxV;
            this.upHasTint = upNeedsBiomeTint;
            this.sideHasTint = sideNeedsBiomeTint;
            this.hasTint = upNeedsBiomeTint;
        }

        public SpriteUvInfo(float minU, float maxU, float minV, float maxV,
                            float sideMinU, float sideMaxU, float sideMinV, float sideMaxV,
                            float colorBoost, boolean hasTint, boolean isLeaves, boolean isGrass, boolean isAvailable) {
            this(minU, maxU, minV, maxV, hasTint, sideMinU, sideMaxU, sideMinV, sideMaxV, hasTint, colorBoost, -1, isLeaves, isGrass, isAvailable);
        }
    }

    public static final class StateTextureInfo {
        public final int averageRgb;
        public final boolean hasTint;
        public final TextureAtlasSprite sprite;
        public final String iconName;

        public StateTextureInfo(int averageRgb, boolean hasTint, TextureAtlasSprite sprite) {
            this.averageRgb = averageRgb;
            this.hasTint = hasTint;
            this.sprite = sprite;
            this.iconName = (sprite != null) ? sprite.getIconName() : "";
        }
    }

    private static final ConcurrentHashMap<IBlockState, StateTextureInfo> CACHE = new ConcurrentHashMap<>(2048);
    private static final SpriteUvInfo NULL_SPRITE = new SpriteUvInfo(0, 0, 0, 0, false, 0, 0, 0, 0, false, 1.0f, -1, false, false, false);
    private static final SpriteUvInfo[] STATE_UV_ARRAY = new SpriteUvInfo[65536];
    private static final ConcurrentHashMap<Integer, SpriteUvInfo> STATE_UV_MAP = new ConcurrentHashMap<>(1024);

    public static int boostRgb(int rgb, float factor) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((rgb & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    private BlockTextureColorCache() {}

    /**
     * Returns full sprite metadata for the given block state, querying the top EnumFacing.UP quad.
     */
    public static StateTextureInfo getTextureInfo(IBlockState state) {
        if (state == null) {
            return null;
        }
        StateTextureInfo cached = CACHE.get(state);
        if (cached != null) {
            return cached;
        }

        StateTextureInfo computed = computeTextureInfo(state);
        CACHE.put(state, computed);
        return computed;
    }

    /**
     * Returns the average 24-bit RGB color of the block state's actual top TextureAtlasSprite.
     * Returns -1 if the texture sprite has no readable pixel frame data.
     */
    public static int getSpriteAverageRgb(IBlockState state) {
        StateTextureInfo info = getTextureInfo(state);
        return (info != null) ? info.averageRgb : -1;
    }

    public static int getColorForStateId(int stateId) {
        if (stateId <= 0) return -1;
        IBlockState state = Block.BLOCK_STATE_IDS.getByValue(stateId);
        if (state == null) return -1;
        return getSpriteAverageRgb(state);
    }

    public static boolean hasTintIndex(IBlockState state) {
        StateTextureInfo info = getTextureInfo(state);
        return info != null && info.hasTint;
    }

    public static TextureAtlasSprite getTopSprite(IBlockState state) {
        StateTextureInfo info = getTextureInfo(state);
        return (info != null) ? info.sprite : null;
    }

    private static StateTextureInfo computeTextureInfo(IBlockState state) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getBlockRendererDispatcher() == null) {
                return new StateTextureInfo(-1, false, null);
            }

            TextureAtlasSprite sprite = null;
            boolean hasTint = false;

            try {
                IBakedModel model = mc.getBlockRendererDispatcher().getModelForState(state);
                if (model != null) {
                    List<BakedQuad> upQuads = model.getQuads(state, EnumFacing.UP, 0L);
                    if (upQuads != null && !upQuads.isEmpty() && upQuads.get(0) != null && upQuads.get(0).getSprite() != null) {
                        sprite = upQuads.get(0).getSprite();
                        hasTint = upQuads.get(0).hasTintIndex();
                    } else {
                        List<BakedQuad> generalQuads = model.getQuads(state, null, 0L);
                        if (generalQuads != null && !generalQuads.isEmpty() && generalQuads.get(0) != null && generalQuads.get(0).getSprite() != null) {
                            sprite = generalQuads.get(0).getSprite();
                            hasTint = generalQuads.get(0).hasTintIndex();
                        }
                    }
                    if (sprite == null || "missingno".equals(sprite.getIconName())) {
                        TextureAtlasSprite particle = model.getParticleTexture();
                        if (particle != null && !"missingno".equals(particle.getIconName())) {
                            sprite = particle;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            if ((sprite == null || "missingno".equals(sprite.getIconName())) && mc.getBlockRendererDispatcher().getBlockModelShapes() != null) {
                sprite = mc.getBlockRendererDispatcher().getBlockModelShapes().getTexture(state);
            }

            String regName = (state.getBlock().getRegistryName() != null)
                    ? state.getBlock().getRegistryName().toString().toLowerCase() : "";
            if (state.getBlock() == net.minecraft.init.Blocks.CACTUS || regName.contains("cactus")) {
                hasTint = false;
                if (sprite == null || "missingno".equals(sprite.getIconName())) {
                    try {
                        sprite = mc.getTextureMapBlocks().getAtlasSprite("minecraft:blocks/cactus_top");
                    } catch (Throwable ignored) {}
                }
            } else if (state.getBlock() == net.minecraft.init.Blocks.GRASS_PATH || regName.contains("grass_path") || regName.contains("path")) {
                hasTint = false;
                if (sprite == null || "missingno".equals(sprite.getIconName())) {
                    try {
                        sprite = mc.getTextureMapBlocks().getAtlasSprite("minecraft:blocks/grass_path_top");
                    } catch (Throwable ignored) {}
                }
            } else if (regName.contains("leaves") || state.getMaterial() == Material.LEAVES) {
                hasTint = true;
            }

            if (sprite == null || "missingno".equals(sprite.getIconName()) || sprite.getFrameCount() <= 0) {
                return new StateTextureInfo(-1, hasTint, sprite);
            }

            int[][] frameData = sprite.getFrameTextureData(0);
            if (frameData == null || frameData.length == 0 || frameData[0] == null) {
                return new StateTextureInfo(-1, hasTint, sprite);
            }

            int[] pixels = frameData[0];
            long sumR = 0, sumG = 0, sumB = 0;
            int count = 0;
            for (int pixel : pixels) {
                int a = (pixel >>> 24) & 0xFF;
                if (a < 32) {
                    continue; // Skip transparent pixels
                }
                int red = (pixel >>> 16) & 0xFF;
                int green = (pixel >>> 8) & 0xFF;
                int blue = pixel & 0xFF;
                sumR += red;
                sumG += green;
                sumB += blue;
                count++;
            }

            if (count == 0) {
                return new StateTextureInfo(-1, hasTint, sprite);
            }

            int avgR = (int) (sumR / count);
            int avgG = (int) (sumG / count);
            int avgB = (int) (sumB / count);
            int rgb = ((avgR & 0xFF) << 16) | ((avgG & 0xFF) << 8) | (avgB & 0xFF);

            return new StateTextureInfo(rgb, hasTint, sprite);
        } catch (Throwable ignored) {
            return new StateTextureInfo(-1, false, null);
        }
    }

    /**
     * Fast cached lookup of 16x16 TextureAtlasSprite UV coordinates for top and side faces.
     */
    public static SpriteUvInfo getSpriteUvInfo(int stateId) {
        int id = stateId & 0xFFFF;
        if (id <= 0) {
            return null;
        }
        if (id < 65536) {
            SpriteUvInfo cached = STATE_UV_ARRAY[id];
            if (cached != null) {
                return (cached == NULL_SPRITE) ? null : cached;
            }
            SpriteUvInfo computed = computeSpriteUvInfo(id);
            STATE_UV_ARRAY[id] = (computed != null) ? computed : NULL_SPRITE;
            return computed;
        } else {
            SpriteUvInfo cached = STATE_UV_MAP.get(id);
            if (cached != null) {
                return (cached == NULL_SPRITE) ? null : cached;
            }
            SpriteUvInfo computed = computeSpriteUvInfo(id);
            STATE_UV_MAP.put(id, (computed != null) ? computed : NULL_SPRITE);
            return computed;
        }
    }

    private static SpriteUvInfo computeSpriteUvInfo(int stateId) {
        try {
            IBlockState state = Block.BLOCK_STATE_IDS.getByValue(stateId & 0xFFFF);
            if (state == null) {
                return null;
            }

            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getBlockRendererDispatcher() == null) {
                return null;
            }

            IBakedModel model = mc.getBlockRendererDispatcher().getModelForState(state);
            if (model == null) {
                return null;
            }

            TextureAtlasSprite topSprite = null;
            try {
                List<BakedQuad> upQuads = model.getQuads(state, EnumFacing.UP, 0L);
                if (upQuads != null && !upQuads.isEmpty() && upQuads.get(0) != null && upQuads.get(0).getSprite() != null) {
                    topSprite = upQuads.get(0).getSprite();
                }
                if (topSprite == null || "missingno".equals(topSprite.getIconName())) {
                    List<BakedQuad> generalQuads = model.getQuads(state, null, 0L);
                    if (generalQuads != null && !generalQuads.isEmpty()) {
                        for (BakedQuad q : generalQuads) {
                            if (q != null && q.getSprite() != null && !"missingno".equals(q.getSprite().getIconName())) {
                                if (q.getFace() == EnumFacing.UP || topSprite == null) {
                                    topSprite = q.getSprite();
                                }
                            }
                        }
                    }
                }
                if (topSprite == null || "missingno".equals(topSprite.getIconName())) {
                    topSprite = model.getParticleTexture();
                }
            } catch (Throwable ignored) {}

            if (topSprite == null || "missingno".equals(topSprite.getIconName()) || topSprite.getFrameCount() <= 0) {
                return null;
            }

            TextureAtlasSprite sideSprite = null;
            boolean sideHasTint = false;
            try {
                List<BakedQuad> northQuads = model.getQuads(state, EnumFacing.NORTH, 0L);
                if (northQuads != null && !northQuads.isEmpty() && northQuads.get(0) != null && northQuads.get(0).getSprite() != null) {
                    sideSprite = northQuads.get(0).getSprite();
                    sideHasTint = northQuads.get(0).hasTintIndex();
                }
                if (sideSprite == null || "missingno".equals(sideSprite.getIconName())) {
                    List<BakedQuad> generalQuads = model.getQuads(state, null, 0L);
                    if (generalQuads != null && !generalQuads.isEmpty()) {
                        for (BakedQuad q : generalQuads) {
                            if (q != null && q.getSprite() != null && !"missingno".equals(q.getSprite().getIconName())) {
                                if (q.getFace() == EnumFacing.NORTH || sideSprite == null) {
                                    sideSprite = q.getSprite();
                                    sideHasTint = q.hasTintIndex();
                                }
                            }
                        }
                    }
                }
                if (sideSprite == null || "missingno".equals(sideSprite.getIconName())) {
                    sideSprite = model.getParticleTexture();
                }
            } catch (Throwable ignored) {}

            if (sideSprite == null || "missingno".equals(sideSprite.getIconName()) || sideSprite.getFrameCount() <= 0) {
                sideSprite = topSprite;
            }

            boolean isLeaves = ChunkSurfaceExtractor.isLeafBlock(state);
            boolean isGrass = (state.getBlock() == net.minecraft.init.Blocks.GRASS);

            if (isGrass) {
                try {
                    TextureAtlasSprite grassSide = mc.getTextureMapBlocks().getAtlasSprite("minecraft:blocks/grass_side");
                    if (grassSide != null && !"missingno".equals(grassSide.getIconName())) {
                        sideSprite = grassSide;
                    }
                } catch (Throwable ignored) {}
                sideHasTint = false;
            } else if (isLeaves) {
                sideHasTint = true;
            }

            boolean upNeedsBiomeTint = isGrass || isLeaves;
            boolean sideNeedsBiomeTint = sideHasTint;

            int averageRgb = getSpriteAverageRgb(state);

            return new SpriteUvInfo(
                    topSprite.getMinU(), topSprite.getMaxU(),
                    topSprite.getMinV(), topSprite.getMaxV(),
                    upNeedsBiomeTint,
                    sideSprite.getMinU(), sideSprite.getMaxU(),
                    sideSprite.getMinV(), sideSprite.getMaxV(),
                    sideNeedsBiomeTint,
                    1.45f, averageRgb, isLeaves, isGrass, true
            );
        } catch (Throwable t) {
            return null;
        }
    }

    public static void clear() {
        CACHE.clear();
        Arrays.fill(STATE_UV_ARRAY, null);
        STATE_UV_MAP.clear();
    }
}
