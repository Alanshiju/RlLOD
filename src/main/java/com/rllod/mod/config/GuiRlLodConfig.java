package com.rllod.mod.config;

import com.rllod.mod.render.LodRenderManager;
import com.rllod.mod.render.LodShaderBridge;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import java.io.IOException;

public final class GuiRlLodConfig extends GuiScreen {

    private final GuiScreen parentScreen;
    private static final int[] DISTANCE_OPTIONS = {32, 48, 64, 96, 128, 192, 256};

    private GuiButton btnDistance;
    private GuiButton btnQuality;
    private GuiButton btnShaderMode;
    private GuiButton btnLandmarks;
    private GuiButton btnAutoScan;
    private GuiButton btnAutoGenerate;
    private GuiButton btnAnimator;
    private GuiButton btnMakeUpShader;
    private GuiButton btnRebuild;

    public GuiRlLodConfig(GuiScreen parent) {
        this.parentScreen = parent;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();

        int centerX = this.width / 2;
        int startY = this.height / 6;

        btnDistance = new GuiButton(0, centerX - 155, startY, 150, 20, getDistanceString());
        btnQuality = new GuiButton(1, centerX + 5, startY, 150, 20, getQualityString());

        btnShaderMode = new GuiButton(2, centerX - 155, startY + 26, 150, 20, getShaderModeString());
        btnLandmarks = new GuiButton(3, centerX + 5, startY + 26, 150, 20, getLandmarksString());

        btnAutoScan = new GuiButton(4, centerX - 155, startY + 52, 150, 20, getAutoScanString());
        btnAutoGenerate = new GuiButton(5, centerX + 5, startY + 52, 150, 20, getAutoGenerateString());

        btnAnimator = new GuiButton(8, centerX - 155, startY + 78, 310, 20, getAnimatorString());
        btnMakeUpShader = new GuiButton(7, centerX - 155, startY + 102, 310, 20, getMakeUpShaderString());
        btnRebuild = new GuiButton(6, centerX - 155, startY + 126, 310, 20, "§eRebuild All Distant LODs Now");

        this.buttonList.add(btnDistance);
        this.buttonList.add(btnQuality);
        this.buttonList.add(btnShaderMode);
        this.buttonList.add(btnLandmarks);
        this.buttonList.add(btnAutoScan);
        this.buttonList.add(btnAutoGenerate);
        this.buttonList.add(btnAnimator);
        this.buttonList.add(btnMakeUpShader);
        this.buttonList.add(btnRebuild);

        this.buttonList.add(new GuiButton(200, centerX - 100, startY + 154, 200, 20, I18n.format("gui.done")));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 0) {
            // Cycle distance
            int cur = RlLodConfig.lodRenderDistanceChunks;
            int nextIdx = 0;
            for (int i = 0; i < DISTANCE_OPTIONS.length; i++) {
                if (DISTANCE_OPTIONS[i] == cur) {
                    nextIdx = (i + 1) % DISTANCE_OPTIONS.length;
                    break;
                }
            }
            RlLodConfig.lodRenderDistanceChunks = DISTANCE_OPTIONS[nextIdx];
            btnDistance.displayString = getDistanceString();
            RlLodConfig.sync();
        } else if (button.id == 1) {
            // Cycle quality: 0 (High) -> 1 (Balanced) -> 2 (Fast)
            RlLodConfig.lodDetailQuality = (RlLodConfig.lodDetailQuality + 1) % 3;
            btnQuality.displayString = getQualityString();
            RlLodConfig.sync();
        } else if (button.id == 2) {
            // Cycle shader mode: 0 (Auto) -> 1 (Built-In GLSL) -> 2 (Off)
            RlLodConfig.shaderMode = (RlLodConfig.shaderMode + 1) % 3;
            RlLodConfig.enableBuiltInShader = (RlLodConfig.shaderMode <= 1);
            btnShaderMode.displayString = getShaderModeString();
            RlLodConfig.sync();
        } else if (button.id == 3) {
            RlLodConfig.preserveRlcraftLandmarks = !RlLodConfig.preserveRlcraftLandmarks;
            btnLandmarks.displayString = getLandmarksString();
            RlLodConfig.sync();
        } else if (button.id == 4) {
            RlLodConfig.autoScanSavedWorldChunks = !RlLodConfig.autoScanSavedWorldChunks;
            btnAutoScan.displayString = getAutoScanString();
            RlLodConfig.sync();
        } else if (button.id == 5) {
            RlLodConfig.autoGenerateUnexploredChunks = !RlLodConfig.autoGenerateUnexploredChunks;
            btnAutoGenerate.displayString = getAutoGenerateString();
            RlLodConfig.sync();
        } else if (button.id == 6) {
            LodRenderManager.INSTANCE.clearAllGlResources();
            LodRenderManager.INSTANCE.markAllRegionsDirty();
            btnRebuild.displayString = "§a✓ All LOD Meshes Scheduled for Rebuild!";
        } else if (button.id == 7) {
            boolean active = LodShaderBridge.isMakeUpShaderActive();
            LodShaderBridge.toggleFullMakeUpShader(!active);
            btnMakeUpShader.displayString = getMakeUpShaderString();
            LodRenderManager.INSTANCE.clearAllGlResources();
            LodRenderManager.INSTANCE.markAllRegionsDirty();
            RlLodConfig.sync();
        } else if (button.id == 8) {
            RlLodConfig.enableChunkAnimatorEffect = !RlLodConfig.enableChunkAnimatorEffect;
            btnAnimator.displayString = getAnimatorString();
            RlLodConfig.sync();
        } else if (button.id == 200) {
            RlLodConfig.sync();
            this.mc.displayGuiScreen(this.parentScreen);
        }
    }

    private String getAnimatorString() {
        return "ChunkAnimator Rise: " + (RlLodConfig.enableChunkAnimatorEffect ? "§aON" : "§cOFF");
    }

    private String getMakeUpShaderString() {
        if (!LodShaderBridge.isOptifineAvailable()) {
            return "MakeUp-UltraFast Shaders: §7(OptiFine Not Detected)";
        }
        boolean active = LodShaderBridge.isMakeUpShaderActive();
        return "MakeUp-UltraFast Shaders: " + (active ? "§a[ON - Waves, Reflections & Shadows]" : "§c[OFF]");
    }

    private String getDistanceString() {
        return "LOD Distance: " + RlLodConfig.lodRenderDistanceChunks + " Chunks";
    }

    private String getQualityString() {
        switch (RlLodConfig.lodDetailQuality) {
            case 0:  return "Quality: §aHigh (1x1 to 24ch)";
            case 1:  return "Quality: §eBalanced (1x1 to 16ch)";
            case 2:
            default: return "Quality: §cFast Surface (2x2+)";
        }
    }

    private String getShaderModeString() {
        switch (RlLodConfig.shaderMode) {
            case 0:  return "Shader: §aAuto (OptiFine+GLSL)";
            case 1:  return "Shader: §eBuilt-In GLSL Only";
            case 2:
            default: return "Shader: §cOff (Vanilla)";
        }
    }

    private String getLandmarksString() {
        return "Landmarks: " + (RlLodConfig.preserveRlcraftLandmarks ? "§aON" : "§cOFF");
    }

    private String getAutoScanString() {
        return "Auto-Scan MCA: " + (RlLodConfig.autoScanSavedWorldChunks ? "§aON" : "§cOFF");
    }

    private String getAutoGenerateString() {
        return "Auto-Gen Surfaces: " + (RlLodConfig.autoGenerateUnexploredChunks ? "§aON" : "§cOFF");
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRenderer, "§b§lRLCraft Minimal LOD Settings", this.width / 2, 20, 0xFFFFFF);
        this.drawCenteredString(this.fontRenderer, "§7Extreme Distance Surface LOD Engine with OptiFine & Shaderpack Support", this.width / 2, 34, 0xAAAAAA);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
