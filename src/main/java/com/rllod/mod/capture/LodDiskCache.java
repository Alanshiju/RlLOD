package com.rllod.mod.capture;

import com.rllod.mod.RlLodMod;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

import java.io.*;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class LodDiskCache {

    private static final int CACHE_MAGIC = 0x524C4C44; // "RLLD"
    private static final int CACHE_VERSION = 23;

    private LodDiskCache() {}

    private static File getCacheDir() {
        Minecraft mc = Minecraft.getMinecraft();
        File worldDir;
        if (mc.isIntegratedServerRunning() && mc.getIntegratedServer() != null) {
            worldDir = mc.getIntegratedServer().getDataDirectory();
        } else {
            // Multiplayer or fallback cache directory
            worldDir = new File(mc.gameDir, "rllod_multiplayer_cache");
        }
        File rllodDir = new File(worldDir, "rllod_cache");
        if (!rllodDir.exists()) {
            rllodDir.mkdirs();
        }
        return rllodDir;
    }

    private static File getChunkFile(int chunkX, int chunkZ) {
        // Group chunks into 32x32 region files to prevent directory inode bloat
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        File regionDir = new File(getCacheDir(), "r_" + regionX + "_" + regionZ);
        if (!regionDir.exists()) {
            regionDir.mkdirs();
        }
        return new File(regionDir, "c_" + chunkX + "_" + chunkZ + ".dat");
    }

    public static synchronized void saveSnapshot(LodChunkSnapshot snapshot) {
        File file = getChunkFile(snapshot.chunkX, snapshot.chunkZ);
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(file))))) {
            dos.writeInt(CACHE_MAGIC);
            dos.writeInt(CACHE_VERSION);
            dos.writeInt(snapshot.chunkX);
            dos.writeInt(snapshot.chunkZ);
            dos.writeLong(snapshot.timestampMs);
            dos.writeInt(snapshot.columns.length);
            for (int i = 0; i < snapshot.columns.length; i++) {
                dos.writeLong(snapshot.columns[i]);
            }
            // Real 16x16 TextureAtlasSprite State IDs
            for (int i = 0; i < 256; i++) {
                dos.writeShort(snapshot.groundStateIds[i]);
            }
            for (int i = 0; i < 256; i++) {
                dos.writeShort(snapshot.canopyStateIds[i]);
            }
            // Multi-Span & Floating Island bottom Y
            dos.write(snapshot.solidBottomY);
            dos.writeBoolean(snapshot.hasComplexSkyStructure);
        } catch (Throwable t) {
            RlLodMod.LOGGER.debug("[RlLOD] Failed to save disk cache for chunk ({}, {})", snapshot.chunkX, snapshot.chunkZ, t);
        }
    }

    public static synchronized LodChunkSnapshot loadSnapshot(int chunkX, int chunkZ) {
        File file = getChunkFile(chunkX, chunkZ);
        if (!file.exists()) {
            return null;
        }
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            int magic = dis.readInt();
            if (magic != CACHE_MAGIC) {
                return null;
            }
            int version = dis.readInt();
            if (version != CACHE_VERSION) {
                return null;
            }
            int x = dis.readInt();
            int z = dis.readInt();
            long timestamp = dis.readLong();
            int colCount = dis.readInt();
            long[] columns = new long[colCount];
            for (int i = 0; i < colCount; i++) {
                columns[i] = dis.readLong();
            }
            short[] groundStateIds = new short[256];
            for (int i = 0; i < 256; i++) {
                groundStateIds[i] = dis.readShort();
            }
            short[] canopyStateIds = new short[256];
            for (int i = 0; i < 256; i++) {
                canopyStateIds[i] = dis.readShort();
            }
            byte[] solidBottomY = new byte[256];
            dis.readFully(solidBottomY);
            boolean hasComplexSky = dis.readBoolean();
            return new LodChunkSnapshot(x, z, columns, groundStateIds, canopyStateIds, solidBottomY, hasComplexSky);
        } catch (Throwable t) {
            RlLodMod.LOGGER.debug("[RlLOD] Failed to load disk cache for chunk ({}, {})", chunkX, chunkZ, t);
            return null;
        }
    }

    public static void preloadCachedSnapshots(Consumer<LodChunkSnapshot> onLoaded) {
        preloadCachedSnapshots(0, 0, onLoaded);
    }

    public static void preloadCachedSnapshots(int playerChunkX, int playerChunkZ, Consumer<LodChunkSnapshot> onLoaded) {
        File cacheDir = getCacheDir();
        if (!cacheDir.exists() || !cacheDir.isDirectory()) {
            return;
        }
        File[] regionDirs = cacheDir.listFiles(File::isDirectory);
        if (regionDirs == null) {
            return;
        }

        java.util.List<File> allChunkFiles = new java.util.ArrayList<>();
        for (File regionDir : regionDirs) {
            if (!regionDir.getName().startsWith("r_")) {
                continue;
            }
            File[] chunkFiles = regionDir.listFiles((dir, name) -> name.startsWith("c_") && name.endsWith(".dat"));
            if (chunkFiles != null) {
                java.util.Collections.addAll(allChunkFiles, chunkFiles);
            }
        }

        if (allChunkFiles.isEmpty()) {
            return;
        }

        // Sort closest to player first (ascending distance squared)
        allChunkFiles.sort((f1, f2) -> {
            int d1 = getChunkDistSq(f1.getName(), playerChunkX, playerChunkZ);
            int d2 = getChunkDistSq(f2.getName(), playerChunkX, playerChunkZ);
            return Integer.compare(d1, d2);
        });

        for (File file : allChunkFiles) {
            try (DataInputStream dis = new DataInputStream(
                    new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
                int magic = dis.readInt();
                int version = dis.readInt();
                if (magic != CACHE_MAGIC || version != CACHE_VERSION) {
                    continue;
                }
                int x = dis.readInt();
                int z = dis.readInt();
                long timestamp = dis.readLong();
                int colCount = dis.readInt();
                long[] columns = new long[colCount];
                for (int i = 0; i < colCount; i++) {
                    columns[i] = dis.readLong();
                }
                short[] groundStateIds = new short[256];
                for (int i = 0; i < 256; i++) {
                    groundStateIds[i] = dis.readShort();
                }
                short[] canopyStateIds = new short[256];
                for (int i = 0; i < 256; i++) {
                    canopyStateIds[i] = dis.readShort();
                }
                byte[] solidBottomY = new byte[256];
                dis.readFully(solidBottomY);
                onLoaded.accept(new LodChunkSnapshot(x, z, columns, groundStateIds, canopyStateIds, solidBottomY));
            } catch (Throwable ignored) {
            }
        }
    }

    private static int getChunkDistSq(String name, int playerX, int playerZ) {
        // Filename format: c_<chunkX>_<chunkZ>.dat
        try {
            int firstUnderscore = name.indexOf('_');
            int secondUnderscore = name.indexOf('_', firstUnderscore + 1);
            int dot = name.indexOf('.', secondUnderscore);
            if (firstUnderscore >= 0 && secondUnderscore > firstUnderscore && dot > secondUnderscore) {
                int cx = Integer.parseInt(name.substring(firstUnderscore + 1, secondUnderscore));
                int cz = Integer.parseInt(name.substring(secondUnderscore + 1, dot));
                int dx = cx - playerX;
                int dz = cz - playerZ;
                return dx * dx + dz * dz;
            }
        } catch (Throwable ignored) {
        }
        return Integer.MAX_VALUE;
    }
}
