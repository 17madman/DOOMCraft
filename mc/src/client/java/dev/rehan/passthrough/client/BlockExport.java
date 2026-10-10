package dev.rehan.passthrough.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.rehan.passthrough.GroundBlock;
import dev.rehan.passthrough.Passthrough;
import dev.rehan.passthrough.RockBlock;
import dev.rehan.passthrough.client.mixin.SpriteContentsAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Blocks drawn by the host itself (what SkyCraft does, in its first, small form). A block in Minecraft's picture is
 * always a frame or two behind the host's camera and has to be carried onto it; a block the host draws is simply
 * part of its world. So when everything built near the player is plain full cubes, the host is given the cubes
 * and their textures ("cubes", "cubetex") and Minecraft leaves its own terrain out of its picture
 * ({@link #hostDraws()}). As soon as something else is nearby (a torch, a door, glass, a chest) it all goes back
 * to Minecraft's picture, which can draw anything.
 *
 * <p>{"t":"cubetex","i":type,"side":hex,"top":hex,"bottom":hex}: three 16 x 16 RGBA images, rows top-down.
 * <br>{"t":"cubes","native":bool,"b":[x,y,z,type, ...]}: every cube near the player (replaces the last list).
 */
public final class BlockExport {
	/** Chunks around the player's and sections above and below his that are looked through (7 x 7 chunks: 56 blocks out). */
	private static final int CHUNKS = 3, SECTIONS = 3, MOST = 6000, SIZE = 16;
	private static final Map<BlockState, Integer> TYPES = new HashMap<>();
	private static final RandomSource RANDOM = RandomSource.create();
	private static int ticks;
	private static int lastHash;
	private static boolean lastNative;
	private static boolean sentOnce;
	private static volatile boolean hostDraws;
	private static boolean wasActive;
	/** {"t":"caps","cubes":true}: this host draws cubes itself. Without it Minecraft keeps drawing its own blocks. */
	public static volatile boolean hostCan;

	private BlockExport() {
	}

	/** True while the host draws the blocks: Minecraft's own terrain is left out of its picture. */
	public static boolean hostDraws() {
		return hostDraws && Passthrough.active;
	}

	/** Every client tick. */
	static void tick(final Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (!hostCan || !Passthrough.active || player == null || level == null || Boolean.getBoolean("passthrough.pictureBlocks")) {
			hostDraws = false;
			wasActive = false;
			return;
		}

		// a host that has just attached (or attached again) knows nothing yet; and everything is said again now and
		// then, in case it missed something
		if (!wasActive || ++ticks % 400 == 0) {
			TYPES.clear();
			sentOnce = false;
		}

		wasActive = true;
		if (ticks % 3 != 0 && sentOnce) {
			return;
		}

		// By chunk section, and only the sections that hold something besides air and the host's ground (the palette
		// says so without looking at every block): far cheaper than walking the whole volume, so it can reach far.
		BlockPos centre = player.blockPosition();
		int chunkX = centre.getX() >> 4, chunkZ = centre.getZ() >> 4, sectionY = centre.getY() >> 4;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		ArrayList<Integer> cubes = new ArrayList<>();
		boolean plain = true;
		int hash = 17;
		scan:
		for (int cx = chunkX - CHUNKS; cx <= chunkX + CHUNKS; cx++) {
			for (int cz = chunkZ - CHUNKS; cz <= chunkZ + CHUNKS; cz++) {
				var chunk = level.getChunk(cx, cz);
				LevelChunkSection[] sections = chunk.getSections();
				for (int sy = sectionY - SECTIONS; sy <= sectionY + SECTIONS; sy++) {
					int index = level.getSectionIndexFromSectionY(sy);
					if (index < 0 || index >= sections.length || sections[index] == null || sections[index].hasOnlyAir()
						|| !sections[index].maybeHas(BlockExport::drawn)) {
						continue;
					}

					LevelChunkSection section = sections[index];
					for (int y = 0; y < 16; y++) {
						for (int z = 0; z < 16; z++) {
							for (int x = 0; x < 16; x++) {
								BlockState state = section.getBlockState(x, y, z);
								if (!drawn(state)) {
									continue;
								}

								pos.set((cx << 4) + x, (sy << 4) + y, (cz << 4) + z);
								int type = state.getRenderShape() == RenderShape.MODEL && state.isCollisionShapeFullBlock(level, pos) && state.getFluidState().isEmpty()
									? type(minecraft, state) : -1;
								if (type < 0 || cubes.size() >= MOST * 4 || dev.rehan.passthrough.WorldBridge.isLining(pos)) {
									plain = false;
									break scan;
								}

								cubes.add(pos.getX());
								cubes.add(pos.getY());
								cubes.add(pos.getZ());
								cubes.add(type);
								hash = hash * 31 + pos.getX();
								hash = hash * 31 + pos.getY();
								hash = hash * 31 + pos.getZ();
								hash = hash * 31 + type;
							}
						}
					}
				}
			}
		}

		if (!plain) {
			cubes.clear();
			hash = 0;
		}

		hostDraws = plain && !cubes.isEmpty();
		if (sentOnce && hash == lastHash && plain == lastNative) {
			return;
		}

		lastHash = hash;
		lastNative = plain;
		sentOnce = true;
		StringBuilder b = new StringBuilder(64 + cubes.size() * 5).append("{\"t\":\"cubes\",\"native\":").append(plain).append(",\"b\":[");
		for (int i = 0; i < cubes.size(); i++) {
			if (i > 0) {
				b.append(',');
			}

			b.append(cubes.get(i).intValue());
		}

		Passthrough.events.accept(b.append("]}").toString());
	}

	/** Something Minecraft would draw as a block: not air, not the host's own (invisible) ground. */
	private static boolean drawn(final BlockState state) {
		return !state.isAir() && !GroundBlock.is(state) && !RockBlock.is(state) && state.getRenderShape() != RenderShape.INVISIBLE;
	}

	/** The host's number for a block state's look, telling it the textures the first time; -1 if it has no plain cube look. */
	private static int type(final Minecraft minecraft, final BlockState state) {
		Integer known = TYPES.get(state);
		if (known != null) {
			return known;
		}

		int type = -1;
		try {
			var model = minecraft.getModelManager().getBlockStateModelSet().get(state);
			ArrayList<BlockStateModelPart> parts = new ArrayList<>();
			RANDOM.setSeed(42L);
			model.collectParts(RANDOM, parts);
			Direction[] faces = {Direction.NORTH, Direction.UP, Direction.DOWN};
			String[] hex = new String[3];
			int tint = tint(minecraft, state);
			for (int f = 0; f < 3; f++) {
				TextureAtlasSprite sprite = null;
				for (BlockStateModelPart part : parts) {
					var quads = part.getQuads(faces[f]);
					if (!quads.isEmpty()) {
						sprite = quads.getFirst().materialInfo().sprite();
						break;
					}
				}

				hex[f] = sprite == null ? null : pixels(sprite, tint);
			}

			if (hex[0] != null && hex[1] != null && hex[2] != null) {
				type = TYPES.size();
				Passthrough.events.accept("{\"t\":\"cubetex\",\"i\":" + type + ",\"side\":\"" + hex[0] + "\",\"top\":\"" + hex[1] + "\",\"bottom\":\"" + hex[2] + "\"}");
			}
		} catch (RuntimeException e) {
			Passthrough.LOG.warn("no cube textures for {}: {}", state, e.toString());
		}

		TYPES.put(state, type);
		return type;
	}

	/** The block's tint (grass, leaves) as 0xRRGGBB, or -1. */
	private static int tint(final Minecraft minecraft, final BlockState state) {
		var sources = minecraft.getBlockColors().getTintSources(state);
		if (sources.isEmpty() || sources.getFirst() == null) {
			return -1;
		}

		return sources.getFirst().color(state) & 0xFFFFFF;
	}

	/** A sprite's first frame as 16 x 16 RGBA, in hex. Grey pixels take the tint (they are the ones Minecraft tints). */
	private static String pixels(final TextureAtlasSprite sprite, final int tint) {
		NativeImage image = ((SpriteContentsAccessor) sprite.contents()).passthrough$originalImage();
		int w = Math.min(sprite.contents().width(), image.getWidth()), h = Math.min(sprite.contents().height(), image.getHeight());
		StringBuilder b = new StringBuilder(SIZE * SIZE * 8);
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				int argb = image.getPixel(x * w / SIZE, y * h / SIZE);
				int a = argb >>> 24, r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, bl = argb & 0xFF;
				if (tint >= 0 && Math.abs(r - g) < 10 && Math.abs(g - bl) < 10) {
					r = r * (tint >> 16 & 0xFF) / 255;
					g = g * (tint >> 8 & 0xFF) / 255;
					bl = bl * (tint & 0xFF) / 255;
				}

				b.append(HEX[r >> 4]).append(HEX[r & 15]).append(HEX[g >> 4]).append(HEX[g & 15])
					.append(HEX[bl >> 4]).append(HEX[bl & 15]).append(HEX[a >> 4]).append(HEX[a & 15]);
			}
		}

		return b.toString();
	}

	private static final char[] HEX = "0123456789abcdef".toCharArray();
}
