package net.unfamily.another_dynamics.client;

import java.util.Optional;

import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.geometry.UnbakedGeometry;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.neoforged.neoforge.client.model.AbstractUnbakedModel;
import net.neoforged.neoforge.client.model.ExtendedUnbakedGeometry;
import net.neoforged.neoforge.client.model.StandardModelParameters;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctIds;

/**
 * Unbaked block model for composite ducts. Static geometry is a minimal fallback; world rendering is handled by
 * {@link DuctBlockStateModel} installed at {@link net.neoforged.neoforge.client.event.ModelEvent.ModifyBakingResult}.
 */
public final class DuctUnbakedModel extends AbstractUnbakedModel {
    private final DuctModelGeometry geometry;

    public DuctUnbakedModel(
            StandardModelParameters parameters,
            String ductLogicalId,
            Optional<Identifier> blockModelDefault,
            Optional<Identifier> blockModelLine) {
        super(parameters);
        this.geometry = new DuctModelGeometry(ductLogicalId, blockModelDefault, blockModelLine);
    }

    public String ductLogicalId() {
        return geometry.ductLogicalId();
    }

    public Optional<Identifier> blockModelDefault() {
        return geometry.blockModelDefault();
    }

    public Optional<Identifier> blockModelLine() {
        return geometry.blockModelLine();
    }

    @Override
    public UnbakedGeometry geometry() {
        return geometry;
    }

    @Override
    public void resolveDependencies(Resolver resolver) {
        resolver.markDependency(DuctModelGeometry.SIMPLE_DUCT_LINE);
        resolver.markDependency(DuctModelGeometry.CENTER_ONLY);
    }

    static final class DuctModelGeometry implements ExtendedUnbakedGeometry {
        static final Identifier CENTER_ONLY =
                Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "block/simple_duct_center_only");
        static final Identifier SIMPLE_DUCT_LINE =
                Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "block/simple_duct_line");

        private final String ductLogicalId;
        private final Optional<Identifier> blockModelDefault;
        private final Optional<Identifier> blockModelLine;

        DuctModelGeometry(
                String ductLogicalId, Optional<Identifier> blockModelDefault, Optional<Identifier> blockModelLine) {
            this.ductLogicalId = ductLogicalId;
            this.blockModelDefault = blockModelDefault;
            this.blockModelLine = blockModelLine;
        }

        String ductLogicalId() {
            return ductLogicalId;
        }

        Optional<Identifier> blockModelDefault() {
            return blockModelDefault;
        }

        Optional<Identifier> blockModelLine() {
            return blockModelLine;
        }

        @Override
        public QuadCollection bake(
                TextureSlots textureSlots,
                net.minecraft.client.resources.model.ModelBaker baker,
                net.minecraft.client.renderer.block.dispatch.ModelState state,
                net.minecraft.client.resources.model.ModelDebugName debugName,
                ContextMap additionalProperties) {
            // Dynamic duct shape is rendered by DuctBlockStateModel; keep bake output empty.
            return QuadCollection.EMPTY;
        }
    }
}
