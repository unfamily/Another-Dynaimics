package net.unfamily.another_dynamics.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.unfamily.another_dynamics.duct.DuctModelProperties;

import org.jetbrains.annotations.Nullable;

public final class DuctBakedModel extends BakedModelWrapper<BakedModel> {
    private static final ChunkRenderTypeSet BLOCK_RENDER_TYPES = ChunkRenderTypeSet.of(RenderType.cutoutMipped());
    private static final List<RenderType> ITEM_RENDER_TYPES = List.of(RenderType.cutout());

    private final DuctGeometryCache geometry;
    private final TextureAtlasSprite particleSprite;

    public DuctBakedModel(BakedModel original, DuctGeometryCache geometry, TextureAtlasSprite particleSprite) {
        super(original);
        this.geometry = geometry;
        this.particleSprite = particleSprite;
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
        int pm = pipe != null ? pipe : 0;
        int sm = storage != null ? storage : 0;
        List<BakedQuad> built = new ArrayList<>();
        geometry.appendForWorld(built, pm, sm);
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
