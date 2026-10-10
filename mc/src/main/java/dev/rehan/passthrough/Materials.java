package dev.rehan.passthrough;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What the host's map is made of. The host's ground is invisible blocks here; each stands for an ordinary block
 * (dirt under a meadow, logs in a tree, stone bricks in a dungeon), which is what it mines like, what it drops and
 * what shows once the cell next to it has been dug out. The host says what the surface of each column is
 * ({"t":"mats","m":[x,z,material,...]}); what lies under it follows as in a Minecraft world: soil, then stone
 * with ore in it. A host that says nothing is all stone, as before.
 */
public final class Materials {
	public static final int STONE = 0, DIRT = 1, GRASS = 2, SAND = 3, WOOD = 4, PLANKS = 5, BRICKS = 6, ICE = 7, SNOW = 8,
		SANDSTONE = 9, GRAVEL = 10, IRON = 11, GLASS = 12, MAGMA = 13, METAL = 14, MARBLE = 15;
	/** Added to a material: the column is that all the way down, not just at its surface. */
	public static final int SOLID = 0x100;
	/** Column key -> material of its surface, and -> the block y of its top cell (for how deep a cell is). */
	private static final ConcurrentHashMap<Long, Integer> SURFACE = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<Long, Integer> TOP = new ConcurrentHashMap<>();
	/** Column key -> the block y of its lowest cell: the bottom of the world there. */
	private static final ConcurrentHashMap<Long, Integer> BOTTOM = new ConcurrentHashMap<>();
	/** Single cells with a material of their own: things standing on the map (a tree's trunk). */
	private static final ConcurrentHashMap<Long, Integer> CELLS = new ConcurrentHashMap<>();

	private Materials() {
	}

	private static long key(final int x, final int z) {
		return ((long)x << 32) ^ (z & 0xFFFFFFFFL);
	}

	public static void surface(final int[] list) {
		for (int i = 0; i + 2 < list.length; i += 3) {
			SURFACE.put(key(list[i], list[i + 1]), list[i + 2]);
		}
	}

	/** {x, z, floor8, ceil8, lo, hi, ...} as WorldBridge.columns takes them: where each column's top cell is. */
	static void columns(final int[] list) {
		for (int i = 0; i + 5 < list.length; i += 6) {
			if (list[i + 4] < list[i + 5]) {
				TOP.put(key(list[i], list[i + 1]), Math.floorDiv(list[i + 2] - 1, 8));
				BOTTOM.put(key(list[i], list[i + 1]), list[i + 4]);
			} else {
				TOP.remove(key(list[i], list[i + 1]));
				BOTTOM.remove(key(list[i], list[i + 1]));
			}
		}
	}

	static void cell(final BlockPos pos, final int material) {
		CELLS.put(pos.asLong(), material);
	}

	static void forgetCell(final BlockPos pos) {
		CELLS.remove(pos.asLong());
	}

	public static boolean isCell(final BlockPos pos) {
		return CELLS.containsKey(pos.asLong());
	}

	static void clear() {
		SURFACE.clear();
		TOP.clear();
		BOTTOM.clear();
		CELLS.clear();
	}

	/** The same cell always has the same thing in it. */
	private static int roll(final BlockPos pos) {
		long h = pos.getX() * 73856093L ^ pos.getY() * 19349663L ^ pos.getZ() * 83492791L;
		h ^= h >>> 13;
		h *= 0x5DEECE66DL;
		return (int)((h >>> 16) % 1000 + 1000) % 1000;
	}

	/** Stone, or what is sometimes found in it. More, and better, in worked stone (a dungeon's walls) and deeper down. */
	private static BlockState stone(final BlockPos pos, final boolean rich, final BlockState plain) {
		int r = roll(pos);
		if (r < 60) {
			return Blocks.COAL_ORE.defaultBlockState();
		} else if (r < (rich ? 130 : 100)) {
			return Blocks.IRON_ORE.defaultBlockState();
		} else if (r < (rich ? 150 : 110)) {
			return Blocks.COPPER_ORE.defaultBlockState();
		} else if (r < (rich ? 175 : 115)) {
			return Blocks.GOLD_ORE.defaultBlockState();
		} else if (r < (rich ? 195 : 120)) {
			return Blocks.REDSTONE_ORE.defaultBlockState();
		} else if (r < (rich ? 205 : 123)) {
			return Blocks.LAPIS_ORE.defaultBlockState();
		} else if (r < (rich ? 215 : 124)) {
			return Blocks.DIAMOND_ORE.defaultBlockState();
		}

		return plain;
	}

	/** Something made of metal (a base's plating, its doors and machines): iron in every fifth cell, the rest stone. */
	private static BlockState metal(final BlockPos pos) {
		int r = roll(pos);
		return r < 200 ? Blocks.IRON_ORE.defaultBlockState() : r < 240 ? Blocks.COPPER_ORE.defaultBlockState()
			: r < 255 ? Blocks.REDSTONE_ORE.defaultBlockState() : Blocks.STONE.defaultBlockState();
	}

	/**
	 * What a cell of the host's map is to someone digging it with this tool. The map's cells cannot be seen before
	 * they are dug: a cell with ore in it that the tool is too poor for (iron, to a wooden pickaxe) would be a piece
	 * of wall that for no reason to be seen takes four times as long and then gives nothing. To such a tool the cell
	 * is plain stone, and gives cobblestone. (The ore that shows in the walls of a hole, once one is dug, is ore, and
	 * wants the tool ore wants.)
	 */
	public static BlockState at(final BlockPos pos, final net.minecraft.world.item.ItemStack tool) {
		BlockState made = at(pos);
		if (made.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(made)
			&& net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(made.getBlock()).getPath().endsWith("_ore")) {
			return Blocks.STONE.defaultBlockState();
		}

		return made;
	}

	/** The ordinary block the host's map is at pos. */
	public static BlockState at(final BlockPos pos) {
		Integer cell = CELLS.get(pos.asLong());
		long column = key(pos.getX(), pos.getZ());
		int material = cell != null ? cell : SURFACE.getOrDefault(column, STONE);
		Integer top = TOP.get(column);
		Integer bottom = BOTTOM.get(column);
		if (cell == null && bottom != null && pos.getY() <= bottom) {
			// The lowest cell the host's map has here is the bottom of the world, as in Minecraft: it cannot be dug.
			// (It could, and whoever dug straight down was through the map in a few seconds and hung in the void.)
			return Blocks.BEDROCK.defaultBlockState();
		}

		int depth = cell != null || top == null ? 0 : Math.max(0, top - pos.getY());
		if ((material & SOLID) != 0) {
			// the same all the way through (a wall, a cliff, a trunk): no soil over stone here
			material &= ~SOLID;
			depth = 0;
			if (material == METAL) {
				return metal(pos);
			} else if (material == MARBLE) {
				return stone(pos, true, Blocks.DEEPSLATE.defaultBlockState());
			}

			if (material == STONE) {
				return stone(pos, false, Blocks.STONE.defaultBlockState());
			} else if (material == BRICKS) {
				return stone(pos, true, Blocks.STONE_BRICKS.defaultBlockState());
			}
		}

		switch (material) {
			case GRASS:
				return depth == 0 ? Blocks.GRASS_BLOCK.defaultBlockState() : depth <= 2 ? Blocks.DIRT.defaultBlockState()
					: stone(pos, false, Blocks.STONE.defaultBlockState());
			case DIRT:
				return depth <= 2 ? Blocks.DIRT.defaultBlockState() : stone(pos, false, Blocks.STONE.defaultBlockState());
			case SAND:
				return depth <= 1 ? Blocks.SAND.defaultBlockState() : depth <= 3 ? Blocks.SANDSTONE.defaultBlockState()
					: stone(pos, false, Blocks.STONE.defaultBlockState());
			case GRAVEL:
				return depth <= 1 ? Blocks.GRAVEL.defaultBlockState() : stone(pos, false, Blocks.STONE.defaultBlockState());
			case SNOW:
				return depth == 0 ? Blocks.SNOW_BLOCK.defaultBlockState() : depth <= 2 ? Blocks.DIRT.defaultBlockState()
					: stone(pos, false, Blocks.STONE.defaultBlockState());
			case WOOD:
				return Blocks.OAK_LOG.defaultBlockState();
			case PLANKS:
				return depth == 0 ? Blocks.OAK_PLANKS.defaultBlockState() : depth <= 2 ? Blocks.DIRT.defaultBlockState()
					: stone(pos, false, Blocks.STONE.defaultBlockState());
			case BRICKS:
				return depth == 0 ? Blocks.STONE_BRICKS.defaultBlockState() : stone(pos, true, Blocks.STONE.defaultBlockState());
			case ICE:
				return depth <= 1 ? Blocks.PACKED_ICE.defaultBlockState() : stone(pos, false, Blocks.STONE.defaultBlockState());
			case SANDSTONE:
				return depth <= 3 ? Blocks.SANDSTONE.defaultBlockState() : stone(pos, false, Blocks.STONE.defaultBlockState());
			case IRON:
				return depth == 0 ? Blocks.IRON_ORE.defaultBlockState() : stone(pos, true, Blocks.STONE.defaultBlockState());
			case GLASS:
				return depth == 0 ? Blocks.GLASS.defaultBlockState() : stone(pos, false, Blocks.STONE.defaultBlockState());
			case METAL:
				return depth <= 1 ? metal(pos) : stone(pos, true, Blocks.STONE.defaultBlockState());
			case MARBLE:
				return stone(pos, true, Blocks.DEEPSLATE.defaultBlockState());
			case MAGMA:
				return depth == 0 ? Blocks.MAGMA_BLOCK.defaultBlockState() : stone(pos, true, Blocks.BLACKSTONE.defaultBlockState());
			default:
				return depth == 0 ? Blocks.STONE.defaultBlockState() : stone(pos, false, Blocks.STONE.defaultBlockState());
		}
	}
}
