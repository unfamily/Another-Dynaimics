package net.unfamily.another_dynamics.integration.ftbultimine;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import dev.ftb.mods.ftbultimine.api.blockselection.BlockSelectionHandler;
import dev.ftb.mods.ftbultimine.api.blockselection.RegisterBlockSelectionHandlerEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctConnectable;
import net.unfamily.another_dynamics.duct.DuctIds;
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import net.unfamily.another_dynamics.duct.logistics.DuctNetworkCache;
import net.unfamily.another_dynamics.duct.logistics.DuctPathfinder;
import net.unfamily.another_dynamics.duct.project.ProjectDuctNetwork;

/**
 * FTB Ultimine: same {@link DuctBlockEntity#getLogicalDuctId()} and same pipe-connected component per
 * {@link DuctNetworkType} (item does not pull fluid). Project ducts use {@link ProjectDuctNetwork} only.
 */
public final class FTBUltimineCompat {
    private FTBUltimineCompat() {}

    public static void register() {
        RegisterBlockSelectionHandlerEvent.REGISTER.register(
                registry -> registry.registerHandler(DuctSelectionHandler.INSTANCE));
    }

    enum DuctSelectionHandler implements BlockSelectionHandler {
        INSTANCE;

        private static final ThreadLocal<SelectionCache> CACHE = ThreadLocal.withInitial(SelectionCache::new);

        @Override
        public Result customSelectionCheck(
                Player player, BlockPos origPos, BlockPos pos, BlockState origState, BlockState state) {
            Level level = player.level();
            if (ProjectDuctNetwork.isProjectDuct(origState.getBlock())) {
                if (!ProjectDuctNetwork.isProjectDuct(state.getBlock())) {
                    return Result.FALSE;
                }
                return ProjectDuctNetwork.sameConnectedComponent(level, origPos, pos) ? Result.TRUE : Result.FALSE;
            }
            if (!(origState.getBlock() instanceof DuctConnectable)) {
                return Result.PASS;
            }
            if (!(state.getBlock() instanceof DuctConnectable)) {
                return Result.FALSE;
            }
            String origId = logicalDuctIdAt(level, origPos);
            String candId = logicalDuctIdAt(level, pos);
            if (origId == null || candId == null) {
                return Result.FALSE;
            }
            if (!DuctIds.normalize(origId).equals(DuctIds.normalize(candId))) {
                return Result.FALSE;
            }
            return sharesConnectedNetworkComponent(level, origPos, pos) ? Result.TRUE : Result.FALSE;
        }

        private static boolean sharesConnectedNetworkComponent(Level level, BlockPos origPos, BlockPos pos) {
            SelectionCache cache = CACHE.get();
            if (!cache.matches(level, origPos)) {
                cache.rebuild(level, origPos);
            }
            for (DuctNetworkType type : DuctNetworkType.values()) {
                if (!DuctConnectable.isSameNetwork(level, origPos, type)) {
                    continue;
                }
                if (!DuctConnectable.isSameNetwork(level, pos, type)) {
                    continue;
                }
                Set<BlockPos> component = cache.components.get(type);
                if (component != null && component.contains(pos)) {
                    return true;
                }
            }
            return false;
        }

        private static String logicalDuctIdAt(Level level, BlockPos pos) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof DuctBlockEntity duct) {
                return duct.getLogicalDuctId();
            }
            return null;
        }

        private static final class SelectionCache {
            private Level level;
            private BlockPos origin = BlockPos.ZERO;
            private final Map<DuctNetworkType, Set<BlockPos>> components = new EnumMap<>(DuctNetworkType.class);

            boolean matches(Level level, BlockPos origin) {
                return this.level == level && this.origin.equals(origin);
            }

            void rebuild(Level level, BlockPos origin) {
                this.level = level;
                this.origin = origin.immutable();
                components.clear();
                for (DuctNetworkType type : DuctNetworkType.values()) {
                    if (!DuctConnectable.isSameNetwork(level, origin, type)) {
                        continue;
                    }
                    if (level instanceof ServerLevel serverLevel) {
                        components.put(type, DuctNetworkCache.connectedDucts(serverLevel, origin, type));
                    } else {
                        components.put(type, DuctPathfinder.connectedDucts(level, origin, type));
                    }
                }
            }
        }
    }
}
