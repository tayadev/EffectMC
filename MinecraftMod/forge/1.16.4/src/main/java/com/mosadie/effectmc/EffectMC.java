package com.mosadie.effectmc;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.text2speech.Narrator;
import com.mosadie.effectmc.core.EffectExecutor;
import com.mosadie.effectmc.core.EffectMCCore;
import com.mosadie.effectmc.core.handler.*;
import net.minecraft.client.AbstractOption;
import net.minecraft.client.GameSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.client.gui.screen.*;
import net.minecraft.client.gui.toasts.SystemToast;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.settings.PointOfView;
import net.minecraft.entity.player.ChatVisibility;
import net.minecraft.entity.player.PlayerModelPart;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.WrittenBookItem;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLConfig;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static net.minecraft.client.gui.screen.ReadBookScreen.IBookInfo;

@Mod(EffectMC.MODID)
public class EffectMC implements EffectExecutor {
    public final static String MODID = "effectmc";

    private final EffectMCCore core;

    public static Logger LOGGER = LogManager.getLogger();

    private static Narrator narrator = Narrator.getNarrator();
    private static ServerData serverData = new ServerData("", "", false); // Used to hold data during Open Screen

    private final HttpClient authedClient;

    public EffectMC() throws IOException {
        //File configDir = ModList.get().getModFileById(MODID).getFile().getFilePath().resolve("../" + MODID + "/").toFile();
        File configDir = FMLPaths.GAMEDIR.get().resolve(FMLConfig.defaultConfigPath()).resolve("./" + MODID + "/").toFile();
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
        boolean result;
        try {
            result = core.initServer();
        } catch (URISyntaxException e) {
            LOGGER.error("Failed to initialize server due to internal error, please report this!", e);
            result = false;
        }
        LOGGER.info("Server start result: " + result);

        MinecraftForge.EVENT_BUS.addListener(this::onChat);

        Header authHeader = new BasicHeader("Authorization", "Bearer " + Minecraft.getInstance().getSession().getToken());
        List<Header> headers = new ArrayList<>();
        headers.add(authHeader);
        authedClient = HttpClientBuilder.create().setDefaultHeaders(headers).build();
    }

    @SubscribeEvent
    public void onChat(ClientChatEvent event) {
        if (event.getMessage().equalsIgnoreCase("/effectmc trust")) {
            Minecraft.getInstance().enqueue(core::setTrustNextRequest);
            receiveChatMessage("[EffectMC] Now prompting to trust the next request sent.");
            event.setCanceled(true);
        } else if (event.getMessage().equalsIgnoreCase("/effectmc exportbook")) {
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

                if (bookStack.getTag() == null) {
                    receiveChatMessage("[EffectMC] Failed to export book: Missing tag.");
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
    public boolean joinServer(String serverIp) {
        Minecraft.getInstance().enqueue(() -> {
            leaveIfNeeded();

            // Create ServerData
            ServerData server = new ServerData("EffectMC", serverIp, false);


            LOGGER.info("Connecting to " + server.serverIP);
            // Connect to server

            ConnectingScreen connectingScreen = new ConnectingScreen(new MainMenuScreen(), Minecraft.getInstance(), server);
            Minecraft.getInstance().displayGuiScreen(connectingScreen);
        });
        return true;
    }

    @Override
    public boolean setSkinLayer(SkinLayerHandler.SKIN_SECTION section, boolean visibility) {
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

        return true;
    }

    @Override
    public boolean toggleSkinLayer(SkinLayerHandler.SKIN_SECTION section) {
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

        return true;
    }

    @Override
    public boolean sendChatMessage(String message) {
        if (Minecraft.getInstance().player != null) {
            LOGGER.info("Sending chat message: " + message);
            Minecraft.getInstance().player.sendChatMessage(message);
            return true;
        }
        return false;
    }

    @Override
    public boolean receiveChatMessage(String message) {
        if (Minecraft.getInstance().player != null) {
            LOGGER.info("Showing chat message: " + message);
            Minecraft.getInstance().player.sendMessage(new StringTextComponent(message), Minecraft.getInstance().player.getUniqueID());
            return true;
        }
        return false;
    }

    @Override
    public boolean showTitle(String title, String subtitle) {
        LOGGER.info("Showing Title: " + title + " Subtitle: " + subtitle);
        Minecraft.getInstance().ingameGUI.setDefaultTitlesTimes();
        Minecraft.getInstance().ingameGUI.func_238452_a_(null, new StringTextComponent(subtitle), -1, -1, -1);
        Minecraft.getInstance().ingameGUI.func_238452_a_(new StringTextComponent(title), null, -1, -1, -1);
        return true;
    }

    @Override
    public boolean showActionMessage(String message) {
        LOGGER.info("Showing ActionBar message: " + message);
        Minecraft.getInstance().ingameGUI.setOverlayMessage(new StringTextComponent(message), false);
        return true;
    }

    @Override
    public boolean triggerDisconnect(DisconnectHandler.NEXT_SCREEN nextScreenType, String title, String message) {
        Minecraft.getInstance().enqueue(() -> {
            leaveIfNeeded();

            Screen nextScreen;

            switch (nextScreenType) {
                default:
                case MAIN_MENU:
                    nextScreen = new MainMenuScreen();
                    break;

                case SERVER_SELECT:
                    nextScreen = new MultiplayerScreen(new MainMenuScreen());
                    break;

                case WORLD_SELECT:
                    nextScreen = new WorldSelectionScreen(new MainMenuScreen());
                    break;
            }

            DisconnectedScreen screen = new DisconnectedScreen(nextScreen, new StringTextComponent(title), new StringTextComponent(message));
            Minecraft.getInstance().displayGuiScreen(screen);
        });
        return true;
    }

    @Override
    public boolean playSound(String soundID, String categoryName, float volume, float pitch, boolean repeat, int repeatDelay, String attenuationType, double x, double y, double z, boolean relative, boolean global) {
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

            if (relative && Minecraft.getInstance().world != null && Minecraft.getInstance().player != null) {
                trueX += Minecraft.getInstance().player.getPosX();
                trueY += Minecraft.getInstance().player.getPosY();
                trueZ += Minecraft.getInstance().player.getPosZ();
            }

            Minecraft.getInstance().getSoundHandler().play(new SimpleSound(sound, category, volume, pitch, repeat, repeatDelay, attenuation, trueX, trueY, trueZ, global));
        });
        return true;
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
    public boolean stopSound(String sound, String categoryName) {
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
        return true;
    }

    @Override
    public boolean showToast(String title, String subtitle) {
        Minecraft.getInstance().enqueue(() -> Minecraft.getInstance().getToastGui().add(new SystemToast(SystemToast.Type.NARRATOR_TOGGLE, new StringTextComponent(title), new StringTextComponent(subtitle))));
        return true;
    }

    @Override
    public boolean openBook(JsonObject bookJSON) {
        CompoundNBT nbt;
        try {
            nbt = JsonToNBT.getTagFromJson(bookJSON.toString());
        } catch (CommandSyntaxException e) {
            LOGGER.error("Invalid JSON");
            return false;
        }

        if (!WrittenBookItem.validBookTagContents(nbt)) {
            LOGGER.error("Invalid Book JSON");
            return false;
        }

        ItemStack bookStack = new ItemStack(Items.WRITTEN_BOOK);
        bookStack.setTag(nbt);

        IBookInfo bookInfo = IBookInfo.func_216917_a(bookStack);

        ReadBookScreen screen = new ReadBookScreen(bookInfo);

        Minecraft.getInstance().enqueue(() -> Minecraft.getInstance().displayGuiScreen(screen));
        return true;
    }

    @Override
    public boolean narrate(String message, boolean interrupt) {
        if (narrator.active()) {
            Minecraft.getInstance().execute(() -> narrator.say(message, interrupt));
            return true;
        }
        LOGGER.error("Narrator is unavailable!");
        return false;
    }

    @Override
    public boolean loadWorld(String worldName) {

        if (Minecraft.getInstance().getSaveLoader().canLoadWorld(worldName)) {
            Minecraft.getInstance().enqueue(() -> {
                if (Minecraft.getInstance().world != null) {
                    LOGGER.info("Disconnecting from world...");

                    Minecraft.getInstance().world.sendQuittingDisconnectingPacket();
                    Minecraft.getInstance().unloadWorld();
                }

                LOGGER.info("Loading world...");
                Minecraft.getInstance().loadWorld(worldName);
            });
            return true;
        } else {
            LOGGER.warn("World " + worldName + " does not exist!");
            return false;
        }
    }

    @Override
    public boolean setSkin(URL skinUrl, SetSkinHandler.SKIN_TYPE skinType) {
        if (skinUrl == null) {
            LOGGER.warn("Skin URL is null!");
            return false;
        }

        try {
            JsonObject payload = new JsonObject();

            payload.add("variant", new JsonPrimitive(skinType.getValue()));
            payload.add("url", new JsonPrimitive(skinUrl.toString()));

            HttpPost request = new HttpPost("https://api.minecraftservices.com/minecraft/profile/skins");
            request.setEntity(new StringEntity(payload.toString(), ContentType.APPLICATION_JSON));

            HttpResponse response = authedClient.execute(request);

            if (response.getEntity() != null && response.getEntity().getContentLength() > 0) {
                JsonObject responseJSON = core.fromJson(IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8));
                if (responseJSON.has("errorMessage")) {
                    LOGGER.warn("Failed to update skin! " + responseJSON);
                    return false;
                }

                LOGGER.debug("Skin Update Response: " + responseJSON);
            }

            LOGGER.info("Skin updated!");
            return true;
        } catch (IOException e) {
            LOGGER.warn("Failed to update skin!", e);
            return false;
        }
    }

    public void leaveIfNeeded() {
        if (Minecraft.getInstance().world != null) {
            LOGGER.info("Disconnecting from world...");

            Minecraft.getInstance().world.sendQuittingDisconnectingPacket();
            Minecraft.getInstance().unloadWorld();
        }
    }

    @Override
    public boolean openScreen(OpenScreenHandler.SCREEN screen) {
        Minecraft.getInstance().execute(() -> {
            leaveIfNeeded();

            switch (screen) {
                case MAIN_MENU:
                    Minecraft.getInstance().displayGuiScreen(new MainMenuScreen());
                    break;
                case SERVER_SELECT:
                    Minecraft.getInstance().displayGuiScreen(new MultiplayerScreen(new MainMenuScreen()));
                    break;
                case SERVER_DIRECT_CONNECT:
                    Minecraft.getInstance().displayGuiScreen(new ServerListScreen(new MultiplayerScreen(new MainMenuScreen()), this::connectIfTrue, serverData));
                    break;
                case WORLD_SELECT:
                    Minecraft.getInstance().displayGuiScreen(new WorldSelectionScreen(new MainMenuScreen()));
                    break;
                case WORLD_CREATE:
                    Minecraft.getInstance().displayGuiScreen(CreateWorldScreen.func_243425_a(new WorldSelectionScreen(new MainMenuScreen())));
                    break;
                default:
                    LOGGER.error("Unknown screen.");
            }
        });
        return true;
    }

    @Override
    public boolean setFOV(int fov) {
        Minecraft.getInstance().execute(() -> AbstractOption.FOV.set(Minecraft.getInstance().gameSettings, fov));
        return true;
    }

    @Override
    public boolean setPOV(SetPovHandler.POV pov) {
        PointOfView mcPov;

        switch (pov) {
            default:
            case FIRST_PERSON:
                mcPov = PointOfView.FIRST_PERSON;
                break;

            case THIRD_PERSON_BACK:
                mcPov = PointOfView.THIRD_PERSON_BACK;
                break;

            case THIRD_PERSON_FRONT:
                mcPov = PointOfView.THIRD_PERSON_FRONT;
                break;
        }

        Minecraft.getInstance().execute(() -> Minecraft.getInstance().gameSettings.setPointOfView(mcPov));
        return true;
    }

    @Override
    public boolean setGuiScale(int scale) {
        if (Minecraft.getInstance().gameSettings.guiScale == scale) {
            return true;
        }

        Minecraft.getInstance().enqueue(() -> {
            Minecraft.getInstance().gameSettings.guiScale = scale;
            Minecraft.getInstance().gameSettings.saveOptions();
            Minecraft.getInstance().updateWindowSize();
        });
        return true;
    }

    @Override
    public boolean setGamma(double gamma) {
        AbstractOption.GAMMA.set(Minecraft.getInstance().gameSettings, gamma);
        return true;
    }

    @Override
    public boolean setChatVisibility(ChatVisibilityHandler.VISIBILITY visibility) {
        ChatVisibility result;
        switch (visibility) {
            case SHOW:
                result = ChatVisibility.FULL;
                break;

            case COMMANDS_ONLY:
                result = ChatVisibility.SYSTEM;
                break;

            case HIDE:
                result = ChatVisibility.HIDDEN;
                break;

            default:
                return false;
        }

        Minecraft.getInstance().enqueue(() -> {
            Minecraft.getInstance().gameSettings.chatVisibility = result;
            Minecraft.getInstance().gameSettings.saveOptions();
        });
        return true;
    }

    @Override
    public boolean setRenderDistance(int chunks) {
        AbstractOption.RENDER_DISTANCE.set(Minecraft.getInstance().gameSettings, chunks);
        return true;
    }

    private void connectIfTrue(boolean connect) {
        if (connect) {
            joinServer(serverData.serverIP);
        } else {
            Minecraft.getInstance().displayGuiScreen(new MultiplayerScreen(new MainMenuScreen()));
        }
    }
}
