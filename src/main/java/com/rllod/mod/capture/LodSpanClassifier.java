package com.rllod.mod.capture;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Universal physical vertical span merger and classifier for columns.
 * Evaluates each column independently on its physical vertical spans without fragile chunk-wide thresholds.
 */
public final class LodSpanClassifier {

    @FunctionalInterface
    public interface BlockColumnProvider {
        IBlockState getBlock(int y);
    }

    public static final class ColumnSpan {
        public int bottomY;
        public int topY;
        public IBlockState topState;
        public boolean isLeaf;
        public boolean hasNonLeaf;

        public ColumnSpan(int bottomY, int topY, IBlockState topState, boolean isLeaf, boolean hasNonLeaf) {
            this.bottomY = bottomY;
            this.topY = topY;
            this.topState = topState;
            this.isLeaf = isLeaf;
            this.hasNonLeaf = hasNonLeaf;
        }
    }

    public static final class ColumnResult {
        public int solidY;
        public int solidBottomY;
        public IBlockState groundState;
        public int waterY;
        public int canopyTopY;
        public int canopyBottomY;
        public IBlockState canopyState;
        public int canopyRgb;
        public int canopyFlags;
    }

    private LodSpanClassifier() {}

    public static boolean isLeafState(IBlockState s) {
        if (s == null) return false;
        Material m = s.getMaterial();
        if (m == Material.LEAVES || s.getBlock() instanceof BlockLeaves || m == Material.VINE) {
            return true;
        }
        ResourceLocation reg = (s.getBlock() != null) ? s.getBlock().getRegistryName() : null;
        if (reg != null) {
            String name = reg.toString().toLowerCase();
            if (name.contains("leaves") || name.contains("branch") || name.contains("twigs")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isSolidState(IBlockState s) {
        if (s == null) return false;
        Material m = s.getMaterial();
        return m.isSolid() || m.blocksMovement() || m == Material.ROCK || m == Material.GROUND
                || m == Material.GRASS || m == Material.SAND || m == Material.SNOW || m == Material.CRAFTED_SNOW
                || m == Material.ICE || m == Material.WOOD || m == Material.IRON || m == Material.CLAY;
    }

    public static ColumnResult classify(
            BlockColumnProvider provider,
            int startY,
            int defaultGroundY,
            IBlockState defaultGroundState,
            int foliageColor
    ) {
        List<ColumnSpan> rawSpans = new ArrayList<>();
        int curTopY = -1;
        int curBotY = -1;
        IBlockState curTopState = null;
        boolean curHasLeaf = false;
        boolean curHasNonLeaf = false;
        int lowerWaterY = 0;

        for (int y = startY; y >= 1; y--) {
            IBlockState s = provider.getBlock(y);
            if (s == null) continue;
            Material m = s.getMaterial();
            boolean isAir = (m == Material.AIR || s.getBlock() == Blocks.AIR || ChunkSurfaceExtractor.isIgnoredClutter(s));

            if (isAir) {
                if (curTopY != -1) {
                    rawSpans.add(new ColumnSpan(curBotY, curTopY, curTopState, curHasLeaf && !curHasNonLeaf, curHasNonLeaf));
                    curTopY = -1;
                    curBotY = -1;
                    curTopState = null;
                    curHasLeaf = false;
                    curHasNonLeaf = false;
                }
                continue;
            }

            if (m == Material.WATER || s.getBlock() == Blocks.WATER || s.getBlock() == Blocks.FLOWING_WATER) {
                if (curTopY != -1) {
                    rawSpans.add(new ColumnSpan(curBotY, curTopY, curTopState, curHasLeaf && !curHasNonLeaf, curHasNonLeaf));
                    curTopY = -1;
                    curBotY = -1;
                    curTopState = null;
                    curHasLeaf = false;
                    curHasNonLeaf = false;
                }
                if (y <= 85 && lowerWaterY == 0) {
                    lowerWaterY = y;
                }
                continue;
            }

            boolean leaf = isLeafState(s);
            boolean solid = isSolidState(s);

            if (leaf || solid) {
                if (curTopY == -1) {
                    curTopY = y;
                    curTopState = s;
                }
                curBotY = y;
                if (leaf) curHasLeaf = true;
                if (solid && !leaf) curHasNonLeaf = true;
            }
        }

        if (curTopY != -1) {
            rawSpans.add(new ColumnSpan(curBotY, curTopY, curTopState, curHasLeaf && !curHasNonLeaf, curHasNonLeaf));
        }

        // Reverse raw spans to bottom-to-top order
        Collections.reverse(rawSpans);

        // Merge contiguous / nearby spans:
        // Rule 1: Merge any spans separated by < 4 blocks of air.
        // Rule 2: Merge non-leaf solid spans separated by < 12 blocks of air (BattleTower rooms & floors!).
        List<ColumnSpan> merged = new ArrayList<>();
        for (ColumnSpan span : rawSpans) {
            if (merged.isEmpty()) {
                merged.add(span);
            } else {
                ColumnSpan prev = merged.get(merged.size() - 1);
                int gap = span.bottomY - prev.topY - 1;
                boolean shouldMerge = false;
                if (gap < 4) {
                    shouldMerge = true;
                } else if (gap < 12 && prev.hasNonLeaf && span.hasNonLeaf) {
                    shouldMerge = true;
                }

                if (shouldMerge) {
                    prev.topY = span.topY;
                    prev.topState = span.topState;
                    prev.hasNonLeaf |= span.hasNonLeaf;
                    prev.isLeaf = (prev.isLeaf && span.isLeaf);
                } else {
                    merged.add(span);
                }
            }
        }

        ColumnResult res = new ColumnResult();
        if (merged.isEmpty()) {
            res.solidY = (lowerWaterY > 0) ? Math.max(1, lowerWaterY - 3) : defaultGroundY;
            res.solidBottomY = Math.max(1, res.solidY - 4);
            res.groundState = defaultGroundState;
            res.waterY = lowerWaterY;
            return res;
        }

        ColumnSpan groundSpan = merged.get(0);
        res.solidY = groundSpan.topY;
        res.solidBottomY = Math.max(1, groundSpan.bottomY);
        res.groundState = groundSpan.topState;

        if (lowerWaterY > res.solidY) {
            res.waterY = lowerWaterY;
        }

        if (merged.size() > 1) {
            ColumnSpan firstUpper = merged.get(1);
            ColumnSpan lastUpper = merged.get(merged.size() - 1);

            res.canopyBottomY = firstUpper.bottomY;
            res.canopyTopY = lastUpper.topY;
            res.canopyState = lastUpper.topState;

            boolean hasUpperLeaves = false;
            IBlockState highestLeafState = null;
            for (int i = 1; i < merged.size(); i++) {
                ColumnSpan us = merged.get(i);
                if (us.isLeaf || isLeafState(us.topState)) {
                    hasUpperLeaves = true;
                    highestLeafState = us.topState;
                }
            }

            if (hasUpperLeaves) {
                if (highestLeafState != null) {
                    res.canopyState = highestLeafState;
                }
                res.canopyFlags |= LodColumnPacker.FLAG_IS_FOLIAGE;
                res.canopyRgb = (foliageColor != 0) ? foliageColor : 0x48B518;
            } else {
                res.canopyRgb = 0x8A7355;
            }
        }

        return res;
    }

    public static ColumnResult classify(
            IBlockState[] blocksByY,
            int foliageColor
    ) {
        int startY = 255;
        while (startY > 1 && blocksByY[startY] == null) {
            startY--;
        }
        return classify(y -> (y >= 0 && y < blocksByY.length) ? blocksByY[y] : null, startY, 64, Blocks.GRASS.getDefaultState(), foliageColor);
    }
}
