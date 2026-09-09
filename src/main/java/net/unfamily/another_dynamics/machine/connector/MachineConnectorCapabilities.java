package net.unfamily.another_dynamics.machine.connector;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.unfamily.another_dynamics.registry.ModBlockEntities;

import org.jetbrains.annotations.Nullable;

/**
 * Registers NeoForge + version-locked soft-dep block capabilities on the Machine Connector.
 */
public final class MachineConnectorCapabilities {
    private MachineConnectorCapabilities() {}

    public static void register(RegisterCapabilitiesEvent event) {
        BlockEntityType<MachineConnectorBlockEntity> type = ModBlockEntities.MACHINE_CONNECTOR.get();
        registerSided(event, type, Capabilities.ItemHandler.BLOCK);
        registerSided(event, type, Capabilities.FluidHandler.BLOCK);
        registerSided(event, type, Capabilities.EnergyStorage.BLOCK);

        MachineConnectorSoftCaps.register(event, type);
    }

    static <T> void registerSided(
            RegisterCapabilitiesEvent event,
            BlockEntityType<MachineConnectorBlockEntity> type,
            BlockCapability<T, @Nullable Direction> capability) {
        event.registerBlockEntity(capability, type, (be, side) -> be.forward(capability, side));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void registerSidedRaw(
            RegisterCapabilitiesEvent event,
            BlockEntityType<MachineConnectorBlockEntity> type,
            BlockCapability<?, @Nullable Direction> capability) {
        BlockCapability raw = capability;
        event.registerBlockEntity(raw, type, (be, side) -> be.forward(raw, (Direction) side));
    }

    /**
     * Soft-dep probes used for placement facing (and optional lookups). Version-locked for 1.21.1.
     */
    public static final class SoftProbe {
        private SoftProbe() {}

        @Nullable
        public static Object get(Level level, BlockPos pos, @Nullable Direction side, String modId, String className, String field) {
            if (level == null || !ModList.get().isLoaded(modId)) {
                return null;
            }
            try {
                Object token = Class.forName(className).getField(field).get(null);
                if (!(token instanceof BlockCapability<?, ?> cap)) {
                    return null;
                }
                @SuppressWarnings("unchecked")
                BlockCapability<Object, @Nullable Direction> typed =
                        (BlockCapability<Object, @Nullable Direction>) cap;
                return level.getCapability(typed, pos, side);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
