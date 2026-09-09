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
 * Soft-dep capability forwarders for Machine Connector (1.21.1 matrix).
 *
 * <p>Reflection only — no compile-time soft-dep jars required.
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
                "modern_industrialization",
                "aztech.modern_industrialization.api.energy.EnergyApi",
                "SIDED");
        registerNestedIfPresent(
                event,
                type,
                "replication",
                "com.buuz135.replication.ReplicationRegistry$Capabilities",
                "MATTER_HANDLER");
        registerIfPresent(
                event,
                type,
                "pneumaticcraft",
                "me.desht.pneumaticcraft.api.PNCCapabilities",
                "AIR_HANDLER_MACHINE");
        registerIfPresent(
                event,
                type,
                "pneumaticcraft",
                "me.desht.pneumaticcraft.api.PNCCapabilities",
                "HEAT_EXCHANGER_BLOCK");
        registerIfPresent(
                event,
                type,
                "industrialforegoingsouls",
                "com.buuz135.industrialforegoingsouls.capabilities.SoulCapabilities",
                "BLOCK");
        registerIfPresent(
                event,
                type,
                "brandonscore",
                "com.brandon3055.brandonscore.capability.CapabilityOP",
                "BLOCK");
        registerMekanismChemical(event, type);
        registerMekanismHeat(event, type);
    }

    private static final String[][] SOFT_PROBES = {
        {
            "ars_nouveau",
            "com.hollingsworth.arsnouveau.setup.registry.CapabilityRegistry",
            "SOURCE_CAPABILITY"
        },
        {
            "modern_industrialization",
            "aztech.modern_industrialization.api.energy.EnergyApi",
            "SIDED"
        },
        {
            "replication",
            "com.buuz135.replication.ReplicationRegistry$Capabilities",
            "MATTER_HANDLER"
        },
        {
            "pneumaticcraft",
            "me.desht.pneumaticcraft.api.PNCCapabilities",
            "AIR_HANDLER_MACHINE"
        },
        {
            "pneumaticcraft",
            "me.desht.pneumaticcraft.api.PNCCapabilities",
            "HEAT_EXCHANGER_BLOCK"
        },
        {
            "industrialforegoingsouls",
            "com.buuz135.industrialforegoingsouls.capabilities.SoulCapabilities",
            "BLOCK"
        },
        {
            "brandonscore",
            "com.brandon3055.brandonscore.capability.CapabilityOP",
            "BLOCK"
        },
    };

    public static boolean hasAnySoftHandler(Level level, BlockPos pos, Direction side) {
        for (String[] probe : SOFT_PROBES) {
            if (MachineConnectorCapabilities.SoftProbe.get(
                            level, pos, side, probe[0], probe[1], probe[2])
                    != null) {
                return true;
            }
        }
        return false;
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

    private static void registerNestedIfPresent(
            RegisterCapabilitiesEvent event,
            BlockEntityType<MachineConnectorBlockEntity> type,
            String modId,
            String className,
            String field) {
        registerIfPresent(event, type, modId, className, field);
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
