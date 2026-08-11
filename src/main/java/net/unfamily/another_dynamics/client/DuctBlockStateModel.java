package net.unfamily.another_dynamics.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import net.neoforged.neoforge.common.extensions.IBlockGetterExtension;
import net.neoforged.neoforge.model.data.ModelData;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctModelProperties;
import net.unfamily.another_dynamics.duct.DuctOpaqueRendering;

import org.jetbrains.annotations.Nullable;

/**
 * Dynamic {@link BlockStateModel} for composite ducts: reads {@link ModelData} from the block entity and
 * assembles {@link BakedQuad}s from {@link DuctCompositeGeometry}.
 */
public final class DuctBlockStateModel implements DynamicBlockStateModel {
    private static final float OPAQUE_TEXTURE_V_SHIFT_RATIO = 0.5f;

    private final Material.Baked fallbackParticleMaterial;
    private final String defaultDuctLogicalId;
    private final DuctCompositeGeometry fallbackGeometry;
    private final Map<String, DuctCompositeGeometry> geometryByLogicalId;

    public DuctBlockStateModel(
            BlockStateModel delegate,
            String defaultDuctLogicalId,
            DuctCompositeGeometry fallbackGeometry,
            Map<String, DuctCompositeGeometry> geometryByLogicalId) {
        this.fallbackParticleMaterial = delegate.particleMaterial();
        this.defaultDuctLogicalId = defaultDuctLogicalId;
        this.fallbackGeometry = fallbackGeometry;
        this.geometryByLogicalId = geometryByLogicalId;
    }

    @Override
    public void collectParts(
            BlockAndTintGetter level,
            BlockPos pos,
            BlockState state,
            RandomSource random,
            List<BlockStateModelPart> parts) {
        ModelData modelData =
                level instanceof IBlockGetterExtension ext ? ext.getModelData(pos) : ModelData.EMPTY;
        List<BakedQuad> quads = buildWorldQuads(level, pos, modelData);
        if (quads.isEmpty()) {
            return;
        }
        QuadCollection geometry = DuctRenderingSupport.quadsFromList(quads);
        Material.Baked particle = resolveParticleMaterial(modelData);
        parts.add(new SimpleModelWrapper(geometry, false, particle));
    }

    @Override
    public Material.Baked particleMaterial() {
        return fallbackParticleMaterial;
    }

    @Override
    public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        ModelData modelData =
                level instanceof IBlockGetterExtension ext ? ext.getModelData(pos) : ModelData.EMPTY;
        return resolveParticleMaterial(modelData);
    }

    @Override
    @BakedQuad.MaterialFlags
    public int materialFlags() {
        return fallbackGeometry != null && fallbackGeometry.isBuilt()
                ? DuctRenderingSupport.quadsFromList(fallbackGeometry.lineCenterQuads()).materialFlags()
                : 0;
    }

    @Override
    @BakedQuad.MaterialFlags
    public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        ModelData modelData =
                level instanceof IBlockGetterExtension ext ? ext.getModelData(pos) : ModelData.EMPTY;
        List<BakedQuad> quads = buildWorldQuads(level, pos, modelData);
        return quads.isEmpty() ? materialFlags() : DuctRenderingSupport.quadsFromList(quads).materialFlags();
    }

    @Override
    public @Nullable Object createGeometryKey(
            BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        ModelData modelData =
                level instanceof IBlockGetterExtension ext ? ext.getModelData(pos) : ModelData.EMPTY;
        return DuctGeometryKey.from(level, pos, modelData, defaultDuctLogicalId);
    }

    private Material.Baked resolveParticleMaterial(ModelData modelData) {
        String logicalId = effectiveDuctId(modelData);
        Map<String, DuctCompositeGeometry> cache = geometryByLogicalId();
        DuctCompositeGeometry geo =
                Optional.ofNullable(cache.get(logicalId))
                        .orElseGet(() -> cache.get(DuctRenderingSupport.ductIdFallbackKey()));
        if (geo != null && geo.isBuilt() && geo.mainSprite() != null) {
            return new Material.Baked(geo.mainSprite(), false);
        }
        return fallbackParticleMaterial;
    }

    private List<BakedQuad> buildWorldQuads(BlockAndTintGetter level, BlockPos pos, ModelData modelData) {
        Integer pipe = modelData.get(DuctModelProperties.PIPE_MASK);
        Integer storage = modelData.get(DuctModelProperties.STORAGE_MASK);
        Integer packedIcons = modelData.get(DuctModelProperties.NODE_ICONS_PACKED);
        int pipeMask = pipe != null ? pipe : 0;
        int storageMask = storage != null ? storage : 0;
        int packed = packedIcons != null ? packedIcons : 0;

        String effectiveDuctId = effectiveDuctId(modelData);
        DuctCompositeGeometry geometry = effectiveGeometry(effectiveDuctId);
        if (geometry == null || !geometry.isBuilt()) {
            return List.of();
        }

        boolean defOpaque =
                DuctDefinitionRegistry.getByLogicalId(effectiveDuctId)
                        .map(d -> d.alwaysOpaqueRendering())
                        .orElse(false);
        boolean networkOpaque = Boolean.TRUE.equals(modelData.get(DuctModelProperties.NETWORK_OPAQUE));
        boolean playerAll = DuctOpaqueRenderRefresh.snapshotPlayerAllOpaque();
        boolean opaqueRendering = defOpaque || playerAll || networkOpaque;

        float ductVShift = 0f;
        Material.Baked particle = resolveParticleMaterial(modelData);
        if (opaqueRendering && particle.sprite() != null) {
            TextureAtlasSprite sprite = particle.sprite();
            ductVShift = (sprite.getV1() - sprite.getV0()) * OPAQUE_TEXTURE_V_SHIFT_RATIO;
        }

        List<BakedQuad> built = new ArrayList<>();
        geometry.appendForWorldWithNodeIcons(
                built,
                pipeMask,
                storageMask,
                packed,
                DuctRenderingSupport.nodesSprite(),
                true,
                true,
                ductVShift);

        if (Boolean.TRUE.equals(modelData.get(DuctModelProperties.HAS_STALL))) {
            Integer stallMask = modelData.get(DuctModelProperties.STALL_MASK);
            int effectiveStall = stallMask != null ? stallMask : 0;
            effectiveStall &= storageMask;
            appendStallOverlayOnNodes(built, geometry, effectiveStall);
        }
        return built;
    }

    private void appendStallOverlayOnNodes(List<BakedQuad> out, DuctCompositeGeometry geo, int faceMask) {
        var sprite = DuctRenderingSupport.nodeBufferSprite();
        if (sprite == null) {
            return;
        }
        for (Direction face : Direction.values()) {
            if ((faceMask & (1 << face.ordinal())) == 0) {
                continue;
            }
            List<BakedQuad> src = geo.quadsNamed(DuctCompositeGeometry.nodePiece(face));
            for (BakedQuad q : src) {
                Direction qDir = q.direction();
                if (qDir == face || qDir == face.getOpposite()) {
                    continue;
                }
                BakedQuad overlay = DuctCompositeGeometry.buildFullSpriteOverlay(q, sprite);
                if (overlay != null) {
                    out.add(overlay);
                }
            }
        }
    }

    private String effectiveDuctId(ModelData modelData) {
        String fromBe = modelData.get(DuctModelProperties.DUCT_LOGICAL_ID);
        if (fromBe != null && !fromBe.isEmpty()) {
            return fromBe;
        }
        return defaultDuctLogicalId;
    }

    private DuctCompositeGeometry effectiveGeometry(String logicalId) {
        Map<String, DuctCompositeGeometry> cache = geometryByLogicalId();
        DuctCompositeGeometry geo =
                Optional.ofNullable(cache.get(logicalId))
                        .orElseGet(() -> cache.get(DuctRenderingSupport.ductIdFallbackKey()));
        if (geo != null && geo.isBuilt()) {
            return geo;
        }
        return fallbackGeometry;
    }

    private Map<String, DuctCompositeGeometry> geometryByLogicalId() {
        Map<String, DuctCompositeGeometry> global = DuctRenderingSupport.getGlobalGeometryCache();
        return global.isEmpty() ? geometryByLogicalId : global;
    }

    private record DuctGeometryKey(
            String ductLogicalId,
            int pipeMask,
            int storageMask,
            int packedIcons,
            int stallMask,
            boolean networkOpaque,
            boolean opaqueRendering) {
        static DuctGeometryKey from(
                BlockAndTintGetter level, BlockPos pos, ModelData modelData, String defaultDuctLogicalId) {
            Integer pipe = modelData.get(DuctModelProperties.PIPE_MASK);
            Integer storage = modelData.get(DuctModelProperties.STORAGE_MASK);
            Integer packed = modelData.get(DuctModelProperties.NODE_ICONS_PACKED);
            Integer stall = modelData.get(DuctModelProperties.STALL_MASK);
            String logicalId = modelData.get(DuctModelProperties.DUCT_LOGICAL_ID);
            if (logicalId == null || logicalId.isEmpty()) {
                logicalId = defaultDuctLogicalId;
            }
            boolean networkOpaque = Boolean.TRUE.equals(modelData.get(DuctModelProperties.NETWORK_OPAQUE));
            boolean opaque =
                    DuctOpaqueRendering.definitionAlwaysOpaque(logicalId)
                            || DuctOpaqueRenderRefresh.snapshotPlayerAllOpaque()
                            || networkOpaque;
            return new DuctGeometryKey(
                    logicalId,
                    pipe != null ? pipe : 0,
                    storage != null ? storage : 0,
                    packed != null ? packed : 0,
                    stall != null ? stall : 0,
                    networkOpaque,
                    opaque);
        }
    }
}
