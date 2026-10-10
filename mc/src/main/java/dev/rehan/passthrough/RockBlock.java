package dev.rehan.passthrough;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The host's map where it is solid: an invisible full block (the host draws its own walls and floors) that is as hard
 * as stone, so in survival it can be mined with a pickaxe or blown up, and drops cobblestone.
 */
public final class RockBlock extends Block {
	private static Block instance;

	private RockBlock(final BlockBehaviour.Properties properties) {
		super(properties);
	}

	static void register() {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(Passthrough.ID, "rock"));
		instance = Registry.register(BuiltInRegistries.BLOCK, key, new RockBlock(
			BlockBehaviour.Properties.of().setId(key).strength(1.5F, 6.0F).noLootTable().noOcclusion().isValidSpawn((state, level, pos, type) -> false)
		));
	}

	public static BlockState state() {
		return instance.defaultBlockState();
	}

	public static boolean is(final BlockState state) {
		return state.getBlock() == instance;
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
	protected boolean propagatesSkylightDown(final BlockState state) {
		return true;
	}

	@Override
	protected float getShadeBrightness(final BlockState state, final BlockGetter level, final BlockPos pos) {
		return 1.0F;
	}
}
