package net.unfamily.another_dynamics.integration.ftbultimine;

import dev.ftb.mods.ftbultimine.api.blockselection.BlockSelectionHandler;
import dev.ftb.mods.ftbultimine.api.blockselection.RegisterBlockSelectionHandlerEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctConnectable;
import net.unfamily.another_dynamics.duct.DuctIds;

/**
 * FTB Ultimine: treat ducts with the same {@link DuctBlockEntity#getLogicalDuctId()} as one selection group
 * (default matcher only compares block type).
 */
public final class FTBUltimineCompat {
    private FTBUltimineCompat() {}

    public static void register() {
        RegisterBlockSelectionHandlerEvent.REGISTER.register(
                registry -> registry.registerHandler(DuctLogicalIdSelectionHandler.INSTANCE));
    }

    enum DuctLogicalIdSelectionHandler implements BlockSelectionHandler {
        INSTANCE;

        @Override
        public Result customSelectionCheck(
                Player player, BlockPos origPos, BlockPos pos, BlockState origState, BlockState state) {
            if (!(origState.getBlock() instanceof DuctConnectable)) {
                return Result.PASS;
            }
            if (!(state.getBlock() instanceof DuctConnectable)) {
                return Result.FALSE;
            }
            Level level = player.level();
            String origId = logicalDuctIdAt(level, origPos);
            String candId = logicalDuctIdAt(level, pos);
            if (origId == null || candId == null) {
                return Result.FALSE;
            }
            return DuctIds.normalize(origId).equals(DuctIds.normalize(candId)) ? Result.TRUE : Result.FALSE;
        }

        private static String logicalDuctIdAt(Level level, BlockPos pos) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof DuctBlockEntity duct) {
                return duct.getLogicalDuctId();
            }
            return null;
        }
    }
}
