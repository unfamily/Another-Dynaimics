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

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Immutable baked quads for one composite duct template pair ({@code model_default} + {@code model_line}).
 * Element names must match the engine ({@code center}, {@code con_*}, {@code node_*}, line {@code center}).
 */
public final class DuctCompositeGeometry {
    private static final boolean DEBUG_FORCE_NODE_ICON0 = false;
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

    /**
     * @param opaqueDuctTextureVShift atlas V delta for opaque skin on {@code center}, {@code con_*}, and line center;
     *        {@code 0} skips. {@code node_*} quads are added separately in {@link #appendNodeIcon} without this shift.
     */
    public void appendForWorldWithNodeIcons(
            List<BakedQuad> out,
            int pipeMask,
            int storageMask,
            int packedNodeIcons,
            TextureAtlasSprite nodesSprite,
            boolean includeBase,
            boolean includeOverlays,
            float opaqueDuctTextureVShift) {
        if (!built) {
            return;
        }
        DuctConnectionShape shape = DuctConnectionShape.classify(pipeMask, storageMask);
        switch (shape) {
            case SINGLE -> {
                if (includeBase) {
                    addPipeQuadsWithOptionalVShift(out, quadsNamed("center"), opaqueDuctTextureVShift);
                }
            }
            case PARTIAL -> {
                if (includeBase) {
                    addPipeQuadsWithOptionalVShift(out, quadsNamed("center"), opaqueDuctTextureVShift);
                }
                for (Direction d : Direction.values()) {
                    int bit = 1 << d.ordinal();
                    if ((pipeMask & bit) != 0) {
                        if (includeBase) {
                            addPipeQuadsWithOptionalVShift(
                                    out, quadsNamed(connectionPiece(d)), opaqueDuctTextureVShift);
                        }
                    }
                    if ((storageMask & bit) != 0) {
                        if (includeBase) {
                            addPipeQuadsWithOptionalVShift(
                                    out, quadsNamed(connectionPiece(d)), opaqueDuctTextureVShift);
                        }
                        appendNodeIcon(out, d, packedNodeIcons, nodesSprite, includeBase, includeOverlays);
                    }
                }
            }
            case LINE_X, LINE_Y, LINE_Z -> {
                Transformation tr = rotationForLineAxis(shape.lineAxis());
                if (includeBase) {
                    addPipeQuadsWithOptionalVShift(
                            out, transformQuads(lineCenterQuads(), tr), opaqueDuctTextureVShift);
                }
                Direction na = shape.lineEndNegative();
                Direction pb = shape.lineEndPositive();
                if ((storageMask & (1 << na.ordinal())) != 0) {
                    appendNodeIcon(out, na, packedNodeIcons, nodesSprite, includeBase, includeOverlays);
                }
                if ((storageMask & (1 << pb.ordinal())) != 0) {
                    appendNodeIcon(out, pb, packedNodeIcons, nodesSprite, includeBase, includeOverlays);
                }
            }
            case MULTI -> {
                if (includeBase) {
                    addPipeQuadsWithOptionalVShift(out, quadsNamed("center"), opaqueDuctTextureVShift);
                }
                for (Direction d : Direction.values()) {
                    int bit = 1 << d.ordinal();
                    if ((pipeMask & bit) != 0) {
                        if (includeBase) {
                            addPipeQuadsWithOptionalVShift(
                                    out, quadsNamed(connectionPiece(d)), opaqueDuctTextureVShift);
                        }
                    }
                    if ((storageMask & bit) != 0) {
                        if (includeBase) {
                            addPipeQuadsWithOptionalVShift(
                                    out, quadsNamed(connectionPiece(d)), opaqueDuctTextureVShift);
                        }
                    }
                }
                for (Direction d : Direction.values()) {
                    if ((storageMask & (1 << d.ordinal())) != 0) {
                        appendNodeIcon(out, d, packedNodeIcons, nodesSprite, includeBase, includeOverlays);
                    }
                }
            }
        }
    }

    private static void addPipeQuadsWithOptionalVShift(List<BakedQuad> out, List<BakedQuad> src, float dv) {
        if (src.isEmpty()) {
            return;
        }
        if (dv == 0.0f) {
            out.addAll(src);
            return;
        }
        for (BakedQuad q : src) {
            out.add(shiftQuadV(q, dv));
        }
    }

    private static BakedQuad shiftQuadV(BakedQuad q, float deltaV) {
        int stride = IQuadTransformer.STRIDE;
        int uv0 = IQuadTransformer.UV0;
        int[] v = q.getVertices();
        if (v == null || v.length < stride * 4) {
            return q;
        }
        int[] nv = v.clone();
        for (int k = 0; k < 4; k++) {
            int base = k * stride;
            float vv = Float.intBitsToFloat(nv[base + uv0 + 1]);
            nv[base + uv0 + 1] = Float.floatToRawIntBits(vv + deltaV);
        }
        return new BakedQuad(nv, q.getTintIndex(), q.getDirection(), q.getSprite(), q.isShade());
    }

    private void appendStorageNodesOnly(List<BakedQuad> out, int storageMask) {
        for (Direction d : Direction.values()) {
            if ((storageMask & (1 << d.ordinal())) != 0) {
                out.addAll(quadsNamed(nodePiece(d)));
            }
        }
    }

    private void appendNodeIcon(
            List<BakedQuad> out,
            Direction face,
            int packed,
            TextureAtlasSprite nodesSprite,
            boolean includeBase,
            boolean includeOverlays) {
        int idx = (packed >>> (face.ordinal() * 4)) & 0xF;
        if (DEBUG_FORCE_NODE_ICON0) {
            idx = 0;
        }
        List<BakedQuad> src = quadsNamed(nodePiece(face));
        for (BakedQuad q : src) {
            if (includeBase) {
                out.add(q);
            }
            Direction qDir = q.getDirection();
            // The icon should be visible on the 4 lateral sides of the node piece, not on the face pointing to storage.
            // For a node pointing to `face`, the lateral sides are the 4 directions perpendicular to `face`.
            if (qDir == face || qDir == face.getOpposite()) {
                continue;
            }
            if (!includeOverlays) {
                continue;
            }
            BakedQuad overlay = buildNodeIconOverlay(q, idx, nodesSprite);
            if (overlay != null) {
                out.add(overlay);
            }
        }
    }

    /**
     * Builds an overlay quad (same geometry) with UVs mapped onto nodes.png (64x32, 4x4 cells, each 16x8).
     *
     * NeoForge note: do not assume vertex layout indices; use {@link IQuadTransformer} offsets/stride.
     */
    private static @Nullable BakedQuad buildNodeIconOverlay(BakedQuad q, int iconIdx, TextureAtlasSprite nodesSprite) {
        if (nodesSprite == null) {
            return null;
        }
        if (iconIdx < 0 || iconIdx > 15) {
            return null;
        }
        int[] v = q.getVertices();
        if (v == null || v.length < IQuadTransformer.STRIDE * 4) {
            return null;
        }
        int stride = IQuadTransformer.STRIDE;
        int pos = IQuadTransformer.POSITION;
        int color = IQuadTransformer.COLOR;
        int uv0 = IQuadTransformer.UV0;
        float uMin = Float.POSITIVE_INFINITY;
        float uMax = Float.NEGATIVE_INFINITY;
        float vMin = Float.POSITIVE_INFINITY;
        float vMax = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            float u = Float.intBitsToFloat(v[base + uv0]);
            float vv = Float.intBitsToFloat(v[base + uv0 + 1]);
            uMin = Math.min(uMin, u);
            uMax = Math.max(uMax, u);
            vMin = Math.min(vMin, vv);
            vMax = Math.max(vMax, vv);
        }
        float w = uMax - uMin;
        float h = vMax - vMin;
        if (w <= 1e-6f || h <= 1e-6f) {
            return null;
        }
        int col = Math.floorMod(iconIdx, 4);
        int row = Math.floorDiv(iconIdx, 4);
        float u0 = nodesSprite.getU0();
        float u1 = nodesSprite.getU1();
        float vv0 = nodesSprite.getV0();
        float vv1 = nodesSprite.getV1();
        float du = u1 - u0;
        float dv = vv1 - vv0;
        float cellX = col * 16.0f;
        float cellY = row * 8.0f;
        int[] out = v.clone();
        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            // Push the overlay slightly outward to avoid Z-fighting with the original node face.
            float ox = Float.intBitsToFloat(out[base + pos]);
            float oy = Float.intBitsToFloat(out[base + pos + 1]);
            float oz = Float.intBitsToFloat(out[base + pos + 2]);
            float eps = 0.0005f;
            ox += q.getDirection().getStepX() * eps;
            oy += q.getDirection().getStepY() * eps;
            oz += q.getDirection().getStepZ() * eps;
            out[base + pos] = Float.floatToRawIntBits(ox);
            out[base + pos + 1] = Float.floatToRawIntBits(oy);
            out[base + pos + 2] = Float.floatToRawIntBits(oz);

            float ou = Float.intBitsToFloat(out[base + uv0]);
            float ov = Float.intBitsToFloat(out[base + uv0 + 1]);
            float tu = (ou - uMin) / w;
            float tv = (ov - vMin) / h;
            float nu = u0 + du * ((cellX + tu * 16.0f) / 64.0f);
            float nv = vv0 + dv * ((cellY + tv * 8.0f) / 32.0f);
            out[base + uv0] = Float.floatToRawIntBits(nu);
            out[base + uv0 + 1] = Float.floatToRawIntBits(nv);

            // 75% alpha to keep the node texture readable beneath.
            if (out.length > base + color) {
                int c = out[base + color];
                out[base + color] = (c & 0x00FFFFFF) | (0xBF << 24);
            }
        }
        BakedQuad overlay = new BakedQuad(out, -1, q.getDirection(), nodesSprite, false);
        // Make the icon readable even in darkness.
        overlay = QuadTransformers.settingMaxEmissivity().process(overlay);
        return overlay;
    }
}
