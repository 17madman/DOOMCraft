package dev.rehan.passthrough;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Server-side half: the host's collision as invisible barrier blocks, commands, and events back to the host. */
public final class WorldBridge {
	private static volatile MinecraftServer server;
	/** Barriers we placed (so a reset only removes ours, never the player's builds). */
	private static final Set<BlockPos> barriers = ConcurrentHashMap.newKeySet();
	/** Block changes this tick (server thread only): true = now solid, false = gone. Sent at the end of the tick. */
	private static final Map<BlockPos, Boolean> changes = new LinkedHashMap<>();
	/** While placing or removing the host's own ground: those changes aren't news to the host (server thread only). */
	private static boolean placingGround;

	private WorldBridge() {
	}

	private static boolean minedDirty;
	private static int minedSaveIn;

	private static java.nio.file.Path minedFile(final MinecraftServer s) {
		return s.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("passthrough-mined.txt");
	}

	/** The cells mined out of the host's map are part of a world that is kept: one "x y z" a line beside it. */
	private static void loadMined(final MinecraftServer s) {
		java.nio.file.Path file = minedFile(s);
		if (!Passthrough.keepsWorld() || !java.nio.file.Files.exists(file)) {
			return;
		}

		try {
			for (String line : java.nio.file.Files.readAllLines(file)) {
				String[] n = line.trim().split(" ");
				if (n.length == 3) {
					BlockPos pos = new BlockPos(Integer.parseInt(n[0]), Integer.parseInt(n[1]), Integer.parseInt(n[2]));
					mined.add(pos);
					minedSeen.add(pos.asLong());
				}
			}

			Passthrough.LOG.info("{} mined cells from last time", mined.size());
		} catch (java.io.IOException | NumberFormatException e) {
			Passthrough.LOG.warn("couldn't read {}", file, e);
		}
	}

	private static void saveMined(final MinecraftServer s) {
		if (!minedDirty || !Passthrough.keepsWorld() || s == null) {
			return;
		}

		minedDirty = false;
		StringBuilder b = new StringBuilder(mined.size() * 12);
		for (BlockPos pos : mined) {
			b.append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ()).append('\n');
		}

		try {
			java.nio.file.Files.writeString(minedFile(s), b.toString());
		} catch (java.io.IOException e) {
			Passthrough.LOG.warn("couldn't save the mined cells", e);
		}
	}

	/**
	 * The host has come to a part of the world (again): every cell mined out between these x, for it to open its
	 * own world there. {"t":"minedsync","x0":..,"x1":..}
	 */
	public static void minedSync(final int x0, final int x1) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			StringBuilder b = null;
			int n = 0;
			for (BlockPos pos : mined) {
				if (pos.getX() < x0 || pos.getX() >= x1) {
					continue;
				}

				b = b == null ? new StringBuilder("{\"t\":\"mined\",\"set\":[") : b.append(',');
				b.append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
				if (++n >= 400) {
					Passthrough.events.accept(b.append("]}").toString());
					b = null;
					n = 0;
				}
			}

			if (b != null) {
				Passthrough.events.accept(b.append("]}").toString());
			}
		});
	}

	static void attach(final MinecraftServer s) {
		server = s;
		loadMined(s);
	}

	static void detach() {
		saveMined(server);
		server = null;
		Vitals.detach();
		barriers.clear();
		columns.clear();
		built.clear();
		mined.clear();
		minedNews.clear();
		minedSeen.clear();
		liningCells.clear();
		props.clear();
		Materials.clear();
	}

	public static boolean ready() {
		return server != null;
	}

	static MinecraftServer server() {
		return server;
	}

	/** Columns of solid ground from the host: {x, z, yBottom, yTop, ...} in block coordinates (inclusive). Only air is replaced. */
	public static void solid(final int[] columns) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			ServerLevel level = s.overworld();
			BlockState barrier = Blocks.BARRIER.defaultBlockState();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			placingGround = true;
			for (int i = 0; i + 3 < columns.length; i += 4) {
				for (int y = columns[i + 2]; y <= columns[i + 3]; y++) {
					pos.set(columns[i], y, columns[i + 1]);
					if (level.isInWorldBounds(pos) && level.getBlockState(pos).isAir()) {
						level.setBlock(pos, barrier, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
						barriers.add(pos.immutable());
					}
				}
			}

			placingGround = false;
		});
	}

	/** "No ceiling" in a column (open sky). */
	private static final int NONE = 32767;
	/** The host's map as we last built it, by column: {floor8, ceil8, lo, hi} (server thread only). */
	private static final Map<Long, int[]> columns = new HashMap<>();
	/** Columns cleared of the last session's map blocks (server thread only; for as long as Minecraft runs). */
	private static final Set<Long> swept = new HashSet<>();
	/** What the player (or a mob, or an explosion) has put in the world since the last reset (server thread only). */
	private static final Set<BlockPos> built = new HashSet<>();

	/** Cells of the host's map to put back at the end of the tick (server thread only). */
	private static final Set<BlockPos> restore = new HashSet<>();

	private static void restoreGround(final ServerLevel level) {
		if (restore.isEmpty()) {
			return;
		}

		placingGround = true;
		for (BlockPos pos : restore) {
			BlockState want = mapState(pos);
			if (want != null && level.getBlockState(pos).isAir()) {
				level.setBlock(pos, want, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}
		}

		placingGround = false;
		restore.clear();
	}

	/** Cells of the host's map the player has mined out (server thread only): the map never comes back there. */
	private static final Set<BlockPos> mined = new HashSet<>();
	/** Mined cells not yet told to the host (server thread only). */
	private static final Set<BlockPos> minedNews = new HashSet<>();

	/** The same, for the client's thread to look at (collision goes by blocks, not the height field, near them). */
	private static final Set<Long> minedSeen = ConcurrentHashMap.newKeySet();
	/** Where the host's map shows as ordinary blocks: cells next to a mined-out one (any thread may ask). */
	private static final Set<Long> liningCells = ConcurrentHashMap.newKeySet();
	/** Cells of things standing on the host's map (a tree's trunk), from "props" (server thread only). */
	private static final Set<BlockPos> props = new HashSet<>();

	/** The host's map where a mined-out cell exposes it: the ordinary block it is made of there (the walls of the tunnel). */
	private static BlockState lining(final BlockPos pos) {
		return Materials.at(pos);
	}

	/** Is this visible block the host's own map, laid bare by mining (and not something built)? */
	public static boolean isLining(final BlockPos pos) {
		return liningCells.contains(pos.asLong());
	}

	/** Is any of the host's map showing as blocks at all? (Any thread; the cheap question first.) */
	public static boolean anyLining() {
		return !liningCells.isEmpty();
	}

	/** Has this cell of the host's map been mined out? (Any thread.) */
	public static boolean minedCell(final BlockPos pos) {
		return minedSeen.contains(pos.asLong());
	}

	/** Has anything of the host's map been mined out within `reach` blocks of here? (Any thread.) */
	public static boolean minedNear(final double x, final double y, final double z, final int reach) {
		if (minedSeen.isEmpty()) {
			return false;
		}

		int bx = (int)Math.floor(x), by = (int)Math.floor(y), bz = (int)Math.floor(z);
		for (int dy = -reach; dy <= reach + 1; dy++) {
			for (int dx = -reach; dx <= reach; dx++) {
				for (int dz = -reach; dz <= reach; dz++) {
					if (minedSeen.contains(BlockPos.asLong(bx + dx, by + dy, bz + dz))) {
						return true;
					}
				}
			}
		}

		return false;
	}

	/**
	 * Things standing on the host's map that can be mined like it: {x, y, z, height, material, ...}, each a column of
	 * cells. The whole list each time; what is no longer in it goes.
	 */
	public static void props(final int[] list) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			ServerLevel level = s.overworld();
			Set<BlockPos> now = new HashSet<>();
			placingGround = true;
			for (int i = 0; i + 4 < list.length; i += 5) {
				for (int h = 0; h < list[i + 3]; h++) {
					BlockPos pos = new BlockPos(list[i], list[i + 1] + h, list[i + 2]);
					now.add(pos);
					Materials.cell(pos, list[i + 4]);
					if (!mined.contains(pos) && level.isInWorldBounds(pos) && level.getBlockState(pos).isAir()) {
						level.setBlock(pos, RockBlock.state(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
					}
				}
			}

			// What is gone from the list. A thing that went because a piece was mined out of it (the host takes a tree
			// away when it is first cut into) leaves the rest of itself behind as ordinary blocks: the trunk above the
			// cut is logs standing in the air, to be chopped one by one, as a tree is in Minecraft.
			Map<BlockPos, BlockState> left = new HashMap<>();
			for (BlockPos pos : props) {
				if (!now.contains(pos)) {
					boolean felled = false;
					for (int dy = -6; dy <= 6 && !felled; dy++) {
						felled = mined.contains(pos.above(dy));
					}

					BlockState made = felled && !mined.contains(pos) ? Materials.at(pos) : null;
					Materials.forgetCell(pos);
					if (ours(level.getBlockState(pos), pos) && mapState(pos) == null) {
						liningCells.remove(pos.asLong());
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
						if (made != null && !RockBlock.is(made) && !GroundBlock.is(made)) {
							left.put(pos, made);
						}
					}
				}
			}

			props.clear();
			props.addAll(now);
			placingGround = false;
			// (as if built: they are the player's now, kept and shown like anything he puts down)
			for (Map.Entry<BlockPos, BlockState> e : left.entrySet()) {
				level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_ALL);
				built.add(e.getKey());
			}
		});
	}

	private static long columnKey(final int x, final int z) {
		return ((long)x << 32) ^ (z & 0xFFFFFFFFL);
	}

	/** Whether the block at pos is the host's map (not something built). */
	private static boolean ours(final BlockState state, final BlockPos pos) {
		return RockBlock.is(state) || GroundBlock.is(state) || liningCells.contains(pos.asLong()) && !built.contains(pos);
	}

	/** What the host's map is at pos: null (open or mined), invisible rock, a partial floor, or visible rock next to a mined cell. */
	private static BlockState mapState(final BlockPos pos) {
		if (mined.contains(pos)) {
			return null;
		}

		BlockState state = columnState(columns.get(columnKey(pos.getX(), pos.getZ())), pos.getY());
		if (state == null && Materials.isCell(pos)) {
			state = RockBlock.state();
		}

		if (state != null && RockBlock.is(state)) {
			for (Direction d : Direction.values()) {
				if (mined.contains(pos.relative(d))) {
					return lining(pos);
				}
			}
		}

		return state;
	}

	/** A cell of the host's map was broken: it stays open, and the rock around it shows. */
	private static void mine(final ServerLevel level, final BlockPos pos) {
		BlockPos cell = pos.immutable();
		mined.add(cell);
		minedDirty = true;
		minedSeen.add(cell.asLong());
		liningCells.remove(cell.asLong());
		minedNews.add(cell);
		placingGround = true;
		for (Direction d : Direction.values()) {
			BlockPos n = cell.relative(d);
			BlockState want = mapState(n);
			if (want != null && level.isInWorldBounds(n) && RockBlock.is(level.getBlockState(n)) && want != level.getBlockState(n)) {
				liningCells.add(n.asLong());
				level.setBlock(n, want, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}
		}

		placingGround = false;
	}

	/** What a column holds at block y: solid below the floor, a partial block where the floor is, solid above the ceiling. */
	private static BlockState columnState(final int[] c, final int y) {
		if (c == null || y < c[2] || y >= c[3]) {
			return null;
		}

		int floorBlock = Math.floorDiv(c[0], 8);
		if (y < floorBlock) {
			return RockBlock.state();
		}

		// a closed door or a crusher down: the floor reaches the ceiling
		boolean closed = c[1] != NONE && c[1] <= c[0];
		if (y == floorBlock && Math.floorMod(c[0], 8) != 0 && !closed) {
			return GroundBlock.layers(Math.floorMod(c[0], 8));
		}

		if (closed || c[1] != NONE && y >= Math.floorDiv(c[1] + 7, 8)) {
			return RockBlock.state();
		}

		return null;
	}

	/**
	 * Columns of the host's map: {x, z, floor8, ceil8, lo, hi, ...}. Heights are in eighths of a block for the floor
	 * and ceiling (ceil8 = 32767: open sky) and whole blocks for lo/hi: the column is solid from lo up to the floor
	 * and from the ceiling up to hi. A column sent again replaces what was there (doors, lifts); lo = hi removes it.
	 * Blocks that aren't ours (the player's builds) are left alone.
	 */
	public static void columns(final int[] list) {
		Ground.columns(list);
		Materials.columns(list);
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			ServerLevel level = s.overworld();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			placingGround = true;
			for (int i = 0; i + 5 < list.length; i += 6) {
				int x = list[i], z = list[i + 1];
				long key = ((long)x << 32) ^ (z & 0xFFFFFFFFL);
				int[] now = list[i + 4] < list[i + 5] ? new int[] {list[i + 2], list[i + 3], list[i + 4], list[i + 5]} : null;
				int[] old = now == null ? columns.remove(key) : columns.put(key, now);
				int from = Math.min(old != null ? old[2] : Integer.MAX_VALUE, now != null ? now[2] : Integer.MAX_VALUE);
				int to = Math.max(old != null ? old[3] : Integer.MIN_VALUE, now != null ? now[3] : Integer.MIN_VALUE);
				if (old == null && swept.add(key)) {
					// The first word of this column since Minecraft started. In a world that is kept, the map's own blocks
					// from the last time are still in it, where the map was then said to be (a wall as high as the player
					// stood, the top of a bridge taken for the ground): whatever of them is not the map as it is said
					// now goes. Left, they were blocks nobody could see, standing in the way and under the cursor.
					for (int y = level.getMinY(); y <= level.getMaxY(); y++) {
						if (now != null && y >= now[2] && y < now[3]) {
							continue;
						}

						pos.set(x, y, z);
						BlockState have = level.getBlockState(pos);
						if ((RockBlock.is(have) || GroundBlock.is(have)) && mapState(pos) == null) {
							level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
						}
					}
				}

				for (int y = from; y < to; y++) {
					if (columnState(now, y) == columnState(old, y) && old != null) {
						continue;
					}

					pos.set(x, y, z);
					if (!level.isInWorldBounds(pos)) {
						continue;
					}

					BlockState want = mapState(pos);
					BlockState have = level.getBlockState(pos);
					if (want != null && !RockBlock.is(want) && !GroundBlock.is(want) && liningCells.add(pos.asLong())) {
						// the map laid bare by a mined cell beside it (also one from last time: the block is in the kept
						// world already, drawn whole; only its sides into the hole are to be, see ModelBlockRendererMixin)
						Passthrough.redraw.accept(pos.immutable());
					}

					if (want == null ? ours(have, pos) : (have.isAir() || ours(have, pos)) && have != want) {
						level.setBlock(pos, want == null ? Blocks.AIR.defaultBlockState() : want, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
					}
				}
			}

			placingGround = false;
		});
	}

	/** A new map (or a new host): take away the old map's columns, everything built on it and everything that lived there. */
	public static void reset() {
		Ground.clear();
		Materials.clear();
		minedSeen.clear();
		liningCells.clear();
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			ServerLevel level = s.overworld();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			BlockState air = Blocks.AIR.defaultBlockState();
			placingGround = true;
			for (Map.Entry<Long, int[]> e : columns.entrySet()) {
				int x = (int)(e.getKey() >> 32), z = (int)(long)e.getKey();
				for (int y = e.getValue()[2]; y < e.getValue()[3]; y++) {
					pos.set(x, y, z);
					if (level.isInWorldBounds(pos) && ours(level.getBlockState(pos), pos)) {
						level.setBlock(pos, air, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
					}
				}
			}

			for (BlockPos p : built) {
				if (!level.getBlockState(p).isAir()) {
					level.setBlock(p, air, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				}
			}

			for (BlockPos p : props) {
				if (level.isInWorldBounds(p) && !level.getBlockState(p).isAir()) {
					level.setBlock(p, air, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				}
			}

			props.clear();
			liningCells.clear();
			minedSeen.clear();
			placingGround = false;
			columns.clear();
			built.clear();
			restore.clear();
			changes.clear();
			mined.clear();
			minedNews.clear();
			for (Entity e : level.getAllEntities()) {
				if (!(e instanceof Player)) {
					e.discard();
				}
			}
		});
	}

	// ---- saved games of the host
	//
	// The host's own saves hold its world; they know nothing of what the player has done in Minecraft's. Beside each
	// of them (by its name) Minecraft keeps what goes with it: what the player carries, what he has built in the map
	// he is in (chests with what is in them), and what he has mined out of it. Kept in the game folder, under
	// passthrough-saves: the world itself is made afresh at every start.

	private static java.nio.file.Path saveFile(final String id) {
		String name = id.replaceAll("[^A-Za-z0-9_.-]", "_");
		return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("passthrough-saves").resolve(name + ".nbt");
	}

	/** {"t":"save","id":name}: the host has saved its game under that name. */
	public static void saveGame(final String id, final boolean carriedOnly) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			try {
				ServerLevel level = s.overworld();
				net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
				// ("carried only": the map in Minecraft is still the one the host has just left)
				root.putLongArray("mined", carriedOnly ? new long[0] : mined.stream().mapToLong(BlockPos::asLong).toArray());

				net.minecraft.nbt.ListTag blocks = new net.minecraft.nbt.ListTag();
				for (BlockPos pos : carriedOnly ? Set.<BlockPos>of() : built) {
					BlockState state = level.getBlockState(pos);
					if (state.isAir()) {
						continue;
					}

					net.minecraft.nbt.CompoundTag block = new net.minecraft.nbt.CompoundTag();
					block.putLong("pos", pos.asLong());
					block.put("state", net.minecraft.nbt.NbtUtils.writeBlockState(state));
					net.minecraft.world.level.block.entity.BlockEntity entity = level.getBlockEntity(pos);
					if (entity != null) {
						block.put("entity", entity.saveWithFullMetadata(s.registryAccess()));
					}

					blocks.add(block);
				}

				root.put("blocks", blocks);
				if (!s.getPlayerList().getPlayers().isEmpty()) {
					ServerPlayer player = s.getPlayerList().getPlayers().get(0);
					var ops = s.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
					net.minecraft.nbt.ListTag items = new net.minecraft.nbt.ListTag();
					for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
						ItemStack stack = player.getInventory().getItem(slot);
						if (stack.isEmpty()) {
							continue;
						}

						final int at = slot;
						ItemStack.CODEC.encodeStart(ops, stack).result().ifPresent(tag -> {
							net.minecraft.nbt.CompoundTag item = new net.minecraft.nbt.CompoundTag();
							item.putInt("slot", at);
							item.put("item", tag);
							items.add(item);
						});
					}

					root.put("items", items);
					root.putInt("xpLevel", player.experienceLevel);
					root.putFloat("xpProgress", player.experienceProgress);
					root.putInt("xpTotal", player.totalExperience);
				}

				java.nio.file.Path file = saveFile(id);
				java.nio.file.Files.createDirectories(file.getParent());
				net.minecraft.nbt.NbtIo.writeCompressed(root, file);
				Passthrough.LOG.info("saved with the host's game {}: {} blocks built, {} cells mined", id, blocks.size(), mined.size());
			} catch (Exception e) {
				Passthrough.LOG.warn("couldn't save with the host's game {}", id, e);
			}
		});
	}

	/**
	 * {"t":"load","id":name}: the host has loaded that game (and has said "reset" just before: the map is empty and
	 * its columns are on their way). What went with it is put back. A game saved without Minecraft has nothing to
	 * put back: then things stay as they are.
	 */
	public static void loadGame(final String id) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			java.nio.file.Path file = saveFile(id);
			if (!java.nio.file.Files.exists(file)) {
				Passthrough.LOG.info("the host's game {} has nothing of Minecraft's saved with it", id);
				return;
			}

			try {
				ServerLevel level = s.overworld();
				net.minecraft.nbt.CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(file, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
				// what was mined: open before the map's columns are laid (they leave mined cells out), and told to the
				// host, which opens its own world there
				for (long cell : root.getLongArray("mined").orElse(new long[0])) {
					BlockPos pos = BlockPos.of(cell);
					mined.add(pos);
					minedSeen.add(cell);
					minedNews.add(pos);
				}

				// what was built
				var blockLookup = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK);
				net.minecraft.nbt.ListTag blocks = root.getListOrEmpty("blocks");
				for (net.minecraft.nbt.Tag tag : blocks) {
					if (!(tag instanceof net.minecraft.nbt.CompoundTag block)) {
						continue;
					}

					BlockPos pos = BlockPos.of(block.getLongOr("pos", 0L));
					BlockState state = net.minecraft.nbt.NbtUtils.readBlockState(blockLookup, block.getCompoundOrEmpty("state"));
					if (state.isAir() || !level.isInWorldBounds(pos)) {
						continue;
					}

					level.setBlock(pos, state, Block.UPDATE_ALL);
					built.add(pos);
					changes.put(pos, solidForHost(level, pos, state));
					if (block.contains("entity")) {
						net.minecraft.world.level.block.entity.BlockEntity entity = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos, state,
							block.getCompoundOrEmpty("entity"), s.registryAccess());
						if (entity != null) {
							level.setBlockEntity(entity);
						}
					}
				}

				// what he carried
				if (root.contains("items") && !s.getPlayerList().getPlayers().isEmpty()) {
					ServerPlayer player = s.getPlayerList().getPlayers().get(0);
					var ops = s.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
					player.getInventory().clearContent();
					for (net.minecraft.nbt.Tag tag : root.getListOrEmpty("items")) {
						if (tag instanceof net.minecraft.nbt.CompoundTag item && item.contains("item")) {
							int slot = item.getIntOr("slot", -1);
							if (slot >= 0 && slot < player.getInventory().getContainerSize()) {
								ItemStack.CODEC.parse(ops, item.get("item")).result().ifPresent(stack -> player.getInventory().setItem(slot, stack));
							}
						}
					}

					player.setExperienceLevels(root.getIntOr("xpLevel", 0));
					player.experienceProgress = root.getFloatOr("xpProgress", 0.0F);
					player.totalExperience = root.getIntOr("xpTotal", 0);
					player.inventoryMenu.broadcastChanges();
				}

				Passthrough.LOG.info("loaded with the host's game {}: {} blocks built, {} cells mined", id, blocks.size(), mined.size());
			} catch (Exception e) {
				Passthrough.LOG.warn("couldn't load what goes with the host's game {}", id, e);
			}
		});
	}

	/** {"t":"savedel","id":name}: the host has deleted that game. */
	public static void deleteGame(final String id) {
		try {
			java.nio.file.Files.deleteIfExists(saveFile(id));
		} catch (Exception e) {
			Passthrough.LOG.warn("couldn't delete what went with the host's game {}", id, e);
		}
	}

	/**
	 * What the player's swing does with what he holds. Asked of the server's player: the client's copy of this
	 * attribute is never told about the item in hand (Minecraft does not send it), and says 1, a bare fist, whatever
	 * he holds. (So every sword did a fist's damage to the host's creatures.)
	 */
	public static double attackDamage(final double otherwise) {
		MinecraftServer s = server;
		try {
			if (s != null && !s.getPlayerList().getPlayers().isEmpty()) {
				return s.getPlayerList().getPlayers().get(0).getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
			}
		} catch (RuntimeException e) {
			// (read from another thread, between ticks: the client's figure will do for once)
		}

		return otherwise;
	}

	/** Remove every barrier we placed (e.g. when the host teleports somewhere else). */
	public static void clearSolid() {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			ServerLevel level = s.overworld();
			placingGround = true;
			for (BlockPos pos : barriers) {
				if (level.getBlockState(pos).is(Blocks.BARRIER)) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				}
			}

			placingGround = false;
			barriers.clear();
		});
	}

	/** Run a command as the server (op). Results go to the log, not to chat (send_command_feedback is off). */
	public static void command(final String command) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			Passthrough.LOG.info("command: {}", command);
			s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), command);
		});
	}

	private static boolean solidForHost(final ServerLevel level, final BlockPos pos, final BlockState state) {
		// the Nether's ground is the host's own ground turned: nothing to collide with that isn't there already
		return !state.isAir() && !ours(state, pos) && !state.getCollisionShape(level, pos).isEmpty() && !Nether.isGround(pos);
	}

	/** Arrows the host already hit something with (they stay where they hit and aren't reported again). */
	private static final String HIT_TAG = "passthrough_hit";

	/**
	 * The host moves the player much faster than Minecraft's own pickup expects (a Doom marine runs 17 blocks a
	 * second), and drops land in mined-out holes in walls or a little inside the host's floor, out of the usual
	 * one-block reach. So anything lying within 2.5 blocks is picked up, as if touched.
	 */
	private static void collectItems(final MinecraftServer s) {
		if (s.getPlayerList().getPlayers().isEmpty()) {
			return;
		}

		ServerPlayer player = s.getPlayerList().getPlayers().get(0);
		if (!player.isAlive() || player.isSpectator()) {
			return;
		}

		for (Entity e : player.level().getEntities(player, player.getBoundingBox().inflate(2.5, 2.5, 2.5), e -> e instanceof ItemEntity)) {
			ItemEntity item = (ItemEntity)e;
			if (!item.isRemoved() && !item.hasPickUpDelay()) {
				item.playerTouch(player);
			}
		}
	}

	/** Whether mobs spawn by themselves (the host can turn it off: mc_spawns 0). */
	public static volatile boolean naturalSpawns = true;
	private static final String[] SPAWNS = {"zombie", "zombie", "zombie", "skeleton", "skeleton", "spider", "creeper", "creeper"};
	private static final int SPAWN_CAP = 3;
	private static int spawnIn = 900;

	/**
	 * Minecraft's own spawning needs darkness and blocks mobs can stand on, and the host's map is neither to it
	 * (always day, floors of invisible rock). So hostile mobs are put on the host's floors here: one every 45 to 90
	 * seconds, 10 to 28 blocks from the player, where there is room to stand, three at a time at most.
	 * They despawn like any other mob when the player is far away.
	 */
	private static void spawnMobs(final MinecraftServer s) {
		if (!naturalSpawns || --spawnIn > 0) {
			return;
		}

		ThreadLocalRandom random = ThreadLocalRandom.current();
		spawnIn = 900 + random.nextInt(900);
		if (columns.isEmpty() || s.getPlayerList().getPlayers().isEmpty()) {
			return;
		}

		ServerPlayer player = s.getPlayerList().getPlayers().get(0);
		ServerLevel level = s.overworld();
		int hostile = level.getEntities((Entity)null, player.getBoundingBox().inflate(48.0), e -> e instanceof Enemy && !MobWar.isProxy(e)).size();
		if (hostile >= SPAWN_CAP) {
			return;
		}

		int wanted = 1;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int attempt = 0; attempt < 24 && wanted > 0; attempt++) {
			double angle = random.nextDouble() * Math.PI * 2.0, r = 10.0 + random.nextDouble() * 18.0;
			int x = (int)Math.floor(player.getX() + Math.cos(angle) * r), z = (int)Math.floor(player.getZ() + Math.sin(angle) * r);
			int[] c = columns.get(columnKey(x, z));
			// a floor (not solid rock), with two blocks of room under the ceiling
			if (c == null || c[0] >= c[3] * 8 || c[1] != NONE && c[1] - c[0] < 17) {
				continue;
			}

			int y = Math.floorDiv(c[0] + 7, 8);
			pos.set(x, y, z);
			if (Math.abs(y - player.getY()) > 12 || !level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()
				|| level.getBlockState(pos.below()).isAir()) {
				continue;
			}

			Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.withDefaultNamespace(SPAWNS[random.nextInt(SPAWNS.length)]));
			if (type.isPresent() && type.get().spawn(level, pos.immutable(), EntitySpawnReason.NATURAL) != null) {
				wanted--;
			}
		}
	}

	/** Every server tick: block changes, and projectiles in flight for the host to trace through its own world. */
	private static int sinceSaved;

	/**
	 * A world that is kept is written out now (the player's things and what he has built), not only at Minecraft's own
	 * five minutes: Minecraft is hidden behind the host and is as often as not closed by ending it, or goes down with
	 * the host. Up to five minutes of what he had gathered was lost that way.
	 */
	public static void saveKept() {
		MinecraftServer s = server;
		if (s == null || !Passthrough.keepsWorld()) {
			return;
		}

		s.execute(() -> {
			sinceSaved = 0;
			s.getPlayerList().saveAll();
			s.saveAllChunks(true, false, false);
		});
	}

	static void tick(final MinecraftServer s) {
		if (Passthrough.keepsWorld() && Passthrough.active && ++sinceSaved >= 1200) {
			saveKept(); // once a minute while a host plays
		}

		restoreGround(s.overworld());
		flush(s);
		if (Passthrough.active) {
			reportProjectiles(s.overworld());
			collectItems(s);
			spawnMobs(s);
		}

		MobWar.tick(s);
		Nether.tick(s);
		Vitals.tick(s);
	}

	/**
	 * Steve's arrows (bow, crossbow) and crossbow fireworks in flight: {"t":"proj","p":[[id,kind,x,y,z],...]}. Mobs'
	 * arrows aren't traced by the host: they hit its people's proxies here.
	 */
	private static void reportProjectiles(final ServerLevel level) {
		StringBuilder b = null;
		for (Entity e : level.getAllEntities()) {
			String kind = null;
			if (!(e instanceof Projectile projectile) || !(projectile.getOwner() instanceof Player)) {
				continue;
			}

			if (e instanceof AbstractArrow arrow && !arrow.entityTags().contains(HIT_TAG) && arrow.getDeltaMovement().lengthSqr() > 1.0E-4) {
				kind = "arrow";
			} else if (e instanceof FireworkRocketEntity rocket && rocket.isShotAtAngle()) {
				kind = "firework"; // not the ones boosting an elytra flight
			}

			if (kind != null) {
				b = b == null ? new StringBuilder("{\"t\":\"proj\",\"p\":[") : b.append(',');
				b.append(String.format(Locale.ROOT, "[%d,\"%s\",%.3f,%.3f,%.3f]", e.getId(), kind, e.getX(), e.getY(), e.getZ()));
			}
		}

		if (b != null) {
			Passthrough.events.accept(b.append("]}").toString());
		}
	}

	/**
	 * The host traced a projectile into something of its own: a firework bursts there; an arrow goes into a person
	 * or car (gone) or sticks where it hit a wall.
	 */
	public static void projectileHit(final int id, final double x, final double y, final double z, final boolean stick) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			ServerLevel level = s.overworld();
			Entity e = level.getEntity(id);
			if (e instanceof FireworkRocketEntity) {
				e.setPos(x, y, z);
				level.broadcastEntityEvent(e, (byte) 17);
				e.discard();
			} else if (e instanceof AbstractArrow) {
				if (stick) {
					e.setPos(x, y, z);
					e.setDeltaMovement(Vec3.ZERO);
					e.setNoGravity(true);
					e.addTag(HIT_TAG);
				} else {
					e.discard();
				}
			}
		});
	}

	/** Server thread, from Level.setBlock: remember the change; flushed once per tick. */
	public static void onBlockChanged(final ServerLevel level, final BlockPos pos, final BlockState state) {
		if (!Passthrough.active || level != level.getServer().overworld()) {
			return;
		}

		Nether.onBlockChanged(pos, state);
		if (!placingGround) {
			changes.put(pos.immutable(), solidForHost(level, pos, state));
			if (!state.isAir()) {
				built.add(pos.immutable());
			} else if (built.remove(pos)) {
				// a block that had taken the place of the host's floor is gone: the floor comes back
				if (mapState(pos) != null) {
					restore.add(pos.immutable());
				}
			} else if ((columnState(columns.get(columnKey(pos.getX(), pos.getZ())), pos.getY()) != null || Materials.isCell(pos))
				&& !mined.contains(pos)) {
				mine(level, pos);
			}
		}
	}

	/** While on, block changes are the host's own ground being edited (not reported as blocks to collide with). */
	static void quietGround(final boolean on) {
		placingGround = on;
	}

	/** End of each server tick: {"t":"blocks","set":[x,y,z,...],"clear":[x,y,z,...]}. */
	static void flush(final MinecraftServer s) {
		if (minedDirty && ++minedSaveIn >= 200) {
			minedSaveIn = 0;
			saveMined(s);
		}

		if (!minedNews.isEmpty()) {
			// {"t":"mined","set":[x,y,z,...]}: the host opens its own world there
			StringBuilder b = new StringBuilder("{\"t\":\"mined\",\"set\":[");
			int n = 0;
			for (BlockPos p : minedNews) {
				b.append(n++ == 0 ? "" : ",").append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ());
			}

			minedNews.clear();
			Passthrough.events.accept(b.append("]}").toString());
		}

		if (changes.isEmpty()) {
			return;
		}

		StringBuilder set = new StringBuilder();
		StringBuilder clear = new StringBuilder();
		for (Map.Entry<BlockPos, Boolean> e : changes.entrySet()) {
			StringBuilder b = e.getValue() ? set : clear;
			BlockPos p = e.getKey();
			b.append(b.isEmpty() ? "" : ",").append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ());
		}

		changes.clear();
		Passthrough.events.accept("{\"t\":\"blocks\",\"set\":[" + set + "],\"clear\":[" + clear + "]}");
	}

	/** Every solid block within `radius` of the player, as one "blocks" message (the host's props start from this). */
	public static void sync(final int radius) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			ServerLevel level = s.overworld();
			ServerPlayer player = s.getPlayerList().getPlayers().isEmpty() ? null : s.getPlayerList().getPlayers().get(0);
			if (player == null) {
				return;
			}

			BlockPos c = player.blockPosition();
			BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
			int found = 0;
			for (int x = -radius; x <= radius && found < 3000; x++) {
				for (int z = -radius; z <= radius && found < 3000; z++) {
					for (int y = -24; y <= 40; y++) {
						p.set(c.getX() + x, c.getY() + y, c.getZ() + z);
						if (!level.isInWorldBounds(p) || !level.isLoaded(p)) {
							continue;
						}

						BlockState state = level.getBlockState(p);
						if (solidForHost(level, p, state)) {
							changes.put(p.immutable(), true);
							found++;
						}
					}
				}
			}

			flush(s);
		});
	}

	/** Elytra on and gliding (creative flight off), launched forward along the look; or back to creative flight. */
	public static void glide(final boolean start, final double speed) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			if (s.getPlayerList().getPlayers().isEmpty()) {
				return;
			}

			ServerPlayer player = s.getPlayerList().getPlayers().get(0);
			if (start) {
				player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
				player.getAbilities().flying = false;
				player.onUpdateAbilities();
				player.setOnGround(false);
				player.startFallFlying();
				player.setDeltaMovement(player.getLookAngle().scale(speed));
				player.needsSync = true;
			} else {
				player.stopFallFlying();
				player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
			}
		});
	}

	/** `source`: what exploded or was blown up, e.g. "tnt", "creeper", "fireball" (a ghast's). */
	public static void onExplosion(final Vec3 center, final float radius, final String source) {
		if (Passthrough.active) {
			Passthrough.events.accept(String.format(Locale.ROOT, "{\"t\":\"explosion\",\"pos\":[%.3f,%.3f,%.3f],\"r\":%.2f,\"src\":\"%s\"}",
				center.x, center.y, center.z, radius, source));
		}
	}
}
