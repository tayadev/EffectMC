package com.mosadie.effectmc;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.text2speech.Narrator;
import com.mosadie.effectmc.core.EffectExecutor;
import com.mosadie.effectmc.core.EffectMCCore;
import com.mosadie.effectmc.core.handler.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.*;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ChatVisibility;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.Option;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.entity.PlayerModelPart;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.WrittenBookItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
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

public class EffectMC implements ModInitializer, ClientModInitializer, EffectExecutor {

	public static String MODID = "effectmc";

	private EffectMCCore core;

	public static Logger LOGGER = LogManager.getLogger();

	private static Narrator narrator = Narrator.getNarrator();
	private static ServerInfo serverInfo = new ServerInfo("", "", false); // Used to hold data during Open Screen

	private HttpClient authedClient;

	@Override
	public void onInitialize() {
		System.out.println("Hello Fabric world!");
	}

	@Override
	public void onInitializeClient() {
		File configDir = FabricLoader.getInstance().getConfigDir().resolve("../" + MODID + "/").toFile();
		if (!configDir.exists()) {
			if (!configDir.mkdirs()) {
				LOGGER.error("Something went wrong creating the config directory! The mod will not work until this is fixed!");
				return;
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

		// Register command
		ClientCommandManager.DISPATCHER.register(ClientCommandManager.literal("effectmc")
				.then(ClientCommandManager.literal("trust").executes((context -> {
					MinecraftClient.getInstance().send(core::setTrustNextRequest);
					receiveChatMessage("[EffectMC] Now prompting to trust the next request sent.");
					return 0;
				})))
				.then(ClientCommandManager.literal("exportbook").executes((context -> {
					if (MinecraftClient.getInstance().player == null) {
						return 0;
					}

					ItemStack mainHand = MinecraftClient.getInstance().player.getMainHandStack();
					ItemStack offHand = MinecraftClient.getInstance().player.getOffHandStack();

					ItemStack bookStack = null;
					if (mainHand.getItem().equals(Items.WRITTEN_BOOK)) {
						bookStack = mainHand;
					} else if (offHand.getItem().equals(Items.WRITTEN_BOOK)) {
						bookStack = offHand;
					}

					if (bookStack == null) {
						receiveChatMessage("[EffectMC] Failed to export book: Not holding a book!");
						return 0;
					}

					if (bookStack.getNbt() == null) {
						receiveChatMessage("[EffectMC] Failed to export book: Missing tag.");
						return 0;
					}

					LOGGER.info("Exported Book JSON: " + bookStack.getNbt());
					receiveChatMessage("[EffectMC] Exported the held book to the current log file.");
					return 0;
				}))).executes((context -> {
					receiveChatMessage("[EffectMC] Available subcommands: exportbook, trust");
					return 0;
				})));

		Header authHeader = new BasicHeader("Authorization", "Bearer " + MinecraftClient.getInstance().getSession().getAccessToken());
		List<Header> headers = new ArrayList<>();
		headers.add(authHeader);
		authedClient = HttpClientBuilder.create().setDefaultHeaders(headers).build();
	}

	@Override
	public void log(String message) {
		LOGGER.info(message);
	}

	@Override
	public boolean joinServer(String serverIp) {
		MinecraftClient.getInstance().send(() -> {
			leaveIfNeeded();

			if (!ServerAddress.isValid(serverIp)) {
				LOGGER.warn("Invalud server address: " + serverIp);
				return;
			}

			ServerAddress address = ServerAddress.parse(serverIp);
			ServerInfo info = new ServerInfo("EffectMC", serverIp, false);


			LOGGER.info("Connecting to " + serverIp);

			// Connect to server

			ConnectScreen.connect(new TitleScreen(), MinecraftClient.getInstance(), address, info);
		});

		return true;
	}

	@Override
	public boolean setSkinLayer(SkinLayerHandler.SKIN_SECTION section, boolean visibility) {
		GameOptions options = MinecraftClient.getInstance().options;

		switch (section) {
			case ALL:
				options.togglePlayerModelPart(PlayerModelPart.CAPE, visibility);
				// Fall to ALL_BODY
			case ALL_BODY:
				options.togglePlayerModelPart(PlayerModelPart.HAT, visibility);
				options.togglePlayerModelPart(PlayerModelPart.JACKET, visibility);
				options.togglePlayerModelPart(PlayerModelPart.LEFT_SLEEVE, visibility);
				options.togglePlayerModelPart(PlayerModelPart.LEFT_PANTS_LEG, visibility);
				options.togglePlayerModelPart(PlayerModelPart.RIGHT_SLEEVE, visibility);
				options.togglePlayerModelPart(PlayerModelPart.RIGHT_PANTS_LEG, visibility);
				break;
			case CAPE:
				options.togglePlayerModelPart(PlayerModelPart.CAPE, visibility);
				break;
			case JACKET:
				options.togglePlayerModelPart(PlayerModelPart.JACKET, visibility);
				break;
			case LEFT_SLEEVE:
				options.togglePlayerModelPart(PlayerModelPart.LEFT_SLEEVE, visibility);
				break;
			case RIGHT_SLEEVE:
				options.togglePlayerModelPart(PlayerModelPart.RIGHT_SLEEVE, visibility);
				break;
			case LEFT_PANTS_LEG:
				options.togglePlayerModelPart(PlayerModelPart.LEFT_PANTS_LEG, visibility);
				break;
			case RIGHT_PANTS_LEG:
				options.togglePlayerModelPart(PlayerModelPart.RIGHT_PANTS_LEG, visibility);
				break;
			case HAT:
				options.togglePlayerModelPart(PlayerModelPart.HAT, visibility);
				break;
		}

		return true;
	}

	@Override
	public boolean toggleSkinLayer(SkinLayerHandler.SKIN_SECTION section) {
		GameOptions options = MinecraftClient.getInstance().options;

		switch (section) {
			case ALL:
				trueTogglePlayerModelPart(options, PlayerModelPart.CAPE);
				// Fall to ALL_BODY
			case ALL_BODY:
				trueTogglePlayerModelPart(options, PlayerModelPart.HAT);
				trueTogglePlayerModelPart(options, PlayerModelPart.JACKET);
				trueTogglePlayerModelPart(options, PlayerModelPart.LEFT_SLEEVE);
				trueTogglePlayerModelPart(options, PlayerModelPart.LEFT_PANTS_LEG);
				trueTogglePlayerModelPart(options, PlayerModelPart.RIGHT_SLEEVE);
				trueTogglePlayerModelPart(options, PlayerModelPart.RIGHT_PANTS_LEG);
				break;
			case CAPE:
				trueTogglePlayerModelPart(options, PlayerModelPart.CAPE);
				break;
			case JACKET:
				trueTogglePlayerModelPart(options, PlayerModelPart.JACKET);
				break;
			case LEFT_SLEEVE:
				trueTogglePlayerModelPart(options, PlayerModelPart.LEFT_SLEEVE);
				break;
			case RIGHT_SLEEVE:
				trueTogglePlayerModelPart(options, PlayerModelPart.RIGHT_SLEEVE);
				break;
			case LEFT_PANTS_LEG:
				trueTogglePlayerModelPart(options, PlayerModelPart.LEFT_PANTS_LEG);
				break;
			case RIGHT_PANTS_LEG:
				trueTogglePlayerModelPart(options, PlayerModelPart.RIGHT_PANTS_LEG);
				break;
			case HAT:
				trueTogglePlayerModelPart(options, PlayerModelPart.HAT);
				break;
		}

		return true;
	}

	private void trueTogglePlayerModelPart(GameOptions options, PlayerModelPart part) {
		options.togglePlayerModelPart(part, !options.isPlayerModelPartEnabled(part));
	}

	@Override
	public boolean sendChatMessage(String message) {
		if (MinecraftClient.getInstance().player != null) {
			LOGGER.info("Sending chat message: " + message);
			MinecraftClient.getInstance().player.sendChatMessage(message);

			return true;
		}

		return false;
	}

	@Override
	public boolean receiveChatMessage(String message) {
		if (MinecraftClient.getInstance().player != null) {
			LOGGER.info("Showing chat message: " + message);
			MinecraftClient.getInstance().player.sendSystemMessage(Text.of(message), MinecraftClient.getInstance().player.getUuid());

			return true;
		}

		return false;
	}

	@Override
	public boolean showTitle(String title, String subtitle) {
		LOGGER.info("Showing Title: " + title + " Subtitle: " + subtitle);
		MinecraftClient.getInstance().inGameHud.setDefaultTitleFade();
		MinecraftClient.getInstance().inGameHud.setSubtitle(Text.of(subtitle));
		MinecraftClient.getInstance().inGameHud.setTitle(Text.of(title));

		return true;
	}

	@Override
	public boolean showActionMessage(String message) {
		LOGGER.info("Showing ActionBar message: " + message);
		MinecraftClient.getInstance().inGameHud.setOverlayMessage(Text.of(message), false);

		return true;
	}

	@Override
	public void showTrustPrompt(String device) {
		MinecraftClient.getInstance().send(() -> {
			ConfirmScreen screen = new ConfirmScreen(new EffectMCCore.TrustBooleanConsumer(device, core), Text.of("EffectMC - Trust Prompt"), Text.of("Do you want to trust this device? (" + device + ")"));
			MinecraftClient.getInstance().setScreen(screen);
		});
	}

	@Override
	public boolean triggerDisconnect(DisconnectHandler.NEXT_SCREEN nextScreenType, String title, String message) {
		MinecraftClient.getInstance().send(() -> {
			leaveIfNeeded();

			Screen nextScreen;

			switch (nextScreenType) {
				default:
				case MAIN_MENU:
					nextScreen = new TitleScreen();
					break;

				case SERVER_SELECT:
					nextScreen = new MultiplayerScreen(new TitleScreen());
					break;

				case WORLD_SELECT:
					nextScreen = new SelectWorldScreen(new TitleScreen());
					break;
			}

			DisconnectedScreen screen = new DisconnectedScreen(nextScreen, Text.of(title), Text.of(message));
			MinecraftClient.getInstance().setScreen(screen);
		});

		return true;
	}

	@Override
	public boolean playSound(String soundID, String categoryName, float volume, float pitch, boolean repeat, int repeatDelay, String attenuationType, double x, double y, double z, boolean relative, boolean global) {
		MinecraftClient.getInstance().send(() -> {
			Identifier sound = new Identifier(soundID);

			SoundCategory category;
			try {
				category = SoundCategory.valueOf(categoryName.toUpperCase());
			} catch (IllegalArgumentException e) {
				category = SoundCategory.MASTER;
			}

			SoundInstance.AttenuationType attenuation;
			try {
				attenuation = SoundInstance.AttenuationType.valueOf(attenuationType.toUpperCase());
			} catch (IllegalArgumentException e) {
				attenuation = SoundInstance.AttenuationType.NONE;
			}

			double trueX = x;
			double trueY = y;
			double trueZ = z;

			if (relative && MinecraftClient.getInstance().world != null && MinecraftClient.getInstance().player != null) {
				trueX += MinecraftClient.getInstance().player.getX();
				trueY += MinecraftClient.getInstance().player.getY();
				trueZ += MinecraftClient.getInstance().player.getZ();
			}

			MinecraftClient.getInstance().getSoundManager().play(new PositionedSoundInstance(sound, category, volume, pitch, repeat, repeatDelay, attenuation, trueX, trueY, trueZ, global));
		});

		return true;
	}

	@Override
	public void resetScreen() {
		MinecraftClient.getInstance().send(() -> MinecraftClient.getInstance().setScreen(null));
	}

	@Override
	public boolean stopSound(String sound, String categoryName) {
		MinecraftClient.getInstance().send(() -> {
			Identifier location = sound == null ? null : Identifier.tryParse(sound);
			SoundCategory category = null;

			try {
				category = SoundCategory.valueOf(categoryName);
			} catch (IllegalArgumentException | NullPointerException e) {
				// Do nothing, if soundId is non-null Minecraft will auto-search, otherwise Minecraft stops all sounds.
			}

			MinecraftClient.getInstance().getSoundManager().stopSounds(location, category);
		});

		return true;
	}

	@Override
	public boolean showToast(String title, String subtitle) {
		MinecraftClient.getInstance().send(() -> MinecraftClient.getInstance().getToastManager().add(new SystemToast(SystemToast.Type.NARRATOR_TOGGLE, Text.of(title), Text.of(subtitle))));

		return true;
	}

	@Override
	public boolean openBook(JsonObject bookJSON) {
		MinecraftClient.getInstance().send(() -> {
			NbtCompound nbt = null;
			try {
				nbt = StringNbtReader.parse(bookJSON.toString());
			} catch (CommandSyntaxException e) {
				LOGGER.error("Invalid JSON");
				return;
			}

			if (!WrittenBookItem.isValid(nbt)) {
				LOGGER.error("Invalid Book JSON");
				return;
			}

			ItemStack bookStack = new ItemStack(Items.WRITTEN_BOOK);
			bookStack.setNbt(nbt);

			BookScreen.Contents bookContents = BookScreen.Contents.create(bookStack);

			BookScreen screen = new BookScreen(bookContents);

			MinecraftClient.getInstance().setScreen(screen);
		});

		return true;
	}

	@Override
	public boolean narrate(String message, boolean interrupt) {
		if (narrator.active()) {
			MinecraftClient.getInstance().send(() -> narrator.say(message, interrupt));
			return true;
		}

		LOGGER.error("Narrator is unavailable!");

		return false;
	}

	@Override
	public boolean loadWorld(String worldName) {
		MinecraftClient.getInstance().send(() -> {
			if (MinecraftClient.getInstance().getLevelStorage().levelExists(worldName)) {
				leaveIfNeeded();

				LOGGER.info("Loading world...");
				MinecraftClient.getInstance().startIntegratedServer(worldName);
			} else {
				LOGGER.warn("World " + worldName + " does not exist!");
			}
		});

		return true;
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
		if (MinecraftClient.getInstance().world != null) {
			LOGGER.info("Disconnecting from world...");

			MinecraftClient.getInstance().world.disconnect();
			MinecraftClient.getInstance().disconnect();
		}
	}
	@Override
	public boolean openScreen(OpenScreenHandler.SCREEN screen) {
		MinecraftClient.getInstance().execute(() -> {
			leaveIfNeeded();

			switch (screen) {
				case MAIN_MENU:
					MinecraftClient.getInstance().setScreen(new TitleScreen());
					break;
				case SERVER_SELECT:
					MinecraftClient.getInstance().setScreen(new MultiplayerScreen(new TitleScreen()));
					break;
				case SERVER_DIRECT_CONNECT:
					MinecraftClient.getInstance().setScreen(new DirectConnectScreen(new MultiplayerScreen(new TitleScreen()), this::connectIfTrue, serverInfo));
					break;
				case WORLD_SELECT:
					MinecraftClient.getInstance().setScreen(new SelectWorldScreen(new TitleScreen()));
					break;
				case WORLD_CREATE:
					MinecraftClient.getInstance().setScreen(CreateWorldScreen.create(new SelectWorldScreen(new TitleScreen())));
					break;
				default:
					LOGGER.error("Unknown screen.");
			}
		});
		return true;
	}

	@Override
	public boolean setFOV(int fov) {
		MinecraftClient.getInstance().execute(() -> Option.FOV.set(MinecraftClient.getInstance().options, fov));
		return true;
	}

	@Override
	public boolean setPOV(SetPovHandler.POV pov) {
		Perspective mcPov;

		switch (pov) {
			default:
			case FIRST_PERSON:
				mcPov = Perspective.FIRST_PERSON;
				break;

			case THIRD_PERSON_BACK:
				mcPov = Perspective.THIRD_PERSON_BACK;
				break;

			case THIRD_PERSON_FRONT:
				mcPov = Perspective.THIRD_PERSON_FRONT;
				break;
		}

		MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().options.setPerspective(mcPov));
		return true;
	}

	@Override
	public boolean setGuiScale(int scale) {
		if (MinecraftClient.getInstance().options.guiScale == scale) {
			return true;
		}

		MinecraftClient.getInstance().execute(() -> {
			MinecraftClient.getInstance().options.guiScale = scale;
			MinecraftClient.getInstance().options.write();
			MinecraftClient.getInstance().onResolutionChanged();
		});
		return true;
	}

	@Override
	public boolean setGamma(double gamma) {
		Option.GAMMA.set(MinecraftClient.getInstance().options, gamma);
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

		MinecraftClient.getInstance().execute(() -> {
			MinecraftClient.getInstance().options.chatVisibility = result;
			MinecraftClient.getInstance().options.write();
		});
		return true;
	}

	@Override
	public boolean setRenderDistance(int chunks) {
		Option.RENDER_DISTANCE.set(MinecraftClient.getInstance().options, chunks);
		return true;
	}

	private void connectIfTrue(boolean connect) {
		if (connect) {
			joinServer(serverInfo.address);
		} else {
			MinecraftClient.getInstance().setScreen(new MultiplayerScreen(new TitleScreen()));
		}
	}
}
