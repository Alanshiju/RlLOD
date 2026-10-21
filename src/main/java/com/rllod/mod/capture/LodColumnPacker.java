package com.rllod.mod.capture;

/**
 * Packs 1x1 column surface data into 64-bit longs:
 *
 * Base Layer (Ground / Water):
 * - Bits 0..7   (8 bits)  : Solid surface Y (0..255)
 * - Bits 8..15  (8 bits)  : Water top Y (0..255, 0 if dry)
 * - Bits 16..39 (24 bits) : 24-bit RGB surface color (0xRRGGBB)
 * - Bits 40..43 (4 bits)  : Sky light (0..15)
 * - Bits 44..47 (4 bits)  : Block light (0..15)
 * - Bits 48..55 (8 bits)  : Flags (bit 0=hasWater, bit 1=isFoliage, bit 2=isSnow, bit 3=hasCanopy)
 * - Bits 56..63 (8 bits)  : Biome ID (0..255)
 *
 * Upper Layer (Canopy / Roof / Overhang):
 * - Bits 0..7   (8 bits)  : Canopy Bottom Y (0..255)
 * - Bits 8..15  (8 bits)  : Canopy Top Y (0..255)
 * - Bits 16..39 (24 bits) : 24-bit RGB canopy color (0xRRGGBB)
 * - Bits 40..43 (4 bits)  : Sky light (0..15)
 * - Bits 44..47 (4 bits)  : Block light (0..15)
 * - Bits 48..55 (8 bits)  : Flags
 */
public final class LodColumnPacker {

    public static final int FLAG_HAS_WATER     = 1;
    public static final int FLAG_IS_FOLIAGE    = 1 << 1;
    public static final int FLAG_IS_SNOW       = 1 << 2;
    public static final int FLAG_HAS_CANOPY    = 1 << 3;
    public static final int FLAG_HAS_TALLGRASS = 1 << 4;
    public static final int FLAG_MAT_GRASS     = 1 << 5;
    public static final int FLAG_MAT_SAND      = 1 << 6;
    public static final int FLAG_MAT_STONE     = 1 << 7;

    private LodColumnPacker() {}

    public static long pack(
            int solidY,
            int waterY,
            int rgbColor,
            int skyLight,
            int blockLight,
            int flags,
            int biomeId
    ) {
        return ((long) (solidY & 0xFF))
                | (((long) (waterY & 0xFF)) << 8)
                | (((long) (rgbColor & 0xFFFFFF)) << 16)
                | (((long) (skyLight & 0x0F)) << 40)
                | (((long) (blockLight & 0x0F)) << 44)
                | (((long) (flags & 0xFF)) << 48)
                | (((long) (biomeId & 0xFF)) << 56);
    }

    public static long packCanopy(
            int bottomY,
            int topY,
            int rgbColor,
            int skyLight,
            int blockLight,
            int flags
    ) {
        return ((long) (bottomY & 0xFF))
                | (((long) (topY & 0xFF)) << 8)
                | (((long) (rgbColor & 0xFFFFFF)) << 16)
                | (((long) (skyLight & 0x0F)) << 40)
                | (((long) (blockLight & 0x0F)) << 44)
                | (((long) (flags & 0xFF)) << 48);
    }

    public static int unpackSolidY(long packed) {
        return (int) (packed & 0xFFL);
    }

    public static int unpackWaterY(long packed) {
        return (int) ((packed >>> 8) & 0xFFL);
    }

    public static int unpackRgb(long packed) {
        return (int) ((packed >>> 16) & 0xFFFFFFL);
    }

    public static int unpackSkyLight(long packed) {
        return (int) ((packed >>> 40) & 0x0FL);
    }

    public static int unpackBlockLight(long packed) {
        return (int) ((packed >>> 44) & 0x0FL);
    }

    public static int unpackFlags(long packed) {
        return (int) ((packed >>> 48) & 0xFFL);
    }

    public static int unpackBiomeId(long packed) {
        return (int) ((packed >>> 56) & 0xFFL);
    }

    public static int unpackCanopyBottomY(long packed) {
        return (int) (packed & 0xFFL);
    }

    public static int unpackCanopyTopY(long packed) {
        return (int) ((packed >>> 8) & 0xFFL);
    }

    public static int unpackCanopyRgb(long packed) {
        return (int) ((packed >>> 16) & 0xFFFFFFL);
    }

    public static int unpackCanopySkyLight(long packed) {
        return (int) ((packed >>> 40) & 0x0FL);
    }

    public static int unpackCanopyBlockLight(long packed) {
        return (int) ((packed >>> 44) & 0x0FL);
    }

    public static boolean hasCanopy(long packed) {
        return packed != 0L && ((packed >>> 8) & 0xFFL) > 0L;
    }
}
