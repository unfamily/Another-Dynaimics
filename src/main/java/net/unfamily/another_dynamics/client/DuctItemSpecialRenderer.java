package net.unfamily.another_dynamics.client;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;

import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctIds;
import net.unfamily.another_dynamics.duct.DuctOpaqueRendering;
import net.unfamily.another_dynamics.registry.ModDataComponents;

import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/** Item special renderer for duct stacks driven by {@link ModDataComponents#DUCT_LOGICAL_ID}. */
public final class DuctItemSpecialRenderer implements SpecialModelRenderer<String> {
    private static final float OPAQUE_V_SHIFT_RATIO = 0.5f;
    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "duct_item");

    @Override
    public void submit(
            @Nullable String logicalId,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            int lightCoords,
            int overlayCoords,
            boolean hasFoil,
            int outlineColor) {
        if (logicalId == null) {
            return;
        }
        List<BakedQuad> quads = buildItemQuads(logicalId);
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
        String logicalId = stack.get(ModDataComponents.DUCT_LOGICAL_ID.get());
        if (logicalId == null || logicalId.isEmpty()) {
            logicalId = DuctIds.DEFAULT_LOGICAL_ID;
        }
        return logicalId;
    }

    static List<BakedQuad> buildItemQuads(String logicalId) {
        Map<String, DuctCompositeGeometry> cache = DuctRenderingSupport.getGlobalGeometryCache();
        DuctCompositeGeometry geo = cache.get(logicalId);
        if (geo == null || !geo.isBuilt()) {
            geo = cache.get(DuctIds.DEFAULT_LOGICAL_ID);
        }
        if (geo == null || !geo.isBuilt()) {
            return List.of();
        }

        boolean opaqueRendering =
                DuctOpaqueRendering.definitionAlwaysOpaque(logicalId)
                        || DuctOpaqueRenderRefresh.snapshotPlayerAllOpaque();

        if (opaqueRendering) {
            List<BakedQuad> centerQuads = geo.lineCenterQuads();
            List<BakedQuad> nodeCaps = geo.lineNodeCapsQuads();
            List<BakedQuad> shiftedCenter = centerQuads;
            if (!centerQuads.isEmpty()) {
                TextureAtlasSprite sprite = centerQuads.getFirst().materialInfo().sprite();
                if (sprite != null) {
                    float vShift = (sprite.getV1() - sprite.getV0()) * OPAQUE_V_SHIFT_RATIO;
                    shiftedCenter = new java.util.ArrayList<>(centerQuads.size());
                    for (BakedQuad q : centerQuads) {
                        shiftedCenter.add(DuctCompositeGeometry.shiftQuadV(q, vShift));
                    }
                }
            }
            java.util.ArrayList<BakedQuad> out = new java.util.ArrayList<>(shiftedCenter.size() + nodeCaps.size());
            out.addAll(shiftedCenter);
            out.addAll(nodeCaps);
            return out;
        }
        return geo.lineAllQuads();
    }

    public record Unbaked() implements SpecialModelRenderer.Unbaked<String> {
        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

        @Override
        public SpecialModelRenderer<String> bake(BakingContext context) {
            return new DuctItemSpecialRenderer();
        }

        @Override
        public MapCodec<? extends SpecialModelRenderer.Unbaked<String>> type() {
            return MAP_CODEC;
        }
    }
}
