package net.unfamily.another_dynamics.client.transit;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.registry.ModAttachments;

import org.jetbrains.annotations.Nullable;

/**
 * In-transit items: {@link BlockEntityRenderer} draws stacks via a lightweight {@link ItemEntity} and the
 * entity item renderer (local {@link PoseStack}, no global stage hacks).
 */
public final class DuctTransitBlockEntityRenderer implements BlockEntityRenderer<DuctBlockEntity> {

    /** Reused ghost entity + cached {@link EntityRenderer} to avoid per-frame allocation. */
    private static final class LazyItemGhost {
        @Nullable
        private ItemEntity entityItem;
        @Nullable
        private EntityRenderer<? super ItemEntity> renderer;

        void init(Level world, BlockPos pos) {
            // Entity#setLevel is protected on 1.21.x; recreate when the level reference changes.
            if (entityItem == null || entityItem.level() != world) {
                entityItem = new ItemEntity(EntityType.ITEM, world);
                renderer = null;
            }
            entityItem.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }

        void renderAsStack(PoseStack matrix, MultiBufferSource buffer, ItemStack stack, int light) {
            if (entityItem == null) {
                return;
            }
            if (renderer == null) {
                renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entityItem);
            }
            entityItem.setItem(stack);
            renderer.render(entityItem, 0.0F, 0.0F, matrix, buffer, light);
        }
    }

    private final LazyItemGhost itemGhost = new LazyItemGhost();

    public DuctTransitBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(
            DuctBlockEntity tile,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            int packedOverlay) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (mc.player.getData(ModAttachments.DUCT_TRANSIT_OPAQUE.get())) {
            return;
        }
        if (tile.getLevel() == null) {
            return;
        }
        BlockPos origin = tile.getBlockPos();
        List<DuctTransitVisual> visuals = DuctTransitClientState.visualsAt(origin);
        if (visuals.isEmpty()) {
            return;
        }
        Level level = tile.getLevel();
        itemGhost.init(level, origin);
        for (DuctTransitVisual v : visuals) {
            ItemStack stack = v.stack;
            if (stack.isEmpty()) {
                continue;
            }
            float progress = v.progress01(level, partialTick);
            Vec3 world = v.positionAt(progress);
            poseStack.pushPose();
            poseStack.translate(world.x - origin.getX(), world.y - origin.getY(), world.z - origin.getZ());

            float rot = (level.getGameTime() + partialTick) * 3.0f;
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(rot));
            poseStack.scale(0.35f, 0.35f, 0.35f);

            BlockPos lightPos = BlockPos.containing(world);
            int light = LevelRenderer.getLightColor(level, lightPos);

            itemGhost.renderAsStack(poseStack, buffer, stack, light);
            poseStack.popPose();
        }
    }

    @Override
    public AABB getRenderBoundingBox(DuctBlockEntity blockEntity) {
        List<DuctTransitVisual> visuals = DuctTransitClientState.visualsAt(blockEntity.getBlockPos());
        if (visuals.isEmpty()) {
            return BlockEntityRenderer.super.getRenderBoundingBox(blockEntity);
        }
        AABB box = new AABB(blockEntity.getBlockPos());
        for (DuctTransitVisual v : visuals) {
            if (v.ductPath.isEmpty()) {
                box = box.minmax(new AABB(v.ownerDuct));
            } else {
                for (BlockPos p : v.ductPath) {
                    box = box.minmax(new AABB(p));
                }
            }
        }
        return box.inflate(1.0);
    }
}
