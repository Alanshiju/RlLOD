package com.rllod.mod.config;

import com.rllod.mod.RlLodMod;
import com.rllod.mod.render.LodRenderManager;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Config(modid = RlLodMod.MODID, name = "rllod")
@Mod.EventBusSubscriber(modid = RlLodMod.MODID)
public final class RlLodConfig {

    @Config.Name("LOD Render Distance (Chunks)")
    @Config.Comment("Maximum radius in chunks for rendering distant LOD terrain (16 to 256).")
    @Config.RangeInt(min = 16, max = 256)
    public static int lodRenderDistanceChunks = 64;

    @Config.Name("LOD Detail Quality")
    @Config.Comment("0 = High (1x1 to 24ch + Overhangs), 1 = Balanced (1x1 to 16ch), 2 = Fast Surface (2x2+).")
    @Config.RangeInt(min = 0, max = 2)
    public static int lodDetailQuality = 0;

    @Config.Name("Shader Mode")
    @Config.Comment("0 = Auto (OptiFine + Built-In GLSL), 1 = Built-In GLSL Only, 2 = Off (Vanilla).")
    @Config.RangeInt(min = 0, max = 2)
    public static int shaderMode = 0;

    @Config.Name("Enable Built-In Atmospheric Shader")
    @Config.Comment("Enables RlLOD's custom MakeUp-inspired GLSL atmospheric & horizon shader when OptiFine shaders are off.")
    public static boolean enableBuiltInShader = true;

    @Config.Name("Override OptiFine Shader Far Fog")
    @Config.Comment("Automatically pushes uniform_far in OptiFine shaders (e.g. MakeUp-UltraFast) to match LOD render distance so shaders don't fog out at vanilla render distance.")
    public static boolean overrideOptifineShaderFarPlane = true;

    @Config.Name("Preserve RLCraft Landmarks")
    @Config.Comment("Preserves BattleTowers, Shivaxi Monuments, Waystones, and tall structures during coarse LOD downsampling.")
    public static boolean preserveRlcraftLandmarks = true;

    @Config.Name("Auto-Scan Singleplayer Saved Chunks")
    @Config.Comment("Automatically reads already-generated chunks from the integrated server/disk in expanding rings out to LOD Render Distance.")
    public static boolean autoScanSavedWorldChunks = true;

    @Config.Name("Auto-Generate Unexplored Distant Surfaces")
    @Config.Comment("Generates distant surface heightmaps for unexplored chunks in Singleplayer when server tick time is healthy.")
    public static boolean autoGenerateUnexploredChunks = true;

    @Config.Name("Enable ChunkAnimator Rising Effect")
    @Config.Comment("Smoothly animates distant LOD chunks rising from below ground on first appearance, matching RLCraft's ChunkAnimator mod.")
    public static boolean enableChunkAnimatorEffect = true;

    @SubscribeEvent
    public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (RlLodMod.MODID.equals(event.getModID())) {
            sync();
        }
    }

    public static void sync() {
        ConfigManager.sync(RlLodMod.MODID, Config.Type.INSTANCE);
        LodRenderManager.INSTANCE.markAllRegionsDirty();
    }
}
