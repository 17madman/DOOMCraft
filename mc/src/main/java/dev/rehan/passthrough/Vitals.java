package dev.rehan.passthrough;

import java.util.Locale;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * The player's life is the host's: the hearts show the host's health (5 health a half heart, more hearts above 100),
 * the armour bar its armour. What hurts the player here hurts the host's player instead, and what heals the player
 * here (food, potions) heals the host's.
 */
public final class Vitals {
	private static final float HOST_PER_POINT = 5.0F;
	/** The host's latest health and armour, or -1 before the first report (host thread -> server tick). */
	private static volatile int hostHealth = -1;
	private static volatile int hostArmor;
	private static volatile float hostPoints = -1.0F, hostMax = -1.0F;
	/**
	 * {"t":"hp",...,"food":0}: no hunger under this host. Its world has nothing to eat and its own ways to heal
	 * (hearts, fairies, potions): the food bar stays full and does nothing.
	 */
	private static volatile boolean hunger = true;

	public static void hunger(final boolean on) {
		hunger = on;
	}

	public static boolean hungerOff() {
		return Passthrough.active && !hunger;
	}
	/** The health we last gave the player (server thread): more than this by the next tick is a heal from Minecraft. */
	private static float lastSet = -1.0F;
	/** Hurts that never reach the host: its own world decides those (falls, walls, its explosions). */
	private static final java.util.List<ResourceKey<DamageType>> IGNORED = java.util.List.of(
		DamageTypes.FALL, DamageTypes.IN_WALL, DamageTypes.FELL_OUT_OF_WORLD, DamageTypes.CRAMMING, DamageTypes.FLY_INTO_WALL,
		DamageTypes.DROWN, DamageTypes.EXPLOSION, DamageTypes.PLAYER_EXPLOSION
	);

	private static long hurtSaid;

	private Vitals() {
	}

	/** {"t":"hp","h":health,"a":armour} */
	public static void host(final int health, final int armor) {
		hostHealth = health;
		hostArmor = armor;
	}

	/**
	 * {"t":"hp",...,"pts":health,"max":most}: a host that counts in hearts gives both in Minecraft's points (2 a
	 * heart), and the hearts then show exactly its own. Negative: the host counts to 100 ("h").
	 */
	public static void hostPoints(final float points, final float max) {
		hostPoints = points;
		hostMax = max;
	}

	static void detach() {
		hunger = true;
		hostPoints = hostMax = -1.0F;
		hostHealth = -1;
		lastSet = -1.0F;
	}

	/**
	 * The player is about to be hurt (server thread). True: the hurt doesn't happen here (the host takes it, or it
	 * isn't one that counts).
	 */
	public static boolean hurt(final DamageSource source, final float amount) {
		if (!Passthrough.active || hostHealth < 0 || source.is(DamageTypes.GENERIC_KILL)) {
			return false;
		}

		long now = System.nanoTime();
		if (now - hurtSaid > 2_000_000_000L) {
			hurtSaid = now;
			Passthrough.LOG.info("hurt: {} of {} to the host", amount, source.type().msgId());
		}

		for (ResourceKey<DamageType> type : IGNORED) {
			if (source.is(type)) {
				return true;
			}
		}

		Passthrough.events.accept(String.format(Locale.ROOT, "{\"t\":\"hurt\",\"d\":%.2f}", amount * HOST_PER_POINT));
		return true;
	}

	/** Every server tick. */
	static void tick(final MinecraftServer s) {
		int health = hostHealth;
		if (!Passthrough.active || health < 0 || s.getPlayerList().getPlayers().isEmpty()) {
			lastSet = -1.0F;
			return;
		}

		ServerPlayer player = s.getPlayerList().getPlayers().get(0);
		if (!player.isAlive()) {
			lastSet = -1.0F;
			return;
		}

		float now = player.getHealth();
		// (nothing heals while the host is paused: what Minecraft added meanwhile, a potion still working, is taken
		// off again below, where his health is set to the host's)
		boolean paused = s.tickRateManager().isFrozen();
		if (paused) {
			lastSet = -1.0F;
		}

		if (lastSet >= 0.0F && now > lastSet + 0.01F) {
			// Minecraft healed the player (natural regeneration from food, a potion, a golden apple)
			float gained = (now - lastSet) * HOST_PER_POINT;
			Passthrough.events.accept(String.format(Locale.ROOT, "{\"t\":\"heal\",\"d\":%.2f}", gained));
			health += Math.round(gained);
		}

		// more than 100 health: more hearts
		AttributeInstance max = player.getAttribute(Attributes.MAX_HEALTH);
		float points = hostPoints, most = hostMax;
		boolean hearts = points >= 0.0F && most > 0.0F;
		double wantMax = hearts
			? Math.clamp(Math.ceil(most / 2.0) * 2.0, 2.0, 1024.0)
			: Math.max(20.0, Math.ceil(Math.min(health, 200) / (HOST_PER_POINT * 2.0F)) * 2.0);
		if (max != null && max.getBaseValue() != wantMax) {
			max.setBaseValue(wantMax);
		}

		// never 0 here: the host decides when its player is dead, and Minecraft's death screen has no place over it
		float want = hearts
			? Math.clamp(points + (lastSet >= 0.0F && now > lastSet + 0.01F ? now - lastSet : 0.0F), 0.5F, (float)wantMax)
			: Math.clamp(health / HOST_PER_POINT, 1.0F, (float)wantMax);
		if (Math.abs(now - want) > 0.01F) {
			player.setHealth(want);
		}

		lastSet = want;
		AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
		double wantArmor = Math.clamp(hostArmor / 10.0, 0.0, 20.0);
		if (armor != null && armor.getBaseValue() != wantArmor) {
			armor.setBaseValue(wantArmor);
		}
	}
}
