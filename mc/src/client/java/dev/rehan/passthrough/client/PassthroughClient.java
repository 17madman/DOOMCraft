package dev.rehan.passthrough.client;

import dev.rehan.passthrough.Passthrough;
import dev.rehan.passthrough.WorldBridge;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.lwjgl.sdl.SDLVideo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;

public class PassthroughClient implements ClientModInitializer {
	/** The world the host plays in: an empty (void) creative world, so only what the player builds is Minecraft. */
	private static final String WORLD = Passthrough.option("world", "passthrough");
	/** Run on the server once the player has joined. */
	private static final List<String> SETUP = List.of(
		// (these two first: what the rest answer is not for the player's chat)
		"gamerule send_command_feedback false",
		"gamerule log_admin_commands false",
		"gamerule advance_time false",
		"gamerule advance_weather false",
		"gamerule spawn_mobs false",
		"gamerule spawn_monsters false",
		"gamerule spawn_patrols false",
		"gamerule spawn_phantoms false",
		"gamerule spawn_wandering_traders false",
		"gamerule keep_inventory true",
		"difficulty normal",
		"gamerule show_advancement_messages false",
		"gamerule player_movement_check false",
		"time set noon",
		"weather clear",
		"clear @a",
		"item replace entity @a hotbar.0 with minecraft:diamond_pickaxe[enchantments={efficiency:3}]",
		"item replace entity @a hotbar.1 with minecraft:diamond_sword",
		"item replace entity @a hotbar.2 with minecraft:bow[enchantments={power:3,infinity:1}]",
		"item replace entity @a hotbar.3 with minecraft:cobblestone 64",
		"item replace entity @a hotbar.4 with minecraft:oak_planks 64",
		"item replace entity @a hotbar.5 with minecraft:tnt 16",
		"item replace entity @a hotbar.6 with minecraft:flint_and_steel",
		"item replace entity @a hotbar.7 with minecraft:firework_rocket[fireworks={flight_duration:3}] 64",
		"item replace entity @a armor.chest with minecraft:elytra[unbreakable={}]",
		"give @a minecraft:cooked_beef 16",
		"item replace entity @a hotbar.8 with minecraft:creeper_spawn_egg 16",
		"give @a minecraft:arrow 16",
		"give @a minecraft:crafting_table",
		"give @a minecraft:zombie_spawn_egg 16",
		"give @a minecraft:skeleton_spawn_egg 16",
		"give @a minecraft:oak_log 32",
		"give @a minecraft:iron_ingot 16"
	);
	private static boolean configured;
	private static boolean worldRequested;

	/** The world that is open is the one this mod opened for a host (an empty one, with nothing to stand on). */
	static boolean ownWorld() {
		return worldRequested;
	}
	/** Server ticks until the setup commands run (the player isn't in the player list yet when JOIN fires). */
	private static int setupIn = -1;

	/**
	 * {"t":"newgame"}: the host has started a new game. The player starts again too: nothing carried, no experience,
	 * and then what a new player is given (see the kit, below), as when Minecraft has just started.
	 */
	static void newGame() {
		WorldBridge.command("clear @a");
		WorldBridge.command("xp set @a 0 levels");
		WorldBridge.command("xp set @a 0 points");
		WorldBridge.command("tag @a remove passthrough_kit");
		setupIn = 5;
	}
	private static int respawnIn;
	static boolean windowHidden;
	/**
	 * PASSTHROUGH_BACKGROUND=1: a run for testing while somebody is using the computer. The window is never shown
	 * (not only once a host is attached) and nothing is heard.
	 */
	private static final boolean BACKGROUND = Passthrough.option("background", "").equals("1");
	private static int quietIn;
	private static boolean reportedScreen;
	/** The host draws the pointer over our screens itself (where its mouse is now; ours would trail behind). */
	static volatile boolean hostCursor;
	private static int screenReportIn;

	@Override
	public void onInitializeClient() {
		HostLink.launch();
		// the host forwards its mouse to our screens, so the cursor has to be drawn here
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
			ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickDelta) -> {
				if (Passthrough.active && !hostCursor) {
					for (int i = 0; i < 9; i++) {
						int w = i < 6 ? i + 1 : 9 - i;
						graphics.fill(mouseX - 1, mouseY + i - 1, mouseX + w + 1, mouseY + i + 2, 0xFF000000);
					}

					for (int i = 0; i < 9; i++) {
						int w = i < 6 ? i + 1 : 9 - i;
						graphics.fill(mouseX, mouseY + i, mouseX + w, mouseY + i + 1, 0xFFFFFFFF);
					}
				}
			}));
		ClientTickEvents.END_CLIENT_TICK.register(PassthroughClient::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.player;
			// never left gliding from a previous session: with no host yet it would glide down into the void
			player.stopFallFlying();
			if (!Passthrough.keepsWorld()) {
				player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
			}

			setupIn = 10;
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (setupIn > 0 && --setupIn == 0) {
				// The rules every time; the things he is given only once in a world that is kept. What he is given
				// (PASSTHROUGH_KIT, or -Dpassthrough.kit): "starter", a wooden pickaxe and nothing else, as good as a new
				// survival world (the default); "full", the whole kit; "none", empty hands.
				ServerPlayer player = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
				boolean given = player != null && player.entityTags().contains("passthrough_kit");
				String wanted = Passthrough.option("kit", "starter");
				boolean full = wanted.equalsIgnoreCase("full"), none = wanted.equalsIgnoreCase("none");
				boolean again = given && Passthrough.keepsWorld();
				for (String command : SETUP) {
					boolean kit = command.startsWith("clear ") || command.startsWith("item ") || command.startsWith("give ");
					if (kit && (!full || again)) {
						continue;
					}

					WorldBridge.command(command);
				}

				if (!full && !none && !again) {
					WorldBridge.command("give @a minecraft:wooden_pickaxe");
				}

				if (player != null && !none) {
					player.addTag("passthrough_kit"); // (so that asking for the kit later still hands it out, once)
				}
			}
		});
	}

	private static int modeSaid = -1, modeSaidIn;

	private static void tick(final Minecraft minecraft) {
		BlockExport.tick(minecraft);
		// {"t":"mode","creative":bool}: in creative (or spectating) nothing can hurt the player here; the host is told,
		// to have its own player as safe (said when it changes, and again now and then for a host that came later)
		if (Passthrough.active && minecraft.player != null) {
			int creative = minecraft.player.getAbilities().invulnerable ? 1 : 0;
			if (creative != modeSaid || --modeSaidIn <= 0) {
				modeSaid = creative;
				modeSaidIn = 60;
				Passthrough.events.accept("{\"t\":\"mode\",\"creative\":" + (creative == 1) + "}");
			}
		}

		// a death (the void) would leave the death screen over the host's picture: respawn straight away
		if (minecraft.player != null && minecraft.gui.screen() instanceof DeathScreen && --respawnIn <= 0) {
			respawnIn = 40;
			minecraft.player.respawn();
		}

		// the pause menu (Minecraft opens it when its window loses the focus) would sit over the host's picture
		if (Passthrough.active && minecraft.gui.screen() instanceof PauseScreen) {
			minecraft.gui.screen().onClose();
		}

		// the host sends its mouse here while a screen (inventory, crafting table, chest...) is open
		boolean screenOpen = Passthrough.active && minecraft.gui.screen() != null && !(minecraft.gui.screen() instanceof DeathScreen);
		if (screenOpen != reportedScreen || Passthrough.active && ++screenReportIn >= 20) {
			reportedScreen = screenOpen;
			screenReportIn = 0;
			Passthrough.events.accept("{\"t\":\"screen\",\"open\":" + screenOpen + "}");
		}

		// The host shows Minecraft's picture, so Minecraft's own window only gets in the way, and a minimized one
		// stops drawing. While a host is attached the window is hidden; it comes back when the host goes.
		boolean hosted = HostState.live() != null || BACKGROUND;
		if (BACKGROUND && (++quietIn & 63) == 1) {
			// (not through the options: those are the player's, and would be saved)
			minecraft.getSoundManager().updateCategoryVolume(net.minecraft.sounds.SoundSource.MASTER, 0.0F);
		}

		if (hosted != windowHidden && !Boolean.getBoolean("passthrough.showWindow")) {
			windowHidden = hosted;
			long handle = minecraft.getWindow().handle();
			if (hosted) {
				SDLVideo.SDL_HideWindow(handle);
			} else {
				SDLVideo.SDL_ShowWindow(handle);
			}

			Passthrough.LOG.info("window {}", hosted ? "hidden (a host is attached)" : "shown again");
		}

		if (!configured) {
			configured = true;
			configure(minecraft.options);
		}

		if (!worldRequested && minecraft.level == null && minecraft.gui.screen() instanceof TitleScreen && !Boolean.getBoolean("passthrough.noAutoWorld")) {
			worldRequested = true;
			openWorld(minecraft);
		}
	}

	/** Settings for sitting behind another game: keep running unfocused, and no sky/cloud/bobbing effects in the picture. */
	private static void configure(final Options options) {
		options.pauseOnLostFocus = false;
		options.onboardAccessibility = false;
		options.tutorialStep = TutorialSteps.NONE;
		options.cloudStatus().set(CloudStatus.OFF);
		options.bobView().set(false);
		options.vignette().set(false);
		options.improvedTransparency().set(false);
		options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
		options.fovEffectScale().set(0.0);
		options.damageTiltStrength().set(0.0);
		options.menuBackgroundBlurriness().set(0);
		options.enableVsync().set(false);
		// the host shows ~60-120 fps: rendering faster only competes with it for the GPU
		options.framerateLimit().set(120);
		options.save();
	}

	private static void openWorld(final Minecraft minecraft) {
		// the world only mirrors the host's map of the moment: every session starts from an empty one (unless the
		// host is one that is played through, and asked for it to be kept: what is built, dug and carried stays)
		if (!Passthrough.keepsWorld() && minecraft.getLevelSource().levelExists(WORLD)) {
			try (LevelStorageSource.LevelStorageAccess access = minecraft.getLevelSource().createAccess(WORLD)) {
				access.deleteLevel();
			} catch (IOException e) {
				Passthrough.LOG.warn("couldn't delete the old {} world", WORLD, e);
			}
		}

		if (minecraft.getLevelSource().levelExists(WORLD)) {
			Passthrough.LOG.info("opening world {}", WORLD);
			minecraft.createWorldOpenFlows().openWorld(WORLD, () -> minecraft.gui.setScreen(new TitleScreen()));
		} else {
			Passthrough.LOG.info("creating world {}", WORLD);
			LevelSettings settings = new LevelSettings("Passthrough", GameType.SURVIVAL, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
			minecraft.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(0L, false, false), PassthroughClient::voidWorld, minecraft.gui.screen());
		}
	}

	/** A flat world with a single layer of air: nothing but what gets built (the host's ground arrives as barriers). */
	private static WorldDimensions voidWorld(final HolderLookup.Provider registries) {
		FlatLevelGeneratorSettings flat = new FlatLevelGeneratorSettings(
			Optional.of(HolderSet.direct()), registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS), List.of()
		);
		flat.getLayersInfo().add(new FlatLayerInfo(1, Blocks.AIR));
		flat.updateLayers();
		return WorldPresets.createNormalWorldDimensions(registries).replaceOverworldGenerator(registries, new FlatLevelSource(flat));
	}
}
