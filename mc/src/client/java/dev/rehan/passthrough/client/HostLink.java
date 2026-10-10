package dev.rehan.passthrough.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.rehan.passthrough.MobWar;
import dev.rehan.passthrough.Nether;
import dev.rehan.passthrough.Passthrough;
import dev.rehan.passthrough.Vitals;
import dev.rehan.passthrough.WorldBridge;
import java.net.InetSocketAddress;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * The host's connection: a WebSocket server on 127.0.0.1 (port 25599, or -Dpassthrough.port).
 *
 * <p>Host to Minecraft (JSON, Minecraft coordinates):
 * <ul>
 * <li>{"t":"cam","f":frame,"p":[x,y,z],"r":[yaw,pitch,roll],"fov":vertical degrees,"fp":first person,"pl":[feet x,y,z],"h":body yaw}</li>
 * <li>{"t":"ground","c":[x,z,yBottom,yTop, ...]}: solid columns (barriers)</li>
 * <li>{"t":"cols","c":[x,z,floor8,ceil8,lo,hi, ...]}: columns of the host's map (see WorldBridge.columns)</li>
 * <li>{"t":"reset"}: a new map: remove the columns, the builds and the entities</li>
 * <li>{"t":"hp","h":health,"a":armour}: the host player's health and armour (the hearts and armour bar show them)</li>
 * <li>{"t":"mouse","x":px,"y":px}, {"t":"click","b":0-2,"down":bool,"mods":bits}: the cursor on an open screen</li>
 * <li>{"t":"clear"}: remove the barriers placed so far</li>
 * <li>{"t":"cmd","c":"time set noon"}: a server command</li>
 * <li>{"t":"key","k":"use|attack|pick|inventory|drop|swap|escape","down":bool}</li>
 * <li>{"t":"slot","n":0-8}, {"t":"scroll","d":+-1}, {"t":"hud","hidden":bool}, {"t":"view","w":px,"h":px}</li>
 * </ul>
 * Minecraft to host: {"t":"hello",...} on connect, {"t":"explosion","pos":[x,y,z],"r":radius}.
 * Relayed unchanged to the other clients: {"t":"gta",...} (director commands for the host plugin), {"t":"gtastate",...}.
 */
public final class HostLink extends WebSocketServer {
	private static HostLink instance;

	private HostLink(final int port) {
		super(new InetSocketAddress("127.0.0.1", port));
		this.setReuseAddr(true);
		this.setDaemon(true);
	}

	static void launch() {
		int port = Integer.getInteger("passthrough.port", 25599);
		instance = new HostLink(port);
		instance.start();
		Passthrough.events = message -> instance.broadcast(message);
		Passthrough.redraw = pos -> {
			net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
			minecraft.execute(() -> {
				if (minecraft.level != null) {
					minecraft.level.setSectionDirtyWithNeighbors(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
				}
			});
		};
	}

	@Override
	public void onStart() {
		Passthrough.LOG.info("host link listening on 127.0.0.1:{}", this.getPort());
	}

	@Override
	public void onOpen(final WebSocket conn, final ClientHandshake handshake) {
		Passthrough.LOG.info("host connected from {}", conn.getRemoteSocketAddress());
		conn.send(String.format(Locale.ROOT, "{\"t\":\"hello\",\"v\":1,\"shm\":\"%s\",\"pid\":%d}", FrameExporter.NAME.replace("\\", "\\\\"), ProcessHandle.current().pid()));
	}

	@Override
	public void onClose(final WebSocket conn, final int code, final String reason, final boolean remote) {
		Passthrough.LOG.info("host disconnected ({} {})", code, reason);
		WorldBridge.saveKept();
		BlockExport.hostCan = false;
		PassthroughClient.hostCursor = false;
		Skins.host("");
	}

	@Override
	public void onMessage(final WebSocket conn, final String message) {
		try {
			JsonObject m = JsonParser.parseString(message).getAsJsonObject();
			switch (m.get("t").getAsString()) {
				case "cam" -> HostState.update(m);
				case "skin" -> Skins.host(m.has("k") ? m.get("k").getAsString() : "");
				case "caps" -> {
					BlockExport.hostCan = m.has("cubes") && m.get("cubes").getAsBoolean();
					PassthroughClient.hostCursor = m.has("cursor") && m.get("cursor").getAsBoolean();
				}
				case "ground" -> WorldBridge.solid(ints(m.getAsJsonArray("c")));
				case "cols" -> WorldBridge.columns(ints(m.getAsJsonArray("c")));
				case "reset" -> WorldBridge.reset();
				case "minedsync" -> WorldBridge.minedSync(m.get("x0").getAsInt(), m.get("x1").getAsInt());
				case "scene" -> Passthrough.LOG.info("host scene {}", m.has("id") ? m.get("id").getAsInt() : -1);
				case "mats" -> dev.rehan.passthrough.Materials.surface(ints(m.getAsJsonArray("m")));
				case "props" -> WorldBridge.props(ints(m.getAsJsonArray("p")));
				case "hp" -> {
					Vitals.host(m.get("h").getAsInt(), m.has("a") ? m.get("a").getAsInt() : 0);
					Vitals.hostPoints(m.has("pts") ? m.get("pts").getAsFloat() : -1.0F, m.has("max") ? m.get("max").getAsFloat() : -1.0F);
					WorldBridge.naturalSpawns = !m.has("sp") || m.get("sp").getAsInt() != 0;
					Vitals.hunger(!m.has("food") || m.get("food").getAsInt() != 0);
				}
				case "clear" -> WorldBridge.clearSolid();
				case "cmd" -> WorldBridge.command(m.get("c").getAsString());
				case "newgame" -> PassthroughClient.newGame();
				case "save" -> WorldBridge.saveGame(m.get("id").getAsString(), m.has("carried") && m.get("carried").getAsBoolean());
				case "load" -> WorldBridge.loadGame(m.get("id").getAsString());
				case "savedel" -> WorldBridge.deleteGame(m.get("id").getAsString());
				case "wall" -> {
					JsonArray at = m.getAsJsonArray("p"), n = m.getAsJsonArray("n");
					dev.rehan.passthrough.Walls.wall(at.get(0).getAsDouble(), at.get(1).getAsDouble(), n.get(0).getAsDouble(), n.get(1).getAsDouble());
				}
				case "gta", "gtastate", "gtainfo", "director" -> this.relay(conn, message);
				case "blocksync" -> WorldBridge.sync(m.has("r") ? m.get("r").getAsInt() : 48);
				case "projhit" -> {
					JsonArray at = m.getAsJsonArray("pos");
					WorldBridge.projectileHit(m.get("id").getAsInt(), at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble(),
						m.has("stick") && m.get("stick").getAsBoolean());
				}
				case "peds" -> {
					JsonArray list = m.getAsJsonArray("p");
					double[] flat = new double[list.size() * MobWar.PED];
					for (int i = 0; i < list.size(); i++) {
						JsonArray e = list.get(i).getAsJsonArray();
						for (int k = 0; k < MobWar.PED; k++) {
							// [handle, x, y, z, radius]: hosts that don't send a radius get a person's
							flat[i * MobWar.PED + k] = k < e.size() ? e.get(k).getAsDouble() : 0.5;
						}
					}

					MobWar.peds(flat);
				}
				case "mobdmg" -> MobWar.damage(m.get("id").getAsInt(), m.get("d").getAsDouble());
				case "spawnmobs" -> MobWar.spawn(m.get("k").getAsString(), m.has("n") ? m.get("n").getAsInt() : 5,
					m.has("rmin") ? m.get("rmin").getAsDouble() : 8.0, m.has("rmax") ? m.get("rmax").getAsDouble() : 16.0,
					m.has("arc") ? m.get("arc").getAsDouble() : 40.0, m.has("yaw") ? m.get("yaw").getAsDouble() : 0.0,
					m.has("at") ? new double[] {m.getAsJsonArray("at").get(0).getAsDouble(), m.getAsJsonArray("at").get(1).getAsDouble(),
						m.getAsJsonArray("at").get(2).getAsDouble()} : null);
				case "mobsclear" -> MobWar.clearMobs();
				case "portal" -> {
					JsonArray at = m.getAsJsonArray("at");
					Nether.buildPortal(at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble(), m.get("yaw").getAsFloat());
				}
				case "netheroff" -> Nether.stop();
				case "nethersync" -> Nether.resync();
				case "glide" -> WorldBridge.glide(!m.has("on") || m.get("on").getAsBoolean(), m.has("speed") ? m.get("speed").getAsDouble() : 1.2);
				default -> {
					Minecraft minecraft = Minecraft.getInstance();
					minecraft.execute(() -> ClientInput.handle(minecraft, m));
				}
			}
		} catch (RuntimeException e) {
			Passthrough.LOG.warn("bad host message {}: {}", message.length() > 200 ? message.substring(0, 200) : message, e.toString());
		}
	}

	/** Messages between the host plugin and a director script pass through Minecraft's link to every other client. */
	private void relay(final WebSocket from, final String message) {
		for (WebSocket c : this.getConnections()) {
			if (c != from && c.isOpen()) {
				c.send(message);
			}
		}
	}

	@Override
	public void onError(final WebSocket conn, final Exception e) {
		Passthrough.LOG.warn("host link error", e);
	}

	private static int[] ints(final JsonArray a) {
		int[] out = new int[a.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = a.get(i).getAsInt();
		}

		return out;
	}
}
