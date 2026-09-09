package net.unfamily.another_dynamics.machine.connector;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.unfamily.another_dynamics.registry.ModBlockEntities;

import org.jetbrains.annotations.Nullable;

/**
 * Forwards registered block capabilities to the attached machine with world-aligned side mapping.
 *
 * <p>Connectors may chain: each follows {@link MachineConnectorBlock#FACING} until a non-connector
 * block is reached, then queries that block with the same absolute {@code side}. Front is always
 * inert. Cycles and reentrant lookups return {@code null}.
 */
public final class MachineConnectorBlockEntity extends BlockEntity {
    private static final int MAX_CHAIN = 32;

    /** Guards nested getCapability while a forward is already in progress on this thread. */
    private static final ThreadLocal<Boolean> FORWARDING = ThreadLocal.withInitial(() -> false);

    public MachineConnectorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MACHINE_CONNECTOR.get(), pos, state);
    }

    public Direction front() {
        return getBlockState().getValue(MachineConnectorBlock.FACING);
    }

    /** Immediate neighbour this connector faces (may be another connector or the machine). */
    public BlockPos attachPos() {
        return worldPosition.relative(front());
    }

    /**
     * Walk the facing chain to the first non-connector block. {@code null} on cycle / overflow /
     * missing level.
     */
    @Nullable
    public BlockPos resolveMachinePos() {
        Level level = getLevel();
        if (level == null) {
            return null;
        }
        Set<BlockPos> visited = new HashSet<>();
        BlockPos cursor = attachPos();
        for (int i = 0; i < MAX_CHAIN; i++) {
            if (!visited.add(cursor.immutable())) {
                return null;
            }
            if (!(level.getBlockEntity(cursor) instanceof MachineConnectorBlockEntity next)) {
                return cursor;
            }
            cursor = next.attachPos();
        }
        return null;
    }

    /**
     * World-aligned forward through an optional connector chain. Front and null context → null.
     */
    @Nullable
    public <T> T forward(BlockCapability<T, @Nullable Direction> capability, @Nullable Direction side) {
        if (side == null || side == front()) {
            return null;
        }
        if (Boolean.TRUE.equals(FORWARDING.get())) {
            return null;
        }
        Level level = getLevel();
        if (level == null) {
            return null;
        }
        BlockPos machine = resolveMachinePos();
        if (machine == null) {
            return null;
        }
        FORWARDING.set(true);
        try {
            return level.getCapability(capability, machine, side);
        } finally {
            FORWARDING.set(false);
        }
    }
}
