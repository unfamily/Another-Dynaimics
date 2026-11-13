package net.unfamily.another_dynamics.integration.iskalib;

import net.minecraft.core.Direction;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctFaceLanes;
import net.unfamily.another_dynamics.duct.DuctUnknownFluxTransportSpec;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects;
import net.unfamily.iskalib.flux.IUnknownFluxStorage;

/**
 * Per-face Unknown Flux buffer exposed on {@link UnknownFluxCapabilities#BLOCK} for external machines.
 */
public final class DuctUnknownFluxFaceBuffer implements IUnknownFluxStorage {
    private final DuctBlockEntity duct;
    private final Direction face;

    public DuctUnknownFluxFaceBuffer(DuctBlockEntity duct, Direction face) {
        this.duct = duct;
        this.face = face;
    }

    private DuctUnknownFluxTransportSpec spec() {
        return duct.unknownFluxTransportSpec();
    }

    private DuctFaceLanes lanes() {
        return duct.getFaceLanes(face);
    }

    @Override
    public long getUnknownFlux() {
        return Math.max(0L, lanes().unknownFluxOutputBuffer);
    }

    @Override
    public long getUnknownFluxCapacity() {
        return DuctModuleEffects.effectiveUnknownFluxOutputBufferCap(duct, face, spec());
    }

    @Override
    public long getMaxUnknownFluxInsert() {
        return Long.MAX_VALUE;
    }

    @Override
    public long getMaxUnknownFluxExtract() {
        return Long.MAX_VALUE;
    }

    @Override
    public long insertUnknownFlux(long amount, boolean simulate) {
        if (amount <= 0 || !canReceiveUnknownFlux()) {
            return 0L;
        }
        long cap = DuctModuleEffects.effectiveUnknownFluxInputBufferCap(duct, face, spec());
        long stored = Math.max(0L, lanes().unknownFluxInputBuffer);
        long accept = Math.min(amount, Math.max(0L, cap - stored));
        if (!simulate && accept > 0L) {
            lanes().unknownFluxInputBuffer = stored + accept;
            duct.setChanged();
            duct.refreshMenuData(face);
            duct.syncStallVisualIfNeeded();
        }
        return accept;
    }

    @Override
    public long extractUnknownFlux(long amount, boolean simulate) {
        if (amount <= 0 || !canExtractUnknownFlux()) {
            return 0L;
        }
        long stored = Math.max(0L, lanes().unknownFluxOutputBuffer);
        long take = Math.min(amount, stored);
        if (!simulate && take > 0L) {
            lanes().unknownFluxOutputBuffer = stored - take;
            duct.setChanged();
            duct.refreshMenuData(face);
            if (lanes().unknownFluxInputBuffer <= 0L && lanes().unknownFluxOutputBuffer <= 0L) {
                duct.syncStallVisualIfNeeded();
            }
        }
        return take;
    }
}
