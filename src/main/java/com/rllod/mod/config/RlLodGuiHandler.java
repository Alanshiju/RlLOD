package com.rllod.mod.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public final class RlLodGuiHandler {

    public static final RlLodGuiHandler INSTANCE = new RlLodGuiHandler();
    public static final int RLLOD_CONFIG_BUTTON_ID = 9912;

    private RlLodGuiHandler() {}

    @SubscribeEvent
    public void onInitGui(GuiScreenEvent.InitGuiEvent.Post event) {
        if (event.getGui() instanceof GuiOptions) {
            int x = event.getGui().width / 2 - 155;
            int y = event.getGui().height / 6 + 18;
            event.getButtonList().add(new GuiButton(RLLOD_CONFIG_BUTTON_ID, x, y, 150, 20, "§bLOD Settings..."));
        } else if (event.getGui() instanceof GuiVideoSettings) {
            int x = event.getGui().width / 2 + 5;
            int y = event.getGui().height / 6 + 18;
            event.getButtonList().add(new GuiButton(RLLOD_CONFIG_BUTTON_ID, x, y, 150, 20, "§bLOD Settings..."));
        }
    }

    @SubscribeEvent
    public void onActionPerformed(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (event.getButton() != null && event.getButton().id == RLLOD_CONFIG_BUTTON_ID) {
            Minecraft.getMinecraft().displayGuiScreen(new GuiRlLodConfig(event.getGui()));
            event.setCanceled(true);
        }
    }
}
