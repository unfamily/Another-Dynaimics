package net.unfamily.another_dynamics.client;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.math.Transformation;

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraft.client.resources.model.cuboid.CuboidFace;
import net.minecraft.client.resources.model.cuboid.CuboidModelElement;
import net.minecraft.client.resources.model.cuboid.FaceBakery;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.model.quad.MutableQuad;
import net.neoforged.neoforge.client.model.quad.QuadTransforms;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctConnectionShape;
import net.unfamily.another_dynamics.registry.ModBlocks;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Immutable baked quads for one composite duct template pair ({@code model_default} + {@code model_line}).
 * Element names must match the engine ({@code center}, {@code con_*}, {@code node_*}, line {@code center}).
 *
 * <p>26.x rewrite: geometry is baked into the new {@link BakedQuad} record ({@code Vector3fc} positions,
 * packed-long UVs) via {@link FaceBakery}; UV/vertex manipulation uses {@link MutableQuad}.
 */
public final class DuctCompositeGeometry {
    private static final boolean DEBUG_FORCE_NODE_ICON0 = false;

    public static final Identifier DEFAULT_MODEL_DEFAULT =
            Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "block/simple_duct_default");
    public static final Identifier DEFAULT_MODEL_LINE =
            Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "block/simple_duct_line");

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(CuboidModelElement.class, new CuboidModelElement.Deserializer())
            .registerTypeAdapter(CuboidFace.class, new CuboidFace.Deserializer())
            .create();

    /** Identity {@link ModelState} for baking (no rotation/translation). */
    private static final ModelState IDENTITY_STATE = new ModelState() {};

    private final Map<String, List<BakedQuad>> defaultByName;
    private final List<BakedQuad> lineCenterQuadsIdentity;
    private final List<BakedQuad> lineAllQuadsIdentity;
    private final List<BakedQuad> lineNodeCapsQuadsIdentity;
    private final @Nullable TextureAtlasSprite mainSprite;
    private final boolean built;

    private DuctCompositeGeometry(
            Map<String, List<BakedQuad>> defaultByName,
            List<BakedQuad> lineCenterQuads,
            List<BakedQuad> lineAllQuads,
            List<BakedQuad> lineNodeCapsQuads,
            @Nullable TextureAtlasSprite mainSprite,
            boolean built) {
        this.defaultByName = defaultByName;
        this.lineCenterQuadsIdentity = lineCenterQuads;
        this.lineAllQuadsIdentity = lineAllQuads;
        this.lineNodeCapsQuadsIdentity = lineNodeCapsQuads;
        this.mainSprite = mainSprite;
        this.built = built;
    }

    public static DuctCompositeGeometry bake(
            Identifier modelDefaultId, Identifier modelLineId, Identifier textureId, Function<SpriteId, TextureAtlasSprite> spriteGetter) {
        TextureAtlasSprite ductSprite = spriteGetter.apply(DuctRenderingSupport.blockSprite(textureId));
        return bake(modelDefaultId, modelLineId, ductSprite);
    }

    public static DuctCompositeGeometry bake(
            Identifier modelDefaultId, Identifier modelLineId, TextureAtlasSprite ductSprite) {
        if (ductSprite == null) {
            return empty();
        }
        Material.Baked material = new Material.Baked(ductSprite, false);
        try {
            Map<String, List<BakedQuad>> byName = new HashMap<>();
            ParsedModel defParsed = readModel(modelDefaultId);
            for (int i = 0; i < defParsed.elements().size(); i++) {
                String name = i < defParsed.names().size() ? defParsed.names().get(i) : null;
                if (name == null) {
                    continue;
                }
                byName.put(name, bakeElement(defParsed.elements().get(i), material));
            }

            ParsedModel lineParsed = readModel(modelLineId);
            List<BakedQuad> lineCenter = new ArrayList<>();
            List<BakedQuad> lineAll = new ArrayList<>();
            List<BakedQuad> lineNodeCaps = new ArrayList<>();
            for (int i = 0; i < lineParsed.elements().size(); i++) {
                String name = i < lineParsed.names().size() ? lineParsed.names().get(i) : null;
                List<BakedQuad> quads = bakeElement(lineParsed.elements().get(i), material);
                lineAll.addAll(quads);
                if ("center".equals(name)) {
                    lineCenter.addAll(quads);
                } else {
                    lineNodeCaps.addAll(quads);
                }
            }
            return new DuctCompositeGeometry(
                    Map.copyOf(byName),
                    List.copyOf(lineCenter),
                    List.copyOf(lineAll),
                    List.copyOf(lineNodeCaps),
                    ductSprite,
                    true);
        } catch (Exception ex) {
            AnotherDynamicsMod.LOGGER.error(
                    "Failed to bake duct composite geometry (default={}, line={})",
                    modelDefaultId,
                    modelLineId,
                    ex);
            return empty();
        }
    }

    private static DuctCompositeGeometry empty() {
        return new DuctCompositeGeometry(Map.of(), List.of(), List.of(), List.of(), null, false);
    }

    /** Shared empty geometry placeholder used before model baking completes. */
    public static DuctCompositeGeometry emptyGeometry() {
        return empty();
    }

    // -------- baking primitives --------

    private static List<BakedQuad> bakeElement(CuboidModelElement element, Material.Baked material) {
        List<BakedQuad> out = new ArrayList<>();
        element.faces().forEach((side, face) -> out.add(
                FaceBakery.bakeQuad(
                        DuctModelBaker.INSTANCE,
                        element.from(),
                        element.to(),
                        face,
                        material,
                        side,
                        IDENTITY_STATE,
                        element.rotation(),
                        element.shade(),
                        element.lightEmission())));
        return out;
    }

    private record ParsedModel(List<CuboidModelElement> elements, List<String> names) {}

    private static ParsedModel readModel(Identifier modelId) throws Exception {
        String cp = "/assets/" + modelId.getNamespace() + "/models/" + modelId.getPath() + ".json";
        var stream = ModBlocks.class.getResourceAsStream(cp);
        if (stream == null) {
            throw new IllegalStateException("Missing model resource: " + modelId + " (" + cp + ")");
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            List<CuboidModelElement> elements = new ArrayList<>();
            List<String> names = new ArrayList<>();
            if (root.has("elements")) {
                JsonArray arr = root.getAsJsonArray("elements");
                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    names.add(obj.has("name") ? obj.get("name").getAsString() : null);
                    elements.add(GSON.fromJson(obj, CuboidModelElement.class));
                }
            }
            return new ParsedModel(elements, names);
        }
    }

    // -------- accessors --------

    public boolean isBuilt() {
        return built;
    }

    public List<BakedQuad> quadsNamed(String name) {
        return defaultByName.getOrDefault(name, List.of());
    }

    public List<BakedQuad> lineCenterQuads() {
        return lineCenterQuadsIdentity;
    }

    public List<BakedQuad> lineAllQuads() {
        return lineAllQuadsIdentity;
    }

    public List<BakedQuad> lineNodeCapsQuads() {
        return lineNodeCapsQuadsIdentity;
    }

    public @Nullable TextureAtlasSprite mainSprite() {
        return mainSprite;
    }

    // -------- transforms / naming --------

    public List<BakedQuad> transformQuads(List<BakedQuad> source, Transformation transform) {
        if (transform == null || transform.isIdentity()) {
            return List.copyOf(source);
        }
        List<BakedQuad> out = new ArrayList<>(source.size());
        for (BakedQuad q : source) {
            BakedQuad processed = QuadTransforms.applyTransformation(q, transform);
            // NeoForge's applyTransformation leaves direction unchanged (TODO in QuadTransforms).
            // Shade uses direction: LINE_Y stacks keep Z-line face labels, so south stays UP-bright
            // while other sides look wrongly dark vs PARTIAL end pipes.
            Direction rotated = rotateCullDirection(transform, q.direction());
            if (rotated != processed.direction()) {
                MutableQuad m = new MutableQuad().setFrom(processed);
                m.setDirection(rotated);
                out.add(m.toBakedQuad());
            } else {
                out.add(processed);
            }
        }
        return out;
    }

    /** Rotates a cull/shade face to match {@link QuadTransforms#applyTransformation}. */
    private static Direction rotateCullDirection(Transformation transform, Direction direction) {
        Vector3f normal = new Vector3f(direction.getStepX(), direction.getStepY(), direction.getStepZ());
        transform.transformNormal(normal);
        return Direction.getApproximateNearest(normal.x, normal.y, normal.z);
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

    // -------- world composition --------

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
                    if ((pipeMask & bit) != 0 && includeBase) {
                        addPipeQuadsWithOptionalVShift(out, quadsNamed(connectionPiece(d)), opaqueDuctTextureVShift);
                    }
                    if ((storageMask & bit) != 0) {
                        if (includeBase) {
                            addPipeQuadsWithOptionalVShift(out, quadsNamed(connectionPiece(d)), opaqueDuctTextureVShift);
                        }
                        appendNodeIcon(out, d, packedNodeIcons, nodesSprite, includeBase, includeOverlays);
                    }
                }
            }
            case LINE_X, LINE_Y, LINE_Z -> {
                Transformation tr = rotationForLineAxis(shape.lineAxis());
                if (includeBase) {
                    addPipeQuadsWithOptionalVShift(out, transformQuads(lineCenterQuads(), tr), opaqueDuctTextureVShift);
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
                    if ((pipeMask & bit) != 0 && includeBase) {
                        addPipeQuadsWithOptionalVShift(out, quadsNamed(connectionPiece(d)), opaqueDuctTextureVShift);
                    }
                    if ((storageMask & bit) != 0 && includeBase) {
                        addPipeQuadsWithOptionalVShift(out, quadsNamed(connectionPiece(d)), opaqueDuctTextureVShift);
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
            Direction qDir = q.direction();
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

    // -------- quad UV/vertex manipulation (new BakedQuad via MutableQuad) --------

    static BakedQuad shiftQuadV(BakedQuad q, float deltaV) {
        MutableQuad m = new MutableQuad().setFrom(q);
        for (int i = 0; i < 4; i++) {
            m.setUv(i, m.u(i), m.v(i) + deltaV);
        }
        return m.toBakedQuad();
    }

    /**
     * Builds an overlay quad (same geometry) with UVs mapped onto nodes.png (64x32, 4x4 cells, each 16x8).
     */
    static @Nullable BakedQuad buildNodeIconOverlay(BakedQuad q, int iconIdx, TextureAtlasSprite nodesSprite) {
        if (nodesSprite == null || iconIdx < 0 || iconIdx > 15) {
            return null;
        }
        MutableQuad m = new MutableQuad().setFrom(q);
        float uMin = Float.POSITIVE_INFINITY;
        float uMax = Float.NEGATIVE_INFINITY;
        float vMin = Float.POSITIVE_INFINITY;
        float vMax = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            uMin = Math.min(uMin, m.u(i));
            uMax = Math.max(uMax, m.u(i));
            vMin = Math.min(vMin, m.v(i));
            vMax = Math.max(vMax, m.v(i));
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
        Direction dir = q.direction();
        float eps = 0.0005f;
        m.setSprite(new Material.Baked(nodesSprite, true));
        for (int i = 0; i < 4; i++) {
            float ox = m.x(i) + dir.getStepX() * eps;
            float oy = m.y(i) + dir.getStepY() * eps;
            float oz = m.z(i) + dir.getStepZ() * eps;
            m.setPosition(i, ox, oy, oz);

            float tu = (m.u(i) - uMin) / w;
            float tv = (m.v(i) - vMin) / h;
            float nu = u0 + du * ((cellX + tu * 16.0f) / 64.0f);
            float nv = vv0 + dv * ((cellY + tv * 8.0f) / 32.0f);
            m.setUv(i, nu, nv);
            // Opaque-ish overlay; full alpha reads closest to the bright 1.21 emissive node icons.
            m.setColor(i, 0xFFFFFFFF);
        }
        m.setTintIndex(-1);
        m.setShade(false);
        m.setLightEmission(15);
        return m.toBakedQuad();
    }

    /**
     * Builds an overlay quad using the full UV range of {@code sprite}, preserving the original quad's UV orientation.
     */
    static @Nullable BakedQuad buildFullSpriteOverlay(BakedQuad q, TextureAtlasSprite sprite) {
        return buildFullSpriteOverlay(q, sprite, 0xFFFFFFFF);
    }

    /**
     * @param argb vertex color (ARGB). Soft stall overlay uses a fixed lower alpha; full uses opaque white.
     */
    static @Nullable BakedQuad buildFullSpriteOverlay(BakedQuad q, TextureAtlasSprite sprite, int argb) {
        if (sprite == null) {
            return null;
        }
        MutableQuad m = new MutableQuad().setFrom(q);
        float uMin = Float.POSITIVE_INFINITY;
        float uMax = Float.NEGATIVE_INFINITY;
        float vMin = Float.POSITIVE_INFINITY;
        float vMax = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            uMin = Math.min(uMin, m.u(i));
            uMax = Math.max(uMax, m.u(i));
            vMin = Math.min(vMin, m.v(i));
            vMax = Math.max(vMax, m.v(i));
        }
        float w = uMax - uMin;
        float h = vMax - vMin;
        if (w <= 1e-6f || h <= 1e-6f) {
            return null;
        }
        float su0 = sprite.getU0();
        float su1 = sprite.getU1();
        float sv0 = sprite.getV0();
        float sv1 = sprite.getV1();
        m.setSprite(new Material.Baked(sprite, true));
        for (int i = 0; i < 4; i++) {
            float nu = (m.u(i) - uMin) / w;
            float nv01 = (m.v(i) - vMin) / h;
            m.setUv(i, su0 + (su1 - su0) * nu, sv0 + (sv1 - sv0) * nv01);
            m.setColor(i, argb);
        }
        m.setTintIndex(-1);
        m.setShade(false);
        return m.toBakedQuad();
    }

    /**
     * Minimal {@link ModelBaker} exposing only a pass-through {@link ModelBaker.Interner}, used by
     * {@link FaceBakery#bakeQuad} to build quads outside the normal baking pipeline.
     */
    private static final class DuctModelBaker implements ModelBaker {
        static final DuctModelBaker INSTANCE = new DuctModelBaker();

        private static final Interner INTERNER = new Interner() {
            @Override
            public org.joml.Vector3fc vector(org.joml.Vector3fc vector) {
                return vector;
            }

            @Override
            public BakedQuad.MaterialInfo materialInfo(BakedQuad.MaterialInfo material) {
                return material;
            }
        };

        @Override
        public ResolvedModel getModel(Identifier location) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BlockStateModelPart missingBlockModelPart() {
            throw new UnsupportedOperationException();
        }

        @Override
        public MaterialBaker materials() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Interner interner() {
            return INTERNER;
        }

        @Override
        public <T> T compute(SharedOperationKey<T> key) {
            return key.compute(this);
        }
    }
}
