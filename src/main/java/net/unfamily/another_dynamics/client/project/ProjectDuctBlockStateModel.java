package net.unfamily.another_dynamics.client.project;

import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import net.neoforged.neoforge.common.extensions.IBlockGetterExtension;
import net.neoforged.neoforge.model.data.ModelData;
import net.unfamily.another_dynamics.client.DuctCompositeGeometry;
import net.unfamily.another_dynamics.client.DuctRenderingSupport;
import net.unfamily.another_dynamics.client.project.ProjectDuctTextures;
import net.unfamily.another_dynamics.duct.project.ProjectDuctBlock;
import net.unfamily.another_dynamics.duct.project.ProjectDuctModelProperties;

import org.jetbrains.annotations.Nullable;

/** Dynamic block model for project ducts (pipe mask from block state, node preview from model data). */
public final class ProjectDuctBlockStateModel implements DynamicBlockStateModel {
    /** North + south arms for item preview line. */
    private static final int ITEM_PIPE_MASK =
            (1 << net.minecraft.core.Direction.NORTH.ordinal()) | (1 << net.minecraft.core.Direction.SOUTH.ordinal());

    private final Material.Baked particleMaterial;
    private final DuctCompositeGeometry geometry;

    public ProjectDuctBlockStateModel(BlockStateModel delegate, DuctCompositeGeometry geometry) {
        this.particleMaterial = delegate.particleMaterial();
        this.geometry = geometry;
    }

    @Override
    public void collectParts(
            BlockAndTintGetter level,
            BlockPos pos,
            BlockState state,
            RandomSource random,
            List<BlockStateModelPart> parts) {
        if (!geometry.isBuilt()) {
            return;
        }
        int pipeMask = ProjectDuctBlock.effectivePipeMask(state);
        ModelData modelData =
                level instanceof IBlockGetterExtension ext ? ext.getModelData(pos) : ModelData.EMPTY;
        int nodeMask = nodePreviewFromModelData(modelData);
        java.util.ArrayList<BakedQuad> quads = new java.util.ArrayList<>();
        geometry.appendForWorld(quads, pipeMask, nodeMask);
        if (quads.isEmpty()) {
            return;
        }
        parts.add(new SimpleModelWrapper(DuctRenderingSupport.quadsFromList(quads), true, particleMaterial));
    }

    @Override
    public Material.Baked particleMaterial() {
        return particleMaterial;
    }

    @Override
    @BakedQuad.MaterialFlags
    public int materialFlags() {
        return geometry.isBuilt()
                ? DuctRenderingSupport.quadsFromList(geometry.lineCenterQuads()).materialFlags()
                : 0;
    }

    @Override
    public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return particleMaterial;
    }

    @Override
    @BakedQuad.MaterialFlags
    public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return materialFlags();
    }

    @Override
    public @Nullable Object createGeometryKey(
            BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        ModelData modelData =
                level instanceof IBlockGetterExtension ext ? ext.getModelData(pos) : ModelData.EMPTY;
        return new ProjectKey(ProjectDuctBlock.effectivePipeMask(state), nodePreviewFromModelData(modelData));
    }

    public List<BakedQuad> itemPreviewQuads() {
        java.util.ArrayList<BakedQuad> out = new java.util.ArrayList<>();
        geometry.appendForWorld(out, ITEM_PIPE_MASK, 0);
        return out;
    }

    private static int nodePreviewFromModelData(ModelData modelData) {
        if (modelData == null || !modelData.has(ProjectDuctModelProperties.NODE_PREVIEW_MASK)) {
            return 0;
        }
        Integer mask = modelData.get(ProjectDuctModelProperties.NODE_PREVIEW_MASK);
        return mask != null ? mask : 0;
    }

    public static DuctCompositeGeometry bakeGeometry() {
        var getter = DuctRenderingSupport.blockAtlasSpriteGetter();
        return DuctCompositeGeometry.bake(
                Identifier.fromNamespaceAndPath(
                        net.unfamily.another_dynamics.AnotherDynamicsMod.MOD_ID, "block/project_duct_default"),
                Identifier.fromNamespaceAndPath(
                        net.unfamily.another_dynamics.AnotherDynamicsMod.MOD_ID, "block/project_duct_line"),
                ProjectDuctTextures.BLOCK_TEXTURE,
                getter);
    }

    private record ProjectKey(int pipeMask, int nodeMask) {}
}
