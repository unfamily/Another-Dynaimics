package net.unfamily.another_dynamics.client.project;

import java.util.List;
import java.util.function.Consumer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;

import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.client.DuctCompositeGeometry;
import net.unfamily.another_dynamics.client.DuctRenderingSupport;

import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Item special renderer for {@code project_duct}: same line+node preview as classic ducts
 * ({@link net.unfamily.another_dynamics.client.DuctItemSpecialRenderer}), always non-opaque project texture.
 */
public final class ProjectDuctItemSpecialRenderer implements SpecialModelRenderer<String> {
    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "project_duct_item");

    private static final String ARG = "project_duct";

    @Override
    public void submit(
            @Nullable String unused,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            int lightCoords,
            int overlayCoords,
            boolean hasFoil,
            int outlineColor) {
        if (unused == null) {
            return;
        }
        List<BakedQuad> quads = buildItemQuads();
        if (quads.isEmpty()) {
            return;
        }
        var particleInfo = quads.getFirst().materialInfo();
        var part = new SimpleModelWrapper(
                DuctRenderingSupport.quadsFromList(quads),
                false,
                new net.minecraft.client.resources.model.sprite.Material.Baked(particleInfo.sprite(), false));
        submitNodeCollector.submitBlockModel(
                poseStack,
                Sheets.cutoutBlockSheet(),
                List.of(part),
                ItemStackRenderState.LayerRenderState.EMPTY_TINTS,
                lightCoords,
                overlayCoords,
                outlineColor);
    }

    @Override
    public void getExtents(Consumer<Vector3fc> output) {
        output.accept(new org.joml.Vector3f(0f, 0f, 0f));
        output.accept(new org.joml.Vector3f(1f, 1f, 1f));
    }

    @Override
    public @Nullable String extractArgument(ItemStack stack) {
        return ARG;
    }

    /** Always the non-opaque line mesh (center + node caps), matching classic duct item preview. */
    static List<BakedQuad> buildItemQuads() {
        DuctCompositeGeometry geo = DuctRenderingSupport.getProjectGeometry();
        if (geo == null || !geo.isBuilt()) {
            return List.of();
        }
        return geo.lineAllQuads();
    }

    public record Unbaked() implements SpecialModelRenderer.Unbaked<String> {
        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

        @Override
        public SpecialModelRenderer<String> bake(BakingContext context) {
            return new ProjectDuctItemSpecialRenderer();
        }

        @Override
        public MapCodec<? extends SpecialModelRenderer.Unbaked<String>> type() {
            return MAP_CODEC;
        }
    }
}
