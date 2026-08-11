package net.unfamily.another_dynamics.duct;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.unfamily.another_dynamics.duct.project.ProjectDuctNetwork;

/**
 * Slight jump boost when standing on a duct so jumping onto adjacent full blocks feels like standing
 * on a full block. Collision stays the physical pipe only ({@link DuctShapes#forMasks}).
 */
public final class DuctJumpAssistEvents {
    /**
     * Extra +Y impulse on jump. Compensates the duct top at 11/16 vs a full block (deficit 5/16)
     * without changing collision shapes.
     */
    private static final double JUMP_Y_BOOST = 0.06;

    private DuctJumpAssistEvents() {}

    @SubscribeEvent
    public static void onLivingJump(LivingEvent.LivingJumpEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof Player)) {
            return;
        }
        if (!isStandingOnDuct(entity)) {
            return;
        }
        entity.setDeltaMovement(entity.getDeltaMovement().add(0.0, JUMP_Y_BOOST, 0.0));
    }

    private static boolean isStandingOnDuct(LivingEntity entity) {
        BlockPos support =
                BlockPos.containing(entity.getX(), entity.getY() - 0.05, entity.getZ());
        Block block = entity.level().getBlockState(support).getBlock();
        return block instanceof DuctConnectable || ProjectDuctNetwork.isProjectDuct(block);
    }
}
