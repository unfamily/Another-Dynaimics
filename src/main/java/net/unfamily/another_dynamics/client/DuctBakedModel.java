package net.unfamily.another_dynamics.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    /**
     * Populated at {@code ModelEvent.BakingCompleted} when the full texture atlas is available.
     * All DuctBakedModel instances share this cache; it supersedes the per-instance lazy rebuild.
     * Follows the same pattern used by Custom-Machinery's {@code onBakingCompleted} hook.
     */
    private static volatile Map<String, DuctCompositeGeometry> GLOBAL_GEOMETRY_CACHE = Map.of();

    public static void updateGlobalGeometryCache(Map<String, DuctCompositeGeometry> cache) {
        GLOBAL_GEOMETRY_CACHE = cache;
    }

    /**
     * Returns the geometry cache for all duct logical IDs.
     * If the cache from {@code ModelEvent.BakingCompleted} is not yet available, performs a
     * lazy rebuild using the live block atlas and stores the result for subsequent calls.
     */
    public static Map<String, DuctCompositeGeometry> getGlobalGeometryCache() {
        Map<String, DuctCompositeGeometry> cache = GLOBAL_GEOMETRY_CACHE;
        if (!cache.isEmpty()) return cache;
        Function<Material, TextureAtlasSprite> spriteGetter = mat ->
                Minecraft.getInstance().getTextureAtlas(mat.atlasLocation()).apply(mat.texture());
        Map<String, DuctCompositeGeometry> built = bakeAllGeometries(spriteGetter, null);
        if (!built.isEmpty()) {
            GLOBAL_GEOMETRY_CACHE = built;
            return built;
        }
        return cache;
    }

    private final DuctCompositeGeometry geometry;
    private final AtomicReference<Map<String, DuctCompositeGeometry>> geometryByLogicalIdRef;
    private final AtomicInteger lastKnownRegistryVersion = new AtomicInteger(-1);
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
        this.geometryByLogicalIdRef = new AtomicReference<>(geometryByLogicalId);
        this.itemForceDefinitionOpaqueOnly = itemForceDefinitionOpaqueOnly;
        this.itemFixedLogicalId = itemFixedLogicalId;
    }

    /**
     * Returns the geometry map to use for this model instance.
     *
     * <p>Priority order:
     * <ol>
     *   <li>The global cache populated at {@code ModelEvent.BakingCompleted} (full atlas, all types
     *       guaranteed built). This is the primary path after startup.
     *   <li>Lazy per-instance rebuild using the live block-atlas getter (fallback for edge cases such
     *       as the very first render frame before BakingCompleted fires).
     * </ol>
     */
    private Map<String, DuctCompositeGeometry> geometryByLogicalId() {
        // Primary: global cache built at BakingCompleted with the complete texture atlas.
        Map<String, DuctCompositeGeometry> global = GLOBAL_GEOMETRY_CACHE;
        if (!global.isEmpty()) {
            return global;
        }
        // Fallback: lazy rebuild if BakingCompleted has not fired yet.
        int registryVersion = DuctDefinitionRegistry.version();
        if (lastKnownRegistryVersion.get() != registryVersion) {
            Function<Material, TextureAtlasSprite> spriteGetter = mat ->
                    Minecraft.getInstance().getTextureAtlas(mat.atlasLocation()).apply(mat.texture());
            Map<String, DuctCompositeGeometry> rebuilt = bakeAllGeometries(spriteGetter, geometry);
            Map<String, DuctCompositeGeometry> current = geometryByLogicalIdRef.get();
            if (rebuilt.size() >= current.size()) {
                geometryByLogicalIdRef.set(rebuilt);
            }
            lastKnownRegistryVersion.set(registryVersion);
            return geometryByLogicalIdRef.get();
        }
        return geometryByLogicalIdRef.get();
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
                DuctCompositeGeometry baked = DuctCompositeGeometry.bake(modelDefault, modelLine, texStr, spriteGetter);
                // Only add built geometries: unbuilt entries would cause sub-models to receive
                // geometry.isBuilt()==false, triggering the early return before getItemQuads() runs.
                if (baked.isBuilt()) {
                    out.put(logicalId, baked);
                }
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
                Map<String, DuctCompositeGeometry> gMap = geometryByLogicalId();
                final DuctCompositeGeometry g0 =
                        Optional.ofNullable(gMap.get(id))
                                .orElseGet(() -> gMap.getOrDefault(ductIdFallbackKey(), geometry));
                final DuctCompositeGeometry g = g0 != null ? g0 : geometry;
                if (g == null) {
                    return DuctBakedModel.this;
                }
                return itemModelsByLogicalId.computeIfAbsent(
                        id,
                        k -> new DuctBakedModel(originalModel, g, particleSprite, nodesSprite, ductLogicalId, gMap, true, k));
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

    /**
     * NeoForge's patched {@code ItemRenderer} iterates {@code getRenderPasses()} and calls
     * {@code renderModelLists(model)} for each returned model — NOT on {@code this} directly.
     * {@link BakedModelWrapper} delegates this to {@code originalModel}, which returns
     * {@code [baker.bake(SIMPLE_DUCT_LINE)]} — bypassing all of our {@code getQuads()} logic.
     * Returning {@code this} ensures the render loop reaches our override.
     */
    @Override
    public List<BakedModel> getRenderPasses(ItemStack itemStack, boolean fabulous) {
        return List.of(this);
    }

    /**
     * Vanilla {@code ItemRenderer.renderModelLists} (not patched by NeoForge) calls the
     * 3-parameter {@code getQuads(state, side, rand)} — NOT the NeoForge 5-param version.
     * {@link BakedModelWrapper} delegates this to {@code originalModel}, bypassing our logic.
     * This override routes item rendering ({@code state == null}) through {@link #getItemQuads}.
     */
    @SuppressWarnings("deprecation")
    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
        if (state == null) {
            return getItemQuads(side, null);
        }
        return originalModel.getQuads(state, side, rand);
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return particleSprite != null ? particleSprite : originalModel.getParticleIcon();
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        if (data != null) {
            String logicalId = data.get(DuctModelProperties.DUCT_LOGICAL_ID);
            if (logicalId != null && !logicalId.isEmpty()) {
                Map<String, DuctCompositeGeometry> gMap = geometryByLogicalId();
                DuctCompositeGeometry geo = gMap.get(logicalId);
                if (geo != null && geo.isBuilt()) {
                    TextureAtlasSprite s = geo.mainSprite();
                    if (s != null) return s;
                }
            }
        }
        return particleSprite != null ? particleSprite : originalModel.getParticleIcon(data);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand, ModelData modelData, @Nullable RenderType renderType) {
        if (!geometry.isBuilt()) {
            return originalModel.getQuads(state, side, rand, modelData, renderType);
        }
        // Item rendering (blockState == null): always show line shape along Z axis.
        if (state == null && !(originalModel instanceof DuctBakedModel)) {
            return getItemQuads(side, renderType);
        }
        Integer pipe = modelData.get(DuctModelProperties.PIPE_MASK);
        Integer storage = modelData.get(DuctModelProperties.STORAGE_MASK);
        Integer packedIcons = modelData.get(DuctModelProperties.NODE_ICONS_PACKED);
        int pm = pipe != null ? pipe : 0;
        int sm = storage != null ? storage : 0;
        List<BakedQuad> built = new ArrayList<>();
        int pi = packedIcons != null ? packedIcons : 0;
        // RenderType instances are not guaranteed to be reference-equal across calls; use a robust check.
        String rt = renderType != null ? renderType.toString() : "";
        boolean isOverlayPass = renderType != null && rt.startsWith("RenderType[translucent");
        // World uses cutout_mipped; item uses cutout. Treat both as the base pass.
        boolean isBasePass = renderType == null || rt.startsWith("RenderType[cutout_mipped") || rt.startsWith("RenderType[cutout");
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
        Map<String, DuctCompositeGeometry> gMap = geometryByLogicalId();
        DuctCompositeGeometry effectiveGeometry =
                Optional.ofNullable(gMap.get(effectiveDuctId))
                        .orElseGet(() -> gMap.getOrDefault(ductIdFallbackKey(), geometry));
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

    /**
     * Builds quads for item-in-hand/GUI rendering: always shows a line (Z axis) with the correct
     * per-definition texture and optional opaque V-shift.
     */
    private List<BakedQuad> getItemQuads(@Nullable Direction side, @Nullable RenderType renderType) {
        String rt = renderType != null ? renderType.toString() : "";
        boolean isBasePass = renderType == null || rt.startsWith("RenderType[cutout_mipped") || rt.startsWith("RenderType[cutout");
        if (!isBasePass) {
            return List.of();
        }
        String resolvedId = itemFixedLogicalId != null ? itemFixedLogicalId : ductLogicalId;
        Map<String, DuctCompositeGeometry> gMap = geometryByLogicalId();
        DuctCompositeGeometry effectiveGeometry =
                Optional.ofNullable(gMap.get(resolvedId))
                        .orElseGet(() -> gMap.getOrDefault(ductIdFallbackKey(), geometry));
        if (effectiveGeometry == null || !effectiveGeometry.isBuilt()) {
            // Fall back to the default type's geometry which is always built.
            DuctCompositeGeometry def = gMap.get(DuctIds.DEFAULT_LOGICAL_ID);
            effectiveGeometry = (def != null && def.isBuilt()) ? def : null;
            if (effectiveGeometry == null) {
                return List.of();
            }
        }
        boolean defOpaque =
                DuctDefinitionRegistry.getByLogicalId(resolvedId)
                        .map(d -> d.alwaysOpaqueRendering())
                        .orElse(false);
        float ductVShift = 0f;
        if (defOpaque) {
            // Compute V-shift from the first quad of the geometry so it matches the actual sprite used.
            List<BakedQuad> sample = effectiveGeometry.lineAllQuads();
            if (!sample.isEmpty() && sample.get(0).getSprite() != null) {
                TextureAtlasSprite s = sample.get(0).getSprite();
                ductVShift = (s.getV1() - s.getV0()) * OPAQUE_TEXTURE_V_SHIFT_RATIO;
            } else if (particleSprite != null) {
                ductVShift = (particleSprite.getV1() - particleSprite.getV0()) * OPAQUE_TEXTURE_V_SHIFT_RATIO;
            }
        }
        // Use the full line model quads (center + end caps) for item display.
        List<BakedQuad> lineQuads = effectiveGeometry.lineAllQuads();
        List<BakedQuad> built = new ArrayList<>(lineQuads.size());
        if (ductVShift != 0f) {
            for (BakedQuad q : lineQuads) {
                built.add(DuctCompositeGeometry.shiftQuadV(q, ductVShift));
            }
        } else {
            built.addAll(lineQuads);
        }
        if (side != null) {
            built.removeIf(q -> q.getDirection() != side);
        }
        return built;
    }
}
