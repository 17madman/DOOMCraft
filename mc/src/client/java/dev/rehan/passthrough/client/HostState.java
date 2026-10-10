package dev.rehan.passthrough.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.rehan.passthrough.Passthrough;

/** The host's latest camera and player pose, already in Minecraft coordinates (the host converts). */
public final class HostState {
	/**
	 * @param hostFrame the host's frame number for this pose (echoed in the exported frame)
	 * @param x camera position
	 * @param yaw Minecraft yaw (0 = facing +Z), pitch (positive = down) and roll, in degrees
	 * @param fov vertical field of view, degrees
	 * @param firstPerson whether the host camera is first person (third person shows the player model)
	 * @param px the player's feet (third person)
	 * @param bodyYaw the player's body yaw (third person)
	 * @param drive Minecraft moves the player (elytra flight) and the host follows; lookYaw/lookPitch steer
	 * @param gun the player holds one of the host's guns (Steve aims it; his own item isn't drawn)
	 * @param ownLook third person: Steve looks along lookYaw/lookPitch instead of along the camera
	 * @param ray third person: Steve aims along this ray from the camera (the host's cursor), or null
	 * @param walk Minecraft walks the player (its own physics on the host's ground) and the host follows; lookYaw/lookPitch steer
	 */
	public record Pose(
		long hostFrame, double x, double y, double z, float yaw, float pitch, float roll, float fov,
		boolean firstPerson, double px, double py, double pz, float bodyYaw, long receivedNanos,
		boolean drive, float lookYaw, float lookPitch, boolean gun, boolean ownLook, double[] ray, boolean walk,
		String pose, boolean hurt, int swing, boolean blocks, float step, float[] limbs
	) {
	}

	private static final long TIMEOUT_NANOS = 2_000_000_000L;
	private static volatile Pose latest;
	/** The pose this frame renders with, taken once per frame so every hook agrees. Render thread only. */
	private static Pose frame;

	private HostState() {
	}

	/** {"t":"cam","f":frame,"p":[x,y,z],"r":[yaw,pitch,roll],"fov":deg,"fp":bool,"pl":[x,y,z],"h":bodyYaw} */
	static void update(final JsonObject m) {
		JsonArray p = m.getAsJsonArray("p");
		JsonArray r = m.getAsJsonArray("r");
		JsonArray pl = m.has("pl") ? m.getAsJsonArray("pl") : p;
		float yaw = r.get(0).getAsFloat();
		latest = new Pose(
			m.has("f") ? m.get("f").getAsLong() : 0L,
			p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble(),
			yaw, r.get(1).getAsFloat(), r.size() > 2 ? r.get(2).getAsFloat() : 0.0F,
			m.has("fov") ? m.get("fov").getAsFloat() : 70.0F,
			!m.has("fp") || m.get("fp").getAsBoolean(),
			pl.get(0).getAsDouble(), pl.get(1).getAsDouble(), pl.get(2).getAsDouble(),
			m.has("h") ? m.get("h").getAsFloat() : yaw,
			System.nanoTime(),
			m.has("drive") && m.get("drive").getAsBoolean(),
			m.has("look") ? m.getAsJsonArray("look").get(0).getAsFloat() : yaw,
			m.has("look") ? m.getAsJsonArray("look").get(1).getAsFloat() : r.get(1).getAsFloat(),
			m.has("gun") && m.get("gun").getAsBoolean(),
			m.has("look") && !(m.has("drive") && m.get("drive").getAsBoolean()),
			ray(m),
			m.has("walk") && m.get("walk").getAsBoolean() && m.has("look"),
			m.has("pose") ? m.get("pose").getAsString() : "",
			m.has("hurt") && m.get("hurt").getAsInt() != 0,
			m.has("swing") ? m.get("swing").getAsInt() : -1,
			m.has("blocks") && m.get("blocks").getAsBoolean(),
			m.has("step") ? m.get("step").getAsFloat() : 0.0F,
			limbs(m)
		);
	}

	/** "limbs": how the host's character holds its body and limbs (see Mimic), or null. */
	private static float[] limbs(final JsonObject m) {
		if (!m.has("limbs")) {
			return null;
		}

		JsonArray a = m.getAsJsonArray("limbs");
		float[] out = new float[a.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = a.get(i).getAsFloat();
		}

		return out;
	}

	/** "ray":[dx,dy,dz], a direction in the world (need not be unit length). */
	private static double[] ray(final JsonObject m) {
		if (!m.has("ray")) {
			return null;
		}

		JsonArray r = m.getAsJsonArray("ray");
		double x = r.get(0).getAsDouble(), y = r.get(1).getAsDouble(), z = r.get(2).getAsDouble();
		double length = Math.sqrt(x * x + y * y + z * z);
		return length > 1.0e-6 ? new double[] {x / length, y / length, z / length} : null;
	}

	/**
	 * While Minecraft walks the player its camera keeps the host's framing of him but goes where he is now, which is
	 * ahead of where the host has him: the frame is exported with that camera, so the host can carry the picture
	 * onto its own. (Render thread.)
	 */
	private static double cameraX, cameraY, cameraZ;

	public static void camera(final double x, final double y, final double z) {
		cameraX = x;
		cameraY = y;
		cameraZ = z;
	}

	/** The pose with the camera the frame was actually rendered from. */
	static Pose rendered(final Pose p) {
		if (p == null || !p.walk()) {
			return p;
		}

		return new Pose(p.hostFrame(), cameraX, cameraY, cameraZ, p.yaw(), p.pitch(), p.roll(), p.fov(), p.firstPerson(),
			p.px(), p.py(), p.pz(), p.bodyYaw(), p.receivedNanos(), p.drive(), p.lookYaw(), p.lookPitch(), p.gun(),
			p.ownLook(), p.ray(), p.walk(), p.pose(), p.hurt(), p.swing(), p.blocks(), p.step(), p.limbs());
	}

	/** The latest pose if the host is still sending, else null. */
	static Pose live() {
		Pose p = latest;
		return p != null && System.nanoTime() - p.receivedNanos() < TIMEOUT_NANOS ? p : null;
	}

	/** The pose for the frame being rendered, or null when no host is attached. */
	public static Pose frame() {
		return frame;
	}

	public static void beginFrame() {
		frame = live();
		Passthrough.active = frame != null;
		Passthrough.walk = frame != null && frame.walk();
		var player = net.minecraft.client.Minecraft.getInstance().player;
		Passthrough.heightField = frame == null || !frame.blocks()
			&& (player == null || !dev.rehan.passthrough.WorldBridge.minedNear(player.getX(), player.getY(), player.getZ(), 2));
	}
}
