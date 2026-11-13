package net.unfamily.another_dynamics.integration.iskalib;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.iskalib.flux.IUnknownFluxStorage;

import org.jetbrains.annotations.Nullable;

/** NeoForge block capability for {@link IUnknownFluxStorage} on AD blocks. */
public final class UnknownFluxCapabilities {
    public static final BlockCapability<IUnknownFluxStorage, @Nullable Direction> BLOCK =
            BlockCapability.createSided(
                    Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "unknown_flux"), IUnknownFluxStorage.class);

    private UnknownFluxCapabilities() {}
}
