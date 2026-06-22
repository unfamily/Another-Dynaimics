package net.unfamily.another_dynamics.duct;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

public record FilterRemoteNodeContext(
        FilterRemoteNodeRole role, @Nullable DuctDirectionalEndpoint counterparty) {

    public static final FilterRemoteNodeContext ANY =
            new FilterRemoteNodeContext(FilterRemoteNodeRole.EXTRACT_ROUTE, null);

    public static FilterRemoteNodeContext of(
            FilterRemoteNodeRole role, Level level, BlockPos ductPos, Direction ductFace) {
        return new FilterRemoteNodeContext(
                role, DuctDirectionalEndpoint.connectionAtDuctFace(level, ductPos, ductFace));
    }
}
