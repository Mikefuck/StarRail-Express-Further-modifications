package com.habitrain.core.scene.block;

import com.habitrain.core.HabiTrainCore;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * Persisted world marker; replay data stays in the server service, not in per-board NBT.
 *
 * <p>A level mahogany tabletop on four wooden legs supports the floating replay display. Only
 * display settings are stored per table; the last match and its mesh remain shared.</p>
 */
public final class SettlementSummaryBoard extends BaseEntityBlock {
    public static final ResourceLocation ID = HabiTrainCore.id("settlement_summary_board");
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final MapCodec<SettlementSummaryBoard> CODEC = simpleCodec(SettlementSummaryBoard::new);
    /** North-facing (front at z = 0) boxes in model pixels; other facings are rotated copies. */
    private static final double[][] NORTH_BOXES = {
            {0, 14, 0, 16, 16, 16},   // flat tabletop
            {1, 0, 1, 4, 14, 4},      // four legs, open space underneath
            {12, 0, 1, 15, 14, 4},
            {1, 0, 12, 4, 14, 15},
            {12, 0, 12, 15, 14, 15},
            {2, 11, 2, 14, 14, 3},    // apron around the tabletop
            {2, 11, 13, 14, 14, 14},
            {2, 11, 3, 3, 14, 13},
            {13, 11, 3, 14, 14, 13}
    };
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        for (Direction direction : Direction.Plane.HORIZONTAL) SHAPES.put(direction, shapeFor(direction));
    }

    public static final SettlementSummaryBoard BLOCK = new SettlementSummaryBoard(
            BlockBehaviour.Properties.of().strength(2.5f).sound(net.minecraft.world.level.block.SoundType.WOOD).noOcclusion());
    public static final BlockItem ITEM = new BlockItem(BLOCK, new Item.Properties());
    private static BlockEntityType<Marker> markerType;

    public SettlementSummaryBoard(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public static void register() {
        Registry.register(BuiltInRegistries.BLOCK, ID, BLOCK);
        Registry.register(BuiltInRegistries.ITEM, ID, ITEM);
        markerType = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ID,
                BlockEntityType.Builder.of(Marker::new, BLOCK).build(null));
    }

    /** Rotates the north-facing pixel boxes about the block centre (matches the blockstate's y rotation). */
    static VoxelShape shapeFor(Direction facing) {
        VoxelShape shape = Shapes.empty();
        for (double[] b : NORTH_BOXES) {
            double[] r = BoardShapeMath.rotate(b, facing);
            shape = Shapes.or(shape, Block.box(r[0], r[1], r[2], r[3], r[4], r[5]));
        }
        return shape.optimize();
    }

    @Override protected MapCodec<SettlementSummaryBoard> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.getOrDefault(state.getValue(FACING), SHAPES.get(Direction.NORTH));
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                                         BlockHitResult hit) {
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Marker(pos, state); }

    public static final class Marker extends BlockEntity {
        private ReplayBoardSettings settings = ReplayBoardSettings.DEFAULT;

        public Marker(BlockPos pos, BlockState state) { super(markerType, pos, state); }

        public ReplayBoardSettings settings() { return settings; }

        public void setSettings(ReplayBoardSettings value) {
            if (settings.equals(value)) return;
            settings = value;
            setChanged();
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            tag.put("Display", settings.save());
        }

        @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            settings = ReplayBoardSettings.load(tag.getCompound("Display"));
        }

        @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            return saveCustomOnly(registries);
        }

        @Override public ClientboundBlockEntityDataPacket getUpdatePacket() {
            return ClientboundBlockEntityDataPacket.create(this);
        }
    }
}
