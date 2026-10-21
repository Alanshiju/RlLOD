package com.rllod.mod;

import com.rllod.mod.capture.LodCacheManager;
import com.rllod.mod.render.LodRenderManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
    modid = RlLodMod.MODID,
    name = RlLodMod.NAME,
    version = RlLodMod.VERSION,
    clientSideOnly = true,
    acceptableRemoteVersions = "*"
)
public class RlLodMod {
    public static final String MODID = "rllod";
    public static final String NAME = "RLCraft Minimal LOD";
    public static final String VERSION = "3.3.0";

    public static final Logger LOGGER = LogManager.getLogger(MODID);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("[RlLOD] Initializing Minimal LOD Architecture for 1.12.2...");
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(LodCacheManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(LodRenderManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(com.rllod.mod.capture.DistantSurfaceGenerator.INSTANCE);
        MinecraftForge.EVENT_BUS.register(com.rllod.mod.config.RlLodGuiHandler.INSTANCE);
        LOGGER.info("[RlLOD] Client pipeline, LodCacheManager, LodRenderManager, DistantSurfaceGenerator & RlLodGuiHandler registered.");
    }
}


