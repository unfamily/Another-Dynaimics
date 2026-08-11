package net.unfamily.another_dynamics.client.project;

import java.util.List;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.geometry.BakedQuad;
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
import net.unfamily.another_dynamics.duct.project.ProjectDuctBlock;
import net.unfamily.another_dynamics.duct.project.ProjectDuctModelProperties;

import org.jetbrains.annotations.Nullable;

/** Dynamic block model for project ducts (pipe mask from block state, node preview from model data). */
public final class ProjectDuctBlockStateModel implements DynamicBlockStateModel {
    private final Material.Baked particleMaterial;

    public ProjectDuctBlockStateModel(BlockStateModel delegate) {
        this.particleMaterial = delegate.particleMaterial();
    }

    private DuctCompositeGeometry geometry() {
        return DuctRenderingSupport.getProjectGeometry();
    }

    @Override
    public void collectParts(
            BlockAndTintGetter level,
            BlockPos pos,
            BlockState state,
            RandomSource random,
            List<BlockStateModelPart> parts) {
        DuctCompositeGeometry geometry = geometry();
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
        DuctCompositeGeometry geometry = geometry();
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

    private static int nodePreviewFromModelData(ModelData modelData) {
        if (modelData == null || !modelData.has(ProjectDuctModelProperties.NODE_PREVIEW_MASK)) {
            return 0;
        }
        Integer mask = modelData.get(ProjectDuctModelProperties.NODE_PREVIEW_MASK);
        return mask != null ? mask : 0;
    }

    public static DuctCompositeGeometry bakeGeometry(
            java.util.function.Function<
                            net.minecraft.client.resources.model.sprite.SpriteId,
                            net.minecraft.client.renderer.texture.TextureAtlasSprite>
                    spriteGetter) {
        return DuctCompositeGeometry.bake(
                Identifier.fromNamespaceAndPath(
                        net.unfamily.another_dynamics.AnotherDynamicsMod.MOD_ID, "block/project_duct_default"),
                Identifier.fromNamespaceAndPath(
                        net.unfamily.another_dynamics.AnotherDynamicsMod.MOD_ID, "block/project_duct_line"),
                ProjectDuctTextures.BLOCK_TEXTURE,
                spriteGetter);
    }

    private record ProjectKey(int pipeMask, int nodeMask) {}
}
