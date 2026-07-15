package net.unfamily.another_dynamics.client.transit;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;

/**
 * Visual-only stacks along the path: uses the 26.x item submit pipeline; fluid/gas use small textured cuboids with the
 * same path motion and Y spin.
 */
public final class DuctTransitBlockEntityRenderer
        implements BlockEntityRenderer<DuctBlockEntity, DuctTransitBlockEntityRenderer.TransitRenderState> {

    /** Vertical tweak and scale for in-duct ghost items (block-local space before item transform). */
    private static final float GHOST_Y_OFFSET = -0.14f;
    private static final float FLUID_Y_OFFSET = 0.0f;
    private static final float GHOST_SCALE = 0.78f;

    private final ItemModelResolver itemModelResolver;

    public DuctTransitBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {
        this.itemModelResolver = ctx.itemModelResolver();
    }

    public static final class TransitRenderState extends BlockEntityRenderState {
        final List<ItemEntry> items = new ArrayList<>();
        final List<FluidEntry> fluids = new ArrayList<>();
        final List<GasEntry> gases = new ArrayList<>();
    }

    private static final class ItemEntry {
        final ItemStackRenderState renderState = new ItemStackRenderState();
        double dx;
        double dy;
        double dz;
        float spinDegrees;
        int lightCoords;
        int overlay;
    }

    private record FluidEntry(FluidStack fluid, double dx, double dy, double dz, float spinDegrees, int lightCoords, int overlay) {}

    private record GasEntry(int tintRgb, double dx, double dy, double dz, float spinDegrees, int lightCoords, int overlay) {}

    private record BlockPosItemOwner(Level level, BlockPos pos, float yaw) implements ItemOwner {
        @Override
        public Level level() {
            return level;
        }

        @Override
        public Vec3 position() {
            return Vec3.atCenterOf(pos);
        }

        @Override
        public float getVisualRotationYInDegrees() {
            return yaw;
        }
    }

    @Override
    public TransitRenderState createRenderState() {
        return new TransitRenderState();
    }

    @Override
    public void extractRenderState(
            DuctBlockEntity tile,
            TransitRenderState state,
            float partialTick,
            Vec3 cameraPosition,
            ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderState.extractBase(tile, state, breakProgress);
        state.items.clear();
        state.fluids.clear();
        state.gases.clear();

        Minecraft mc = Minecraft.getInstance();
        Level level = tile.getLevel();
        if (level == null || net.unfamily.another_dynamics.duct.DuctOpaqueRendering.effectiveOpaque(tile, mc.player)) {
            return;
        }

        BlockPos origin = tile.getBlockPos();
        List<DuctTransitVisual> visuals = DuctTransitClientState.visualsAt(origin);
        List<DuctFluidTransitVisual> fluidVisuals = DuctFluidTransitClientState.visualsAt(origin);
        List<DuctGasTransitVisual> gasVisuals = DuctGasTransitClientState.visualsAt(origin);
        if (visuals.isEmpty() && fluidVisuals.isEmpty() && gasVisuals.isEmpty()) {
            return;
        }

        float spinBase = (level.getGameTime() + partialTick) * 3.0f;
        int defaultOverlay = state.lightCoords != 0 ? OverlayTexture.NO_OVERLAY : OverlayTexture.NO_OVERLAY;

        for (DuctTransitVisual v : visuals) {
            ItemStack stack = v.stack;
            if (stack.isEmpty()) {
                continue;
            }
            Vec3 world = v.positionAt(0f, level, partialTick);
            ItemEntry entry = new ItemEntry();
            entry.dx = world.x - origin.getX();
            entry.dy = world.y - origin.getY();
            entry.dz = world.z - origin.getZ();
            entry.spinDegrees = spinBase;
            BlockPos lightPos = BlockPos.containing(world);
            entry.lightCoords = LevelRenderer.getLightCoords(level, lightPos);
            entry.overlay = defaultOverlay;
            int seed = (int) (origin.asLong() ^ stack.getItem().hashCode() ^ stack.getCount());
            itemModelResolver.updateForTopItem(
                    entry.renderState,
                    stack,
                    ItemDisplayContext.GROUND,
                    level,
                    new BlockPosItemOwner(level, lightPos, spinBase),
                    seed);
            state.items.add(entry);
        }

        for (DuctFluidTransitVisual fv : fluidVisuals) {
            if (fv.fluid.isEmpty()) {
                continue;
            }
            Vec3 world = fv.positionAt(0f, level, partialTick);
            BlockPos lightPos = BlockPos.containing(world);
            state.fluids.add(
                    new FluidEntry(
                            fv.fluid,
                            world.x - origin.getX(),
                            world.y - origin.getY(),
                            world.z - origin.getZ(),
                            spinBase,
                            LevelRenderer.getLightCoords(level, lightPos),
                            defaultOverlay));
        }

        for (DuctGasTransitVisual gv : gasVisuals) {
            if (gv.amount <= 0) {
                continue;
            }
            Vec3 world = gv.positionAt(0f, level, partialTick);
            BlockPos lightPos = BlockPos.containing(world);
            state.gases.add(
                    new GasEntry(
                            gv.tintRgb,
                            world.x - origin.getX(),
                            world.y - origin.getY(),
                            world.z - origin.getZ(),
                            spinBase,
                            LevelRenderer.getLightCoords(level, lightPos),
                            defaultOverlay));
        }
    }

    @Override
    public void submit(
            TransitRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            CameraRenderState camera) {
        for (ItemEntry entry : state.items) {
            poseStack.pushPose();
            poseStack.translate(entry.dx, entry.dy, entry.dz);
            poseStack.translate(0.0f, GHOST_Y_OFFSET, 0.0f);
            poseStack.mulPose(Axis.YP.rotationDegrees(entry.spinDegrees));
            poseStack.scale(GHOST_SCALE, GHOST_SCALE, GHOST_SCALE);
            entry.renderState.submit(poseStack, submitNodeCollector, entry.lightCoords, entry.overlay, 0);
            poseStack.popPose();
        }

        for (FluidEntry fv : state.fluids) {
            poseStack.pushPose();
            poseStack.translate(fv.dx, fv.dy, fv.dz);
            poseStack.translate(0.0f, FLUID_Y_OFFSET, 0.0f);
            poseStack.mulPose(Axis.YP.rotationDegrees(fv.spinDegrees));
            poseStack.scale(GHOST_SCALE, GHOST_SCALE, GHOST_SCALE);
            FluidTransitCuboidRenderer.renderCuboid(
                    fv.fluid, poseStack, submitNodeCollector, fv.lightCoords, fv.overlay);
            poseStack.popPose();
        }

        for (GasEntry gv : state.gases) {
            poseStack.pushPose();
            poseStack.translate(gv.dx, gv.dy, gv.dz);
            poseStack.translate(0.0f, FLUID_Y_OFFSET, 0.0f);
            poseStack.mulPose(Axis.YP.rotationDegrees(gv.spinDegrees));
            poseStack.scale(GHOST_SCALE, GHOST_SCALE, GHOST_SCALE);
            GasTransitCuboidRenderer.renderCuboid(gv.tintRgb, poseStack, submitNodeCollector, gv.lightCoords, gv.overlay);
            poseStack.popPose();
        }
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}
