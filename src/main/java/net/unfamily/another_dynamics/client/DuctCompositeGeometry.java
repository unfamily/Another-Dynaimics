package net.unfamily.another_dynamics.client;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.math.Transformation;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.ExtendedBlockModelDeserializer;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.QuadTransformers;
import net.neoforged.neoforge.client.model.SimpleModelState;
import net.neoforged.neoforge.client.model.geometry.UnbakedGeometryHelper;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctConnectionShape;
import net.unfamily.another_dynamics.registry.ModBlocks;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Immutable baked quads for one composite duct template pair ({@code model_default} + {@code model_line}).
 * Element names must match the engine ({@code center}, {@code con_*}, {@code node_*}, line {@code center}).
 */
public final class DuctCompositeGeometry {
    public static final ResourceLocation DEFAULT_MODEL_DEFAULT =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "block/simple_duct_default");
    public static final ResourceLocation DEFAULT_MODEL_LINE =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "block/simple_duct_line");

    private final Map<String, List<BakedQuad>> defaultByName;
    private final List<BakedQuad> lineCenterQuadsIdentity;
    private final boolean built;

    private DuctCompositeGeometry(Map<String, List<BakedQuad>> defaultByName, List<BakedQuad> lineCenterQuads, boolean built) {
        this.defaultByName = defaultByName;
        this.lineCenterQuadsIdentity = lineCenterQuads;
        this.built = built;
    }

    public static DuctCompositeGeometry bake(
            ResourceLocation modelDefaultId,
            ResourceLocation modelLineId,
            String textureRlString,
            Function<Material, TextureAtlasSprite> spriteGetter) {
        Map<String, List<BakedQuad>> byName = new HashMap<>();
        List<BakedQuad> lineCenter = List.of();
        try {
            ParsedModel defParsed = readModel(modelDefaultId, textureRlString);
            ParsedModel lineParsed = readModel(modelLineId, textureRlString);
            var identity = new SimpleModelState(Transformation.identity());
            List<BlockElement> defElements = defParsed.model().getElements();
            List<String> defNames = defParsed.elementNames();
            for (int i = 0; i < defElements.size(); i++) {
                String name = i < defNames.size() ? defNames.get(i) : null;
                if (name == null) {
                    continue;
                }
                BlockElement el = defElements.get(i);
                List<BakedQuad> quads = UnbakedGeometryHelper.bakeElements(List.of(el), spriteGetter, identity);
                byName.put(name, quads);
            }
            List<BlockElement> lineEls = lineParsed.model().getElements();
            List<String> lineNames = lineParsed.elementNames();
            List<BlockElement> lineCenterElements = new ArrayList<>();
            for (int i = 0; i < lineEls.size(); i++) {
                if (i < lineNames.size() && "center".equals(lineNames.get(i))) {
                    lineCenterElements.add(lineEls.get(i));
                }
            }
            lineCenter = UnbakedGeometryHelper.bakeElements(lineCenterElements, spriteGetter, identity);
            return new DuctCompositeGeometry(Map.copyOf(byName), lineCenter, true);
        } catch (Exception ex) {
            AnotherDynamicsMod.LOGGER.error(
                    "Failed to bake duct composite geometry (default={}, line={})",
                    modelDefaultId,
                    modelLineId,
                    ex);
            return new DuctCompositeGeometry(Map.of(), List.of(), false);
        }
    }

    private static String classpathModelPath(ResourceLocation modelId) {
        return "/assets/" + modelId.getNamespace() + "/models/" + modelId.getPath() + ".json";
    }

    private static void resolveFaceTextures(JsonObject root, String textureRlString) {
        if (!root.has("elements")) {
            return;
        }
        JsonArray elements = root.getAsJsonArray("elements");
        for (JsonElement el : elements) {
            if (!el.isJsonObject() || !el.getAsJsonObject().has("faces")) {
                continue;
            }
            JsonObject faces = el.getAsJsonObject().getAsJsonObject("faces");
            for (Map.Entry<String, JsonElement> face : faces.entrySet()) {
                if (!face.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject fo = face.getValue().getAsJsonObject();
                if (fo.has("texture") && fo.get("texture").getAsString().startsWith("#")) {
                    fo.addProperty("texture", textureRlString);
                }
            }
        }
    }

    private record ParsedModel(BlockModel model, List<String> elementNames) {}

    private static List<String> extractElementNames(JsonObject root) {
        if (!root.has("elements")) {
            return List.of();
        }
        JsonArray elements = root.getAsJsonArray("elements");
        List<String> names = new ArrayList<>(elements.size());
        for (JsonElement el : elements) {
            if (el.isJsonObject() && el.getAsJsonObject().has("name")) {
                names.add(el.getAsJsonObject().get("name").getAsString());
            } else {
                names.add(null);
            }
        }
        return names;
    }

    private static ParsedModel readModel(ResourceLocation modelId, String textureRlString) throws Exception {
        String cp = classpathModelPath(modelId);
        var stream = ModBlocks.class.getResourceAsStream(cp);
        if (stream == null) {
            throw new IllegalStateException("Missing model resource: " + modelId + " (" + cp + ")");
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            if (obj.has("textures")) {
                JsonObject tex = obj.getAsJsonObject("textures");
                tex.remove("render_type");
                tex.addProperty("0", textureRlString);
                tex.addProperty("particle", textureRlString);
            }
            resolveFaceTextures(obj, textureRlString);
            List<String> elementNames = extractElementNames(obj);
            BlockModel model = ExtendedBlockModelDeserializer.INSTANCE.fromJson(obj, BlockModel.class);
            return new ParsedModel(model, Collections.unmodifiableList(elementNames));
        }
    }

    public boolean isBuilt() {
        return built;
    }

    public List<BakedQuad> quadsNamed(String name) {
        return defaultByName.getOrDefault(name, List.of());
    }

    public List<BakedQuad> lineCenterQuads() {
        return lineCenterQuadsIdentity;
    }

    public List<BakedQuad> transformQuads(List<BakedQuad> source, Transformation transform) {
        if (transform == null || transform.isIdentity()) {
            return List.copyOf(source);
        }
        IQuadTransformer transformer = QuadTransformers.applying(transform);
        List<BakedQuad> out = new ArrayList<>(source.size());
        for (BakedQuad q : source) {
            out.add(transformer.process(q));
        }
        return out;
    }

    public static Transformation rotationForLineAxis(Direction.Axis axis) {
        Matrix4f m = new Matrix4f();
        m.translation(0.5f, 0.5f, 0.5f);
        switch (axis) {
            case X -> m.rotate(new Quaternionf().rotateY((float) (-Math.PI / 2)));
            case Y -> m.rotate(new Quaternionf().rotateX((float) (Math.PI / 2)));
            case Z -> {}
        }
        m.translate(-0.5f, -0.5f, -0.5f);
        return new Transformation(m);
    }

    public static String connectionPiece(Direction dir) {
        return switch (dir) {
            case UP -> "con_N";
            case DOWN -> "con_D";
            case NORTH -> "con_U";
            case SOUTH -> "con_S";
            case EAST -> "con_E";
            case WEST -> "con_W";
        };
    }

    public static String nodePiece(Direction dir) {
        return switch (dir) {
            case UP -> "node_U";
            case DOWN -> "node_D";
            case NORTH -> "node_N";
            case SOUTH -> "node_S";
            case EAST -> "node_E";
            case WEST -> "node_W";
        };
    }

    public void appendForWorld(List<BakedQuad> out, int pipeMask, int storageMask) {
        if (!built) {
            return;
        }
        DuctConnectionShape shape = DuctConnectionShape.classify(pipeMask, storageMask);
        switch (shape) {
            case SINGLE -> out.addAll(quadsNamed("center"));
            case PARTIAL -> {
                out.addAll(quadsNamed("center"));
                for (Direction d : Direction.values()) {
                    int bit = 1 << d.ordinal();
                    if ((pipeMask & bit) != 0) {
                        out.addAll(quadsNamed(connectionPiece(d)));
                    }
                    if ((storageMask & bit) != 0) {
                        out.addAll(quadsNamed(connectionPiece(d)));
                        out.addAll(quadsNamed(nodePiece(d)));
                    }
                }
            }
            case LINE_X, LINE_Y, LINE_Z -> {
                Transformation tr = rotationForLineAxis(shape.lineAxis());
                out.addAll(transformQuads(lineCenterQuads(), tr));
                Direction na = shape.lineEndNegative();
                Direction pb = shape.lineEndPositive();
                if ((storageMask & (1 << na.ordinal())) != 0) {
                    out.addAll(quadsNamed(nodePiece(na)));
                }
                if ((storageMask & (1 << pb.ordinal())) != 0) {
                    out.addAll(quadsNamed(nodePiece(pb)));
                }
            }
            case MULTI -> {
                out.addAll(quadsNamed("center"));
                for (Direction d : Direction.values()) {
                    int bit = 1 << d.ordinal();
                    if ((pipeMask & bit) != 0) {
                        out.addAll(quadsNamed(connectionPiece(d)));
                    }
                    if ((storageMask & bit) != 0) {
                        out.addAll(quadsNamed(connectionPiece(d)));
                    }
                }
                appendStorageNodesOnly(out, storageMask);
            }
        }
    }

    private void appendStorageNodesOnly(List<BakedQuad> out, int storageMask) {
        for (Direction d : Direction.values()) {
            if ((storageMask & (1 << d.ordinal())) != 0) {
                out.addAll(quadsNamed(nodePiece(d)));
            }
        }
    }
}
