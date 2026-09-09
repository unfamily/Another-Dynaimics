package net.unfamily.another_dynamics.machine.connector;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;
import net.unfamily.another_dynamics.integration.mekanism.MekanismHeatCompat;

import org.jetbrains.annotations.Nullable;

/**
 * Soft-dep capability forwarders for Machine Connector (26.1.2 matrix).
 *
 * <p>No PNC / MI / Replication on this branch. NeoVitae + FTBIC when loaded; Ars/Mek if present.
 */
public final class MachineConnectorSoftCaps {
    private MachineConnectorSoftCaps() {}

    public static void register(
            RegisterCapabilitiesEvent event, BlockEntityType<MachineConnectorBlockEntity> type) {
        registerIfPresent(
                event,
                type,
                "ars_nouveau",
                "com.hollingsworth.arsnouveau.setup.registry.CapabilityRegistry",
                "SOURCE_CAPABILITY");
        registerIfPresent(
                event,
                type,
                "neovitae",
                "com.breakinblocks.neovitae.api.capability.NVCapabilities",
                "ARA_VITAE");
        registerIfPresent(
                event,
                type,
                "neovitae",
                "com.breakinblocks.neovitae.api.capability.NVCapabilities",
                "SPIRITUS_STORAGE");
        registerIfPresent(
                event,
                type,
                "ftbic",
                "dev.ftb.mods.ftbic.util.FTBICCapabilities",
                "ZAP_ENERGY_BLOCK");
        registerMekanismChemical(event, type);
        registerMekanismHeat(event, type);
    }

    public static boolean hasAnySoftHandler(Level level, BlockPos pos, Direction side) {
        if (MachineConnectorCapabilities.SoftProbe.get(
                        level,
                        pos,
                        side,
                        "ars_nouveau",
                        "com.hollingsworth.arsnouveau.setup.registry.CapabilityRegistry",
                        "SOURCE_CAPABILITY")
                != null) {
            return true;
        }
        if (MachineConnectorCapabilities.SoftProbe.get(
                        level,
                        pos,
                        side,
                        "neovitae",
                        "com.breakinblocks.neovitae.api.capability.NVCapabilities",
                        "ARA_VITAE")
                != null) {
            return true;
        }
        if (MachineConnectorCapabilities.SoftProbe.get(
                        level,
                        pos,
                        side,
                        "neovitae",
                        "com.breakinblocks.neovitae.api.capability.NVCapabilities",
                        "SPIRITUS_STORAGE")
                != null) {
            return true;
        }
        return MachineConnectorCapabilities.SoftProbe.get(
                        level,
                        pos,
                        side,
                        "ftbic",
                        "dev.ftb.mods.ftbic.util.FTBICCapabilities",
                        "ZAP_ENERGY_BLOCK")
                != null;
    }

    private static void registerIfPresent(
            RegisterCapabilitiesEvent event,
            BlockEntityType<MachineConnectorBlockEntity> type,
            String modId,
            String className,
            String field) {
        if (!ModList.get().isLoaded(modId)) {
            return;
        }
        try {
            Object token = Class.forName(className).getField(field).get(null);
            if (token instanceof BlockCapability<?, ?> cap) {
                @SuppressWarnings("unchecked")
                BlockCapability<?, @Nullable Direction> sided =
                        (BlockCapability<?, @Nullable Direction>) cap;
                MachineConnectorCapabilities.registerSidedRaw(event, type, sided);
            }
        } catch (Throwable ignored) {
            // Soft-dep absent or API mismatch — skip.
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerMekanismChemical(
            RegisterCapabilitiesEvent event, BlockEntityType<MachineConnectorBlockEntity> type) {
        if (!MekanismChemicalCompat.isLoaded()) {
            return;
        }
        try {
            Class<?> caps = Class.forName("mekanism.common.capabilities.Capabilities");
            Object chemical = caps.getField("CHEMICAL").get(null);
            Object blockCap = chemical.getClass().getMethod("block").invoke(chemical);
            if (blockCap instanceof BlockCapability<?, ?> cap) {
                MachineConnectorCapabilities.registerSidedRaw(
                        event, type, (BlockCapability<?, @Nullable Direction>) cap);
            }
        } catch (Throwable ignored) {
            // skip
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerMekanismHeat(
            RegisterCapabilitiesEvent event, BlockEntityType<MachineConnectorBlockEntity> type) {
        if (!MekanismHeatCompat.isHeatCapabilityAvailable()) {
            return;
        }
        try {
            Class<?> caps = Class.forName("mekanism.common.capabilities.Capabilities");
            Object heat = caps.getField("HEAT").get(null);
            if (heat instanceof BlockCapability<?, ?> cap) {
                MachineConnectorCapabilities.registerSidedRaw(
                        event, type, (BlockCapability<?, @Nullable Direction>) cap);
            }
        } catch (Throwable ignored) {
            // skip
        }
    }
}
