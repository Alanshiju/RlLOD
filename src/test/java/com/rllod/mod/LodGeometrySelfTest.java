package com.rllod.mod;

import com.rllod.mod.capture.LodChunkSnapshot;
import com.rllod.mod.capture.LodColumnPacker;
import com.rllod.mod.capture.LodSpanClassifier;
import com.rllod.mod.meshing.LodMeshBuilder;
import com.rllod.mod.meshing.LodMeshData;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.Assert.*;

public class LodGeometrySelfTest {

    @BeforeClass
    public static void setUp() {
        if (!Bootstrap.isRegistered()) {
            Bootstrap.register();
        }
    }

    /**
     * Test 1: Floating Tree Shrine across chunk boundary has NO 70-block drop pillar.
     * Simulates a column with desert sand at Y=64, air at Y=65..133 (69 blocks),
     * stone platform at Y=134..137, air at Y=138..145 (8 blocks), and leaves at Y=146..160.
     * Verifies that the span classifier outputs:
     * solidY == 64, canopyBottomY == 134, canopyTopY == 160, and canopyRgb is green foliage (not brown/grey).
     */
    @Test
    public void testFloatingIslandAcrossChunkBoundaryHasNoPillar() {
        IBlockState[] column = new IBlockState[256];
        Arrays.fill(column, 0, 64, Blocks.STONE.getDefaultState());
        column[64] = Blocks.SAND.getDefaultState();
        for (int y = 65; y <= 133; y++) {
            column[y] = Blocks.AIR.getDefaultState();
        }
        for (int y = 134; y <= 137; y++) {
            column[y] = Blocks.STONE.getDefaultState();
        }
        for (int y = 138; y <= 145; y++) {
            column[y] = Blocks.AIR.getDefaultState();
        }
        for (int y = 146; y <= 160; y++) {
            column[y] = Blocks.LEAVES.getDefaultState();
        }

        int foliageGreen = 0x48B518;
        LodSpanClassifier.ColumnResult res = LodSpanClassifier.classify(column, foliageGreen);

        assertEquals("Ground solidY should be the desert floor at 64, not the floating island platform", 64, res.solidY);
        assertEquals("Canopy bottom Y should be the bottom of the floating stone platform at 134", 134, res.canopyBottomY);
        assertEquals("Canopy top Y should be the top of the tree foliage crown at 160", 160, res.canopyTopY);

        int r = (res.canopyRgb >> 16) & 0xFF;
        int g = (res.canopyRgb >> 8) & 0xFF;
        int b = res.canopyRgb & 0xFF;

        assertTrue("Canopy RGB must be green foliage (G > R): R=" + r + " G=" + g + " B=" + b, g > r);
        assertTrue("Canopy RGB must be green foliage (G > B): R=" + r + " G=" + g + " B=" + b, g > b);
        assertNotEquals("Canopy RGB must not be grey or neutral", r, g);
    }

    /**
     * Test 2: BattleTower with internal floor gaps is fused into a single solid tower.
     * Simulates a BattleTower column with stone at Y=64, air rooms of 6 blocks
     * (Y=65..70, 72..77, 79..84, 86..91, 93..98, 100..105), and cobblestone floors
     * at Y=71, 78, 85, 92, 99, 106..115.
     * Verifies that because all internal air gaps are < 12 blocks, the classifier fuses them into
     * solidY == 115 with canopyTopY == 0.
     */
    @Test
    public void testBattleTowerWithInternalFloorsIsSolid() {
        IBlockState[] column = new IBlockState[256];
        Arrays.fill(column, 0, 65, Blocks.STONE.getDefaultState());

        int[] airRoomStarts = {65, 72, 79, 86, 93, 100};
        for (int start : airRoomStarts) {
            for (int y = start; y < start + 6; y++) {
                column[y] = Blocks.AIR.getDefaultState();
            }
        }

        column[71] = Blocks.COBBLESTONE.getDefaultState();
        column[78] = Blocks.COBBLESTONE.getDefaultState();
        column[85] = Blocks.COBBLESTONE.getDefaultState();
        column[92] = Blocks.COBBLESTONE.getDefaultState();
        column[99] = Blocks.COBBLESTONE.getDefaultState();
        for (int y = 106; y <= 115; y++) {
            column[y] = Blocks.COBBLESTONE.getDefaultState();
        }

        LodSpanClassifier.ColumnResult res = LodSpanClassifier.classify(column, 0x48B518);

        assertEquals("BattleTower must be fused to the highest roof level at Y=115", 115, res.solidY);
        assertEquals("BattleTower should not create a separate canopy layer when fused", 0, res.canopyTopY);
    }

    /**
     * Test 3: 4x4 Region mesh has ZERO holes and NO grey skirts on grass cells.
     * Builds a 4x4 LodMeshData at LOD 0, LOD 1, and LOD 2 even when 4 of the 16 chunks4x4 slots are null
     * (testing the fallback filler), and asserts that subChunkSolidCount[i] > 0 for all i = 0..15
     * and that grass skirt vertices are tinted green (G > R && G > B), never grey (R == G == B).
     */
    @Test
    public void testRegionMeshHasZeroHolesAndNoGreyGrassSkirts() {
        int grassGreen = 0x5B8C32; // Vibrant biome grass green

        int[] lodLevelsToTest = {0, 1, 2};
        for (int lodLevel : lodLevelsToTest) {
            LodChunkSnapshot[] chunks4x4 = new LodChunkSnapshot[16];

            for (int i = 0; i < 16; i++) {
                // Intentionally leave 4 slots null to test zero-hole fallback synthesis
                if (i == 2 || i == 5 || i == 10 || i == 14) {
                    chunks4x4[i] = null;
                    continue;
                }

                int cx = i & 3;
                int cz = i >> 2;
                long[] cols = new long[512];
                short[] gIds = new short[256];
                short[] cIds = new short[256];
                byte[] botY = new byte[256];

                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int colIdx = (lz << 4) | lx;
                        // Stepped hillside to trigger vertical skirts
                        int solidY = 64 + lx + lz;
                        int flags = LodColumnPacker.FLAG_MAT_GRASS;
                        cols[colIdx] = LodColumnPacker.pack(solidY, 0, grassGreen, 15, 0, flags, 1);
                        botY[colIdx] = 1;
                    }
                }

                chunks4x4[i] = new LodChunkSnapshot(cx, cz, cols, gIds, cIds, botY, false);
            }

            LodMeshData mesh = LodMeshBuilder.buildRegionMesh(0, 0, lodLevel, chunks4x4);
            assertNotNull("Mesh should not be null at LOD " + lodLevel, mesh);
            assertTrue("Mesh must have positive vertex count at LOD " + lodLevel, mesh.solidVertexCount > 0);

            // Assert that every single one of the 16 sub-chunks has solid vertices (0 holes!)
            for (int i = 0; i < 16; i++) {
                assertTrue("SubChunk " + i + " must have solid vertices at LOD " + lodLevel,
                        mesh.subChunkSolidCount[i] > 0);
            }

            // Inspect emitted skirt vertices in the buffer (vertical faces where normal ny == 0)
            ByteBuffer buf = mesh.vertexBuffer;
            assertNotNull("Mesh vertexBuffer must not be null", buf);

            int skirtVertexCount = 0;
            int stride = LodMeshData.STRIDE_BYTES; // 32 bytes
            for (int offset = 0; offset < mesh.solidVertexCount * stride; offset += stride) {
                byte ny = buf.get(offset + 29); // Normal Y
                if (ny == 0) { // Vertical skirt face
                    int r = buf.get(offset + 20) & 0xFF;
                    int g = buf.get(offset + 21) & 0xFF;
                    int b = buf.get(offset + 22) & 0xFF;

                    skirtVertexCount++;
                    assertTrue("Grass skirt vertex must be tinted green (G > R): R=" + r + " G=" + g + " B=" + b + " at LOD " + lodLevel, g > r);
                    assertTrue("Grass skirt vertex must be tinted green (G > B): R=" + r + " G=" + g + " B=" + b + " at LOD " + lodLevel, g > b);
                    assertFalse("Grass skirt vertex must NEVER be grey (R == G == B): R=" + r + " G=" + g + " B=" + b + " at LOD " + lodLevel,
                            r == g && g == b);
                }
            }

            assertTrue("Expected vertical skirt vertices to be emitted at LOD " + lodLevel, skirtVertexCount > 0);
        }
    }
}
