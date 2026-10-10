package dev.rehan.passthrough;

import java.util.function.Consumer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Passthrough implements ModInitializer {
	public static final String ID = "passthrough";
	public static final Logger LOG = LoggerFactory.getLogger(ID);
	/** True while a host game is driving the camera. The integrated server shares this JVM, so both sides read it. */
	public static volatile boolean active;
	/** Minecraft moves the player itself, on the host's ground (its own physics and collision), and the host follows. */
	public static volatile boolean walk;
	/**
	 * While he walks: true, he collides with the host's ground as a smooth height field (Ground: a host with sloped
	 * ground, and nothing to walk under); false, with the blocks the ground was placed as (a host whose map is
	 * columns anyway and has ceilings, doors and mined tunnels, which only the blocks know about).
	 */
	public static volatile boolean heightField = true;
	/** Where events for the host go (JSON lines); the client's HostLink sets it. */
	public static volatile Consumer<String> events = message -> {};
	/** Asks the client to draw the blocks around a position again (what is drawn of them has changed, not they). */
	public static volatile Consumer<net.minecraft.core.BlockPos> redraw = pos -> {};

	/**
	 * A setting of the mod: -Dpassthrough.name=..., or the environment variable PASSTHROUGH_NAME (which a host's
	 * start script can set without touching how Minecraft is launched).
	 */
	public static String option(final String name, final String otherwise) {
		String value = System.getProperty("passthrough." + name);
		if (value == null) {
			value = System.getenv("PASSTHROUGH_" + name.toUpperCase(java.util.Locale.ROOT));
		}

		return value == null || value.isEmpty() ? otherwise : value;
	}

	/** The world is kept from one session to the next (a host that is played through, not a map of the moment). */
	public static boolean keepsWorld() {
		return option("keep", "").equals("1") || option("keep", "").equalsIgnoreCase("true");
	}

	@Override
	public void onInitialize() {
		GroundBlock.register();
		RockBlock.register();
		ServerLifecycleEvents.SERVER_STARTED.register(WorldBridge::attach);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			// before the world is saved: the Nether goes back and the fight's mobs go, so none of it is kept
			Nether.detach(server);
			MobWar.detach(server);
			WorldBridge.detach();
		});
		ServerEntityEvents.ENTITY_LOAD.register(MobWar::onEntityLoad);
		ServerTickEvents.END_SERVER_TICK.register(WorldBridge::tick);
		LOG.info("passthrough loaded");
	}
}
