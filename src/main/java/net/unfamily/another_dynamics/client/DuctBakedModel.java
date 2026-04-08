package net.unfamily.another_dynamics.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctIds;
import net.unfamily.another_dynamics.duct.DuctModelProperties;
import net.unfamily.another_dynamics.duct.DuctTextures;
import net.unfamily.another_dynamics.registry.ModAttachments;
import net.unfamily.another_dynamics.registry.ModDataComponents;

import org.jetbrains.annotations.Nullable;

public final class DuctBakedModel extends BakedModelWrapper<BakedModel> {
    /**
     * Vertical shift in atlas V for opaque duct skins: lower half of the sprite (stacked normal/opaque in the PNG).
     * Adjust if art uses unequal bands.
     */
    private static final float OPAQUE_TEXTURE_V_SHIFT_RATIO = 0.5f;

    private static final ChunkRenderTypeSet BLOCK_RENDER_TYPES =
            ChunkRenderTypeSet.of(RenderType.cutoutMipped(), RenderType.translucent());
    private static final List<RenderType> ITEM_RENDER_TYPES = List.of(RenderType.cutout(), RenderType.translucent());

    private final DuctCompositeGeometry geometry;
    private final Map<String, DuctCompositeGeometry> geometryByLogicalId;
    private final boolean itemForceDefinitionOpaqueOnly;
    private final @Nullable String itemFixedLogicalId;
    private final Map<String, DuctBakedModel> itemModelsByLogicalId = new HashMap<>();
    private final TextureAtlasSprite particleSprite;
    private final TextureAtlasSprite nodesSprite;
    private final String ductLogicalId;

    public DuctBakedModel(
            BakedModel original,
            DuctCompositeGeometry geometry,
            TextureAtlasSprite particleSprite,
            TextureAtlasSprite nodesSprite,
            String ductLogicalId,
            Map<String, DuctCompositeGeometry> geometryByLogicalId) {
        this(original, geometry, particleSprite, nodesSprite, ductLogicalId, geometryByLogicalId, false, null);
    }

    private DuctBakedModel(
            BakedModel original,
            DuctCompositeGeometry geometry,
            TextureAtlasSprite particleSprite,
            TextureAtlasSprite nodesSprite,
            String ductLogicalId,
            Map<String, DuctCompositeGeometry> geometryByLogicalId,
            boolean itemForceDefinitionOpaqueOnly,
            @Nullable String itemFixedLogicalId) {
        super(original);
        this.geometry = geometry;
        this.particleSprite = particleSprite;
        this.nodesSprite = nodesSprite;
        this.ductLogicalId = ductLogicalId;
        this.geometryByLogicalId = geometryByLogicalId;
        this.itemForceDefinitionOpaqueOnly = itemForceDefinitionOpaqueOnly;
        this.itemFixedLogicalId = itemFixedLogicalId;
    }

    public static Map<String, DuctCompositeGeometry> bakeAllGeometries(
            Function<Material, TextureAtlasSprite> spriteGetter,
            DuctCompositeGeometry fallbackGeometry) {
        Map<String, DuctCompositeGeometry> out = new HashMap<>();
        for (DuctDefinition def : DuctDefinitionRegistry.all().values()) {
            String logicalId = def.logicalId();
            try {
                String texStr = DuctTextures.compositeBlockTexture(logicalId).toString();
                var modelDefault = def.compositeModelDefault().orElse(DuctCompositeGeometry.DEFAULT_MODEL_DEFAULT);
                var modelLine = def.compositeModelLine().orElse(DuctCompositeGeometry.DEFAULT_MODEL_LINE);
                out.put(logicalId, DuctCompositeGeometry.bake(modelDefault, modelLine, texStr, spriteGetter));
            } catch (Exception ignored) {
                // Fallback below.
            }
        }
        if (fallbackGeometry != null) {
            out.putIfAbsent(ductIdFallbackKey(), fallbackGeometry);
        }
        return Map.copyOf(out);
    }

    private static String ductIdFallbackKey() {
        return "__fallback__";
    }

    @Override
    public ItemOverrides getOverrides() {
        return new ItemOverrides() {
            @Override
            public BakedModel resolve(BakedModel original, ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity entity, int seed) {
                String id = stack.get(ModDataComponents.DUCT_LOGICAL_ID.get());
                if (id == null || id.isEmpty()) {
                    id = DuctIds.DEFAULT_LOGICAL_ID;
                }
                final DuctCompositeGeometry g0 =
                        Optional.ofNullable(geometryByLogicalId.get(id))
                                .orElseGet(() -> geometryByLogicalId.getOrDefault(ductIdFallbackKey(), geometry));
                final DuctCompositeGeometry g = g0 != null ? g0 : geometry;
                if (g == null) {
                    return DuctBakedModel.this;
                }
                return itemModelsByLogicalId.computeIfAbsent(
                        id,
                        k -> new DuctBakedModel(originalModel, g, particleSprite, nodesSprite, ductLogicalId, geometryByLogicalId, true, k));
            }
        };
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        return BLOCK_RENDER_TYPES;
    }

    @Override
    public List<RenderType> getRenderTypes(ItemStack itemStack, boolean fabulous) {
        return ITEM_RENDER_TYPES;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return particleSprite != null ? particleSprite : originalModel.getParticleIcon();
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        return particleSprite != null ? particleSprite : originalModel.getParticleIcon(data);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand, ModelData modelData, @Nullable RenderType renderType) {
        if (!geometry.isBuilt()) {
            return originalModel.getQuads(state, side, rand, modelData, renderType);
        }
        Integer pipe = modelData.get(DuctModelProperties.PIPE_MASK);
        Integer storage = modelData.get(DuctModelProperties.STORAGE_MASK);
        Integer packedIcons = modelData.get(DuctModelProperties.NODE_ICONS_PACKED);
        int pm = pipe != null ? pipe : 0;
        int sm = storage != null ? storage : 0;
        List<BakedQuad> built = new ArrayList<>();
        int pi = packedIcons != null ? packedIcons : 0;
        // RenderType instances are not guaranteed to be reference-equal across calls; use a robust check.
        boolean isOverlayPass = renderType != null && renderType.toString().startsWith("RenderType[translucent");
        boolean isBasePass = renderType == null || renderType.toString().startsWith("RenderType[cutout_mipped");
        if (!isOverlayPass && !isBasePass) {
            return List.of();
        }
        boolean includeBase = isBasePass;
        String effectiveDuctId = itemFixedLogicalId != null ? itemFixedLogicalId : ductLogicalId;
        if (modelData != null) {
            String fromBe = modelData.get(DuctModelProperties.DUCT_LOGICAL_ID);
            if (fromBe != null && !fromBe.isEmpty()) {
                effectiveDuctId = fromBe;
            }
        }
        DuctCompositeGeometry effectiveGeometry =
                Optional.ofNullable(geometryByLogicalId.get(effectiveDuctId))
                        .orElseGet(() -> geometryByLogicalId.getOrDefault(ductIdFallbackKey(), geometry));
        if (effectiveGeometry == null || !effectiveGeometry.isBuilt()) {
            effectiveGeometry = geometry;
        }
        boolean defOpaque =
                DuctDefinitionRegistry.getByLogicalId(effectiveDuctId)
                        .map(d -> d.alwaysOpaqueRendering())
                        .orElse(false);
        boolean opaqueRendering =
                itemForceDefinitionOpaqueOnly
                        ? defOpaque
                        : (defOpaque
                                || (Minecraft.getInstance().player != null
                                        && Minecraft.getInstance().player.getData(ModAttachments.DUCT_TRANSIT_OPAQUE.get())));
        // Node icon layer (nodes.png) stays on the translucent pass; opaque mode only affects duct atlas + transit items.
        boolean includeOverlays = isOverlayPass;
        float ductVShift = 0f;
        if (opaqueRendering && includeBase && particleSprite != null) {
            ductVShift = (particleSprite.getV1() - particleSprite.getV0()) * OPAQUE_TEXTURE_V_SHIFT_RATIO;
        }
        effectiveGeometry.appendForWorldWithNodeIcons(
                built, pm, sm, pi, nodesSprite, includeBase, includeOverlays, ductVShift);
        if (side != null) {
            List<BakedQuad> culled = new ArrayList<>();
            for (BakedQuad q : built) {
                if (q.getDirection() == side) {
                    culled.add(q);
                }
            }
            return culled;
        }
        return built;
    }
}
