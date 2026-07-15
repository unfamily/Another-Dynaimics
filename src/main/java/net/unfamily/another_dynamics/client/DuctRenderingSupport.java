package net.unfamily.another_dynamics.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctTextures;

/**
 * Shared baked composite geometry cache and quad helpers for duct block/item rendering (26.x).
 */
public final class DuctRenderingSupport {
    private static volatile Map<String, DuctCompositeGeometry> GLOBAL_GEOMETRY_CACHE = Map.of();
    private static volatile DuctCompositeGeometry PROJECT_GEOMETRY = DuctCompositeGeometry.emptyGeometry();
    private static volatile TextureAtlasSprite NODES_SPRITE;
    private static volatile TextureAtlasSprite NODE_BUFFER_SPRITE;

    private DuctRenderingSupport() {}

    public static void updateGlobalGeometryCache(Map<String, DuctCompositeGeometry> cache) {
        GLOBAL_GEOMETRY_CACHE = cache;
    }

    public static void updateProjectGeometry(DuctCompositeGeometry geometry) {
        PROJECT_GEOMETRY = geometry != null ? geometry : DuctCompositeGeometry.emptyGeometry();
    }

    public static DuctCompositeGeometry getProjectGeometry() {
        return PROJECT_GEOMETRY;
    }

    public static void updateOverlaySprites(TextureAtlasSprite nodesSprite, TextureAtlasSprite nodeBufferSprite) {
        NODES_SPRITE = nodesSprite;
        NODE_BUFFER_SPRITE = nodeBufferSprite;
    }

    public static TextureAtlasSprite nodesSprite() {
        return NODES_SPRITE;
    }

    public static TextureAtlasSprite nodeBufferSprite() {
        return NODE_BUFFER_SPRITE;
    }

    public static void invalidateGlobalGeometryCacheForReload() {
        GLOBAL_GEOMETRY_CACHE = Map.of();
        PROJECT_GEOMETRY = DuctCompositeGeometry.emptyGeometry();
    }

    public static Map<String, DuctCompositeGeometry> getGlobalGeometryCache() {
        return GLOBAL_GEOMETRY_CACHE;
    }

    public static Map<String, DuctCompositeGeometry> bakeAllGeometries(
            Function<SpriteId, TextureAtlasSprite> spriteGetter,
            DuctCompositeGeometry fallbackGeometry) {
        Map<String, DuctCompositeGeometry> out = new HashMap<>();
        for (DuctDefinition def : DuctDefinitionRegistry.all().values()) {
            String logicalId = def.logicalId();
            try {
                Identifier texId = DuctTextures.compositeBlockTexture(logicalId);
                var modelDefault = def.compositeModelDefault().orElse(DuctCompositeGeometry.DEFAULT_MODEL_DEFAULT);
                var modelLine = def.compositeModelLine().orElse(DuctCompositeGeometry.DEFAULT_MODEL_LINE);
                DuctCompositeGeometry baked = DuctCompositeGeometry.bake(modelDefault, modelLine, texId, spriteGetter);
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

    public static String ductIdFallbackKey() {
        return "__fallback__";
    }

    public static QuadCollection quadsFromList(List<BakedQuad> quads) {
        if (quads.isEmpty()) {
            return QuadCollection.EMPTY;
        }
        QuadCollection.Builder builder = new QuadCollection.Builder();
        for (BakedQuad quad : quads) {
            Direction direction = quad.direction();
            if (direction == null) {
                builder.addUnculledFace(quad);
            } else {
                builder.addCulledFace(direction, quad);
            }
        }
        return builder.build();
    }

    public static Function<SpriteId, TextureAtlasSprite> blockAtlasSpriteGetter() {
        return id -> Minecraft.getInstance().getAtlasManager().get(id);
    }

    public static SpriteId blockSprite(Identifier texture) {
        return new SpriteId(TextureAtlas.LOCATION_BLOCKS, texture);
    }
}
