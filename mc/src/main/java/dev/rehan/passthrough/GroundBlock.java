package dev.rehan.passthrough;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The host's floor where it isn't on a whole block: an invisible slab 1 to 7 eighths of a block tall (a Doom floor
 * moves in steps of 8 map units, a quarter block). Mobs and items rest on it at the host's real floor height, and a
 * block placed on it takes its place, so the build sits in the floor instead of floating above it.
 */
public final class GroundBlock extends Block {
	public static final IntegerProperty LAYERS = IntegerProperty.create("layers", 1, 7);
	private static final VoxelShape[] SHAPES = Block.boxes(8, layers -> Block.column(16.0, 0.0, Math.max(layers, 1) * 2.0));
	private static Block instance;

	private GroundBlock(final BlockBehaviour.Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(LAYERS, 1));
	}

	static void register() {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(Passthrough.ID, "ground"));
		instance = Registry.register(BuiltInRegistries.BLOCK, key, new GroundBlock(
			BlockBehaviour.Properties.of().setId(key).strength(1.5F, 6.0F).noLootTable().noOcclusion()
				.isValidSpawn((state, level, pos, type) -> false)
		));
	}

	public static BlockState layers(final int layers) {
		return instance.defaultBlockState().setValue(LAYERS, layers);
	}

	public static boolean is(final BlockState state) {
		return state.getBlock() == instance;
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LAYERS);
	}

	@Override
	protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
		return SHAPES[state.getValue(LAYERS)];
	}

	/** While Minecraft walks the player, he collides with the host's ground itself (Ground), not with its blocks. */
	@Override
	protected net.minecraft.world.phys.shapes.VoxelShape getCollisionShape(final BlockState state, final net.minecraft.world.level.BlockGetter level,
		final net.minecraft.core.BlockPos pos, final net.minecraft.world.phys.shapes.CollisionContext context) {
		if (Passthrough.walk && Passthrough.heightField && !Materials.isCell(pos) && context instanceof net.minecraft.world.phys.shapes.EntityCollisionContext entity
			&& entity.getEntity() instanceof net.minecraft.world.entity.player.Player player && player.level().isClientSide()) {
			return net.minecraft.world.phys.shapes.Shapes.empty();
		}

		return super.getCollisionShape(state, level, pos, context);
	}

	/** As hard to dig, with the same tools, as the ordinary block this cell of the host's map is made of. */
	@Override
	protected float getDestroyProgress(final BlockState state, final net.minecraft.world.entity.player.Player player,
		final net.minecraft.world.level.BlockGetter level, final net.minecraft.core.BlockPos pos) {
		return Materials.at(pos).getDestroyProgress(player, level, pos);
	}

	/** And it drops what that block drops, to the tool that block needs. */
	@Override
	public void playerDestroy(final net.minecraft.server.level.ServerLevel level, final net.minecraft.server.level.ServerPlayer player,
		final net.minecraft.core.BlockPos pos, final BlockState state, final net.minecraft.world.level.block.entity.BlockEntity blockEntity,
		final net.minecraft.world.item.ItemStack tool) {
		super.playerDestroy(level, player, pos, state, blockEntity, tool);
		BlockState made = Materials.at(pos);
		if (!made.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(made)) {
			Block.dropResources(made, level, pos, null, player, tool);
		}
	}

	@Override
	protected RenderShape getRenderShape(final BlockState state) {
		return RenderShape.INVISIBLE;
	}

	@Override
	protected boolean canBeReplaced(final BlockState state, final BlockPlaceContext context) {
		return true;
	}

	@Override
	protected boolean propagatesSkylightDown(final BlockState state) {
		return true;
	}

	@Override
	protected float getShadeBrightness(final BlockState state, final BlockGetter level, final BlockPos pos) {
		return 1.0F;
	}
}
