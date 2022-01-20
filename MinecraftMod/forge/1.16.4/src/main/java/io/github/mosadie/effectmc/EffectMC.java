package io.github.mosadie.effectmc;

import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.text2speech.Narrator;
import io.github.mosadie.effectmc.core.EffectExecutor;
import io.github.mosadie.effectmc.core.EffectMCCore;
import io.github.mosadie.effectmc.core.handler.DisconnectHandler;
import io.github.mosadie.effectmc.core.handler.SkinLayerHandler;
import net.minecraft.client.GameSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.client.gui.screen.*;
import net.minecraft.client.gui.toasts.SystemToast;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.entity.player.PlayerModelPart;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.WritableBookItem;
import net.minecraft.item.WrittenBookItem;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.util.JSONUtils;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;

import static net.minecraft.client.gui.screen.ReadBookScreen.*;

@Mod(EffectMC.MODID)
public class EffectMC implements EffectExecutor {
    public final static String MODID = "effectmc";

    private final EffectMCCore core;

    public static Logger LOGGER = LogManager.getLogger();

    private static Narrator narrator = Narrator.getNarrator();

    public EffectMC() throws IOException {
        File configDir = ModList.get().getModFileById(MODID).getFile().getFilePath().resolve("../" + MODID + "/").toFile();
        if (!configDir.exists()) {
            if (!configDir.mkdirs()) {
                LOGGER.error("Something went wrong creating the config directory!");
                throw new IOException("Failed to create config directory!");
            }
        }
        File trustFile = configDir.toPath().resolve("trust.json").toFile();
        File configFile = configDir.toPath().resolve("config.json").toFile();



        LOGGER.info("Starting Core");
        core = new EffectMCCore(configFile, trustFile,this);
        LOGGER.info("Core Started");

        LOGGER.info("Starting Server");
        boolean result = core.initServer();
        LOGGER.info("Server start result: " + result);

        MinecraftForge.EVENT_BUS.addListener(this::onChat);
    }

    @SubscribeEvent
    public void onChat(ClientChatEvent event) {
        if (event.getMessage().equalsIgnoreCase("/effectmctrust")) {
            Minecraft.getInstance().enqueue(core::setTrustNextRequest);
            receiveChatMessage("[EffectMC] Now prompting to trust the next request sent.");
            event.setCanceled(true);
        } else if (event.getMessage().equalsIgnoreCase("/effectmcexportbook")) {
            event.setCanceled(true);
            Minecraft.getInstance().enqueue(() -> {
                if (Minecraft.getInstance().player == null) {
                    return;
                }

                ItemStack mainHand = Minecraft.getInstance().player.getHeldItemMainhand();
                ItemStack offHand = Minecraft.getInstance().player.getHeldItemOffhand();

                ItemStack bookStack = null;
                if (mainHand.getItem().equals(Items.WRITTEN_BOOK)) {
                    bookStack = mainHand;
                } else if (offHand.getItem().equals(Items.WRITTEN_BOOK)) {
                    bookStack = offHand;
                }

                if (bookStack == null) {
                    receiveChatMessage("[EffectMC] Failed to export book: Not holding a book!");
                    return;
                }

                LOGGER.info("Exported Book JSON: " + bookStack.getTag().toString());
                receiveChatMessage("[EffectMC] Exported the held book to the current log file.");
            });
        }
    }

    @Override
    public void log(String message) {
        LOGGER.info(message);
    }

    @Override
    public void joinServer(String serverIp) {
        Minecraft.getInstance().enqueue(() -> {
            if (Minecraft.getInstance().world != null) {
                LOGGER.info("Disconnecting from world...");

                Minecraft.getInstance().world.sendQuittingDisconnectingPacket();
                Minecraft.getInstance().unloadWorld();
            }

            // Create ServerData
            ServerData server = new ServerData("EffectMC", serverIp, false);


            LOGGER.info("Connecting to " + server.serverIP);
            // Connect to server

            ConnectingScreen connectingScreen = new ConnectingScreen(new MainMenuScreen(), Minecraft.getInstance(), server);
            Minecraft.getInstance().displayGuiScreen(connectingScreen);
        });
    }

    @Override
    public void setSkinLayer(SkinLayerHandler.SKIN_SECTION section, boolean visibility) {
        GameSettings gameSettings = Minecraft.getInstance().gameSettings;

        switch (section) {

            case ALL:
                gameSettings.setModelPartEnabled(PlayerModelPart.CAPE, visibility);
                // Fall to ALL_BODY
            case ALL_BODY:
                gameSettings.setModelPartEnabled(PlayerModelPart.HAT, visibility);
                gameSettings.setModelPartEnabled(PlayerModelPart.JACKET, visibility);
                gameSettings.setModelPartEnabled(PlayerModelPart.LEFT_SLEEVE, visibility);
                gameSettings.setModelPartEnabled(PlayerModelPart.LEFT_PANTS_LEG, visibility);
                gameSettings.setModelPartEnabled(PlayerModelPart.RIGHT_SLEEVE, visibility);
                gameSettings.setModelPartEnabled(PlayerModelPart.RIGHT_PANTS_LEG, visibility);
                break;
            case CAPE:
                gameSettings.setModelPartEnabled(PlayerModelPart.CAPE, visibility);
                break;
            case JACKET:
                gameSettings.setModelPartEnabled(PlayerModelPart.JACKET, visibility);
                break;
            case LEFT_SLEEVE:
                gameSettings.setModelPartEnabled(PlayerModelPart.LEFT_SLEEVE, visibility);
                break;
            case RIGHT_SLEEVE:
                gameSettings.setModelPartEnabled(PlayerModelPart.RIGHT_SLEEVE, visibility);
                break;
            case LEFT_PANTS_LEG:
                gameSettings.setModelPartEnabled(PlayerModelPart.LEFT_PANTS_LEG, visibility);
                break;
            case RIGHT_PANTS_LEG:
                gameSettings.setModelPartEnabled(PlayerModelPart.RIGHT_PANTS_LEG, visibility);
                break;
            case HAT:
                gameSettings.setModelPartEnabled(PlayerModelPart.HAT, visibility);
                break;
        }
    }

    @Override
    public void toggleSkinLayer(SkinLayerHandler.SKIN_SECTION section) {
        GameSettings gameSettings = Minecraft.getInstance().gameSettings;
        switch (section) {

            case ALL:
                gameSettings.switchModelPartEnabled(PlayerModelPart.CAPE);
                // Fall to ALL_BODY
            case ALL_BODY:
                gameSettings.switchModelPartEnabled(PlayerModelPart.HAT);
                gameSettings.switchModelPartEnabled(PlayerModelPart.JACKET);
                gameSettings.switchModelPartEnabled(PlayerModelPart.LEFT_SLEEVE);
                gameSettings.switchModelPartEnabled(PlayerModelPart.LEFT_PANTS_LEG);
                gameSettings.switchModelPartEnabled(PlayerModelPart.RIGHT_SLEEVE);
                gameSettings.switchModelPartEnabled(PlayerModelPart.RIGHT_PANTS_LEG);
                break;
            case CAPE:
                gameSettings.switchModelPartEnabled(PlayerModelPart.CAPE);
                break;
            case JACKET:
                gameSettings.switchModelPartEnabled(PlayerModelPart.JACKET);
                break;
            case LEFT_SLEEVE:
                gameSettings.switchModelPartEnabled(PlayerModelPart.LEFT_SLEEVE);
                break;
            case RIGHT_SLEEVE:
                gameSettings.switchModelPartEnabled(PlayerModelPart.RIGHT_SLEEVE);
                break;
            case LEFT_PANTS_LEG:
                gameSettings.switchModelPartEnabled(PlayerModelPart.LEFT_PANTS_LEG);
                break;
            case RIGHT_PANTS_LEG:
                gameSettings.switchModelPartEnabled(PlayerModelPart.RIGHT_PANTS_LEG);
                break;
            case HAT:
                gameSettings.switchModelPartEnabled(PlayerModelPart.HAT);
                break;
        }
    }

    @Override
    public void sendChatMessage(String message) {
        if (Minecraft.getInstance().player != null) {
            LOGGER.info("Sending chat message: " + message);
            Minecraft.getInstance().player.sendChatMessage(message);
        }
    }

    @Override
    public void receiveChatMessage(String message) {
        if (Minecraft.getInstance().player != null) {
            LOGGER.info("Showing chat message: " + message);
            Minecraft.getInstance().player.sendMessage(new StringTextComponent(message), Minecraft.getInstance().player.getUniqueID());
        }
    }

    @Override
    public void showTitle(String title, String subtitle) {
        LOGGER.info("Showing Title: " + title + " Subtitle: " + subtitle);
        Minecraft.getInstance().ingameGUI.setDefaultTitlesTimes();
        Minecraft.getInstance().ingameGUI.func_238452_a_(null, new StringTextComponent(subtitle), -1, -1, -1);
        Minecraft.getInstance().ingameGUI.func_238452_a_(new StringTextComponent(title), null, -1, -1, -1);
    }

    @Override
    public void showActionMessage(String message) {
        LOGGER.info("Showing ActionBar message: " + message);
        Minecraft.getInstance().ingameGUI.setOverlayMessage(new StringTextComponent(message), false);
    }

    @Override
    public void triggerDisconnect(DisconnectHandler.NEXT_SCREEN nextScreenType, String title, String message) {
        Minecraft.getInstance().enqueue(() -> {
            if (Minecraft.getInstance().world != null) {
                LOGGER.info("Disconnecting from world...");

                Minecraft.getInstance().world.sendQuittingDisconnectingPacket();
                Minecraft.getInstance().unloadWorld();
            }

            Screen nextScreen;

            switch (nextScreenType) {
                case MAIN_MENU:
                    nextScreen = new MainMenuScreen();
                    break;

                case SERVER_SELECT:
                    nextScreen = new MultiplayerScreen(new MainMenuScreen());
                    break;

                case WORLD_SELECT:
                    nextScreen = new WorldSelectionScreen(new MainMenuScreen());
                    break;

                default:
                    nextScreen = new MainMenuScreen();
            }

            DisconnectedScreen screen = new DisconnectedScreen(nextScreen, new StringTextComponent(title), new StringTextComponent(message));
            Minecraft.getInstance().displayGuiScreen(screen);
        });
    }

    @Override
    public void playSound(String soundID, String categoryName, float volume, float pitch, boolean repeat, int repeatDelay, String attenuationType, double x, double y, double z, boolean relative, boolean global) {
        Minecraft.getInstance().enqueue(() -> {
            ResourceLocation sound = new ResourceLocation(soundID);

            SoundCategory category;
            try {
                category = SoundCategory.valueOf(categoryName.toUpperCase());
            } catch (IllegalArgumentException e) {
                category = SoundCategory.MASTER;
            }

            ISound.AttenuationType attenuation;
            try {
                attenuation = ISound.AttenuationType.valueOf(attenuationType.toUpperCase());
            } catch (IllegalArgumentException e) {
                attenuation = ISound.AttenuationType.NONE;
            }

            double trueX = x;
            double trueY = y;
            double trueZ = z;

            if (relative && Minecraft.getInstance().world != null) {
                trueX += Minecraft.getInstance().player.getPosX();
                trueY += Minecraft.getInstance().player.getPosY();
                trueZ += Minecraft.getInstance().player.getPosZ();
            }

            Minecraft.getInstance().getSoundHandler().play(new SimpleSound(sound, category, volume, pitch, repeat, repeatDelay, attenuation, trueX, trueY, trueZ, global));
        });
    }

    @Override
    public void showTrustPrompt(String device) {
        Minecraft.getInstance().enqueue(() -> {
            ConfirmScreen screen = new ConfirmScreen(new EffectMCCore.TrustBooleanConsumer(device, core), new StringTextComponent("EffectMC - Trust Prompt"), new StringTextComponent("Do you want to trust this device? (" + device + ")"));
            Minecraft.getInstance().displayGuiScreen(screen);
        });
    }

    @Override
    public void resetScreen() {
        Minecraft.getInstance().enqueue(() -> Minecraft.getInstance().displayGuiScreen(null));
    }

    @Override
    public void stopSound(String sound, String categoryName) {
        Minecraft.getInstance().enqueue(() -> {
            ResourceLocation location = sound == null ? null : ResourceLocation.tryCreate(sound);
            SoundCategory category = null;

            try {
                category = SoundCategory.valueOf(categoryName);
            } catch (IllegalArgumentException | NullPointerException e) {
                // Do nothing, if soundId is non-null Minecraft will auto-search, otherwise Minecraft stops all sounds.
            }

            Minecraft.getInstance().getSoundHandler().stop(location, category);
        });
    }

    @Override
    public void showToast(String title, String subtitle) {
        Minecraft.getInstance().enqueue(() -> {
            Minecraft.getInstance().getToastGui().add(new SystemToast(null, new StringTextComponent(title), new StringTextComponent(subtitle)));
        });
    }

    @Override
    public void openBook(JsonObject bookJSON) {
        Minecraft.getInstance().enqueue(() -> {
            CompoundNBT nbt = null;
            try {
                nbt = JsonToNBT.getTagFromJson(bookJSON.toString());
            } catch (CommandSyntaxException e) {
                LOGGER.error("Invalid JSON");
                return;
            }

            if (!WrittenBookItem.validBookTagContents(nbt)) {
                LOGGER.error("Invalid Book JSON");
                return;
            }

            ItemStack bookStack = new ItemStack(Items.WRITTEN_BOOK);
            bookStack.setTag(nbt);

            IBookInfo bookInfo = IBookInfo.func_216917_a(bookStack);

            ReadBookScreen screen = new ReadBookScreen(bookInfo);

            Minecraft.getInstance().displayGuiScreen(screen);
        });
    }

    @Override
    public void narrate(String message, boolean interrupt) {
        Minecraft.getInstance().enqueue(() -> {
            narrator.say(message, interrupt);
        });
    }

    @Override
    public void loadWorld(String worldName) {
        Minecraft.getInstance().enqueue(() -> {
            if (Minecraft.getInstance().getSaveLoader().canLoadWorld(worldName)) {
                if (Minecraft.getInstance().world != null) {
                    LOGGER.info("Disconnecting from world...");

                    Minecraft.getInstance().world.sendQuittingDisconnectingPacket();
                    Minecraft.getInstance().unloadWorld();
                }

                LOGGER.info("Loading world...");
                Minecraft.getInstance().loadWorld(worldName);
            } else {
                LOGGER.warn("World " + worldName + " does not exist!");
            }
        });
    }
}
