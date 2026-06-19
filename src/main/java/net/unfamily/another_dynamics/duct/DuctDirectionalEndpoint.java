package net.unfamily.another_dynamics.duct;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.unfamily.another_dynamics.duct.project.ProjectDuctBlock;

import org.jetbrains.annotations.Nullable;

/** Absolute block position and inventory face for remote filter routing. */
public record DuctDirectionalEndpoint(BlockPos pos, Direction face) {
    public static final Codec<DuctDirectionalEndpoint> CODEC =
            RecordCodecBuilder.create(
                    inst ->
                            inst.group(
                                            BlockPos.CODEC.fieldOf("position").forGetter(DuctDirectionalEndpoint::pos),
                                            Direction.CODEC.fieldOf("direction").forGetter(DuctDirectionalEndpoint::face))
                                    .apply(inst, DuctDirectionalEndpoint::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, DuctDirectionalEndpoint> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    DuctDirectionalEndpoint::pos,
                    ByteBufCodecs.idMapper(Direction::from3DDataValue, Direction::get3DDataValue),
                    DuctDirectionalEndpoint::face,
                    DuctDirectionalEndpoint::new);

    public boolean matches(BlockPos otherPos, Direction otherFace) {
        return pos.equals(otherPos) && face == otherFace;
    }

    /**
     * Filter destination: physical block attached on {@code ductFace}, not the duct itself. Returns {@code null} when
     * the neighbor is another duct or has no block entity.
     */
    @Nullable
    public static DuctDirectionalEndpoint connectionAtDuctFace(
            Level level, BlockPos ductPos, Direction ductFace) {
        BlockPos neighbor = ductPos.relative(ductFace);
        BlockState neighborState = level.getBlockState(neighbor);
        if (isTransportPipeBlock(neighborState)) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(neighbor);
        if (be == null) {
            return null;
        }
        return new DuctDirectionalEndpoint(neighbor.immutable(), ductFace.getOpposite());
    }

    /** Destination tool binding: clicked block with a block entity, not a duct/pipe block. */
    @Nullable
    public static DuctDirectionalEndpoint fromBlockInteraction(
            Level level, BlockPos clickedPos, Direction clickedFace) {
        BlockState state = level.getBlockState(clickedPos);
        if (isTransportPipeBlock(state)) {
            return null;
        }
        if (level.getBlockEntity(clickedPos) == null) {
            return null;
        }
        return new DuctDirectionalEndpoint(clickedPos.immutable(), clickedFace);
    }

    private static boolean isTransportPipeBlock(BlockState state) {
        return state.getBlock() instanceof AbstractDuctBlock
                || state.getBlock() instanceof ProjectDuctBlock;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("position", new int[] {pos.getX(), pos.getY(), pos.getZ()});
        tag.putString("direction", face.getSerializedName());
        return tag;
    }

    @Nullable
    public static DuctDirectionalEndpoint fromTag(@Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        BlockPos p = NbtUtils.readBlockPos(tag, "position").orElse(null);
        if (p == null) {
            return null;
        }
        Direction d = Direction.byName(tag.getString("direction"));
        if (d == null) {
            return null;
        }
        return new DuctDirectionalEndpoint(p, d);
    }

    /**
     * Older builds stored the duct block + face; current format stores the attached inventory block.
     */
    @Nullable
    public static DuctDirectionalEndpoint migrateLegacyStoredEndpoint(
            Level level, @Nullable DuctDirectionalEndpoint stored) {
        if (stored == null || level == null) {
            return stored;
        }
        if (!isTransportPipeBlock(level.getBlockState(stored.pos()))) {
            return stored;
        }
        return connectionAtDuctFace(level, stored.pos(), stored.face());
    }
}
