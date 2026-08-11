package net.unfamily.another_dynamics.integration.cablefacades;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.Config;

/**
 * Optional Cable Facades integration: runtime API whitelist + one-shot seed of their common config.
 * Soft-loaded via reflection so Cable Facades is never a hard dependency.
 */
public final class CableFacadesCompat {
    public static final String MOD_ID = "cable_facades";
    public static final String ALLOW_PATTERN = "another_dynamics:*";

    private CableFacadesCompat() {}

    /**
     * Cable Facades drops covers when the supporting block is replaced. Project→duct conversion must
     * snapshot and re-apply facade data, and temporarily disable consume-drops so items are not spilled.
     */
    public static void runPreservingFacade(ServerLevel level, BlockPos pos, Runnable action) {
        if (!ModList.get().isLoaded(MOD_ID)) {
            action.run();
            return;
        }
        Object facadeData = null;
        Class<?> utils = null;
        Field consumeField = null;
        boolean previousConsume = false;
        try {
            utils = Class.forName("com.portingdeadmods.cable_facades.utils.FacadeUtils");
            Method getFacadeData = utils.getMethod("getFacadeData", BlockGetter.class, BlockPos.class);
            facadeData = getFacadeData.invoke(null, level, pos);
            if (facadeData != null) {
                consumeField = Class.forName("com.portingdeadmods.cable_facades.CFConfig").getField("consumeFacade");
                previousConsume = consumeField.getBoolean(null);
                consumeField.setBoolean(null, false);
            }
        } catch (ReflectiveOperationException e) {
            AnotherDynamicsMod.LOGGER.debug("Cable Facades facade preserve unavailable", e);
            facadeData = null;
            consumeField = null;
        }
        try {
            action.run();
            if (facadeData != null && utils != null) {
                try {
                    restoreFacadeData(utils, level, pos, facadeData);
                } catch (ReflectiveOperationException e) {
                    AnotherDynamicsMod.LOGGER.debug("Cable Facades facade restore failed", e);
                }
            }
        } finally {
            if (consumeField != null) {
                try {
                    consumeField.setBoolean(null, previousConsume);
                } catch (IllegalAccessException ignored) {
                    // best-effort restore of CF config field
                }
            }
        }
    }

    private static void restoreFacadeData(Class<?> utils, ServerLevel level, BlockPos pos, Object data)
            throws ReflectiveOperationException {
        Method isFullBlock = data.getClass().getMethod("isFullBlock");
        Method facadeType = data.getClass().getMethod("facadeType");
        Object type = facadeType.invoke(data);
        if (Boolean.TRUE.equals(isFullBlock.invoke(data))) {
            Method getFullBlock = data.getClass().getMethod("getFullBlock");
            BlockState full = (BlockState) getFullBlock.invoke(data);
            if (full == null) {
                return;
            }
            Method addFacade =
                    utils.getMethod("addFacade", Level.class, BlockPos.class, BlockState.class, type.getClass());
            addFacade.invoke(null, level, pos, full, type);
            return;
        }
        Method isDirectional = data.getClass().getMethod("isDirectional");
        if (!Boolean.TRUE.equals(isDirectional.invoke(data))) {
            return;
        }
        Method directional = data.getClass().getMethod("directional");
        Object facesObj = directional.invoke(data);
        if (!(facesObj instanceof Map<?, ?> faces) || faces.isEmpty()) {
            return;
        }
        Method addDirectional = utils.getMethod(
                "addDirectionalFacade",
                Level.class,
                BlockPos.class,
                Direction.class,
                BlockState.class,
                type.getClass());
        for (Map.Entry<?, ?> entry : faces.entrySet()) {
            if (entry.getKey() instanceof Direction dir && entry.getValue() instanceof BlockState state) {
                addDirectional.invoke(null, level, pos, dir, state, type);
            }
        }
    }

    public static void register(FMLCommonSetupEvent event) {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return;
        }
        event.enqueueWork(CableFacadesCompat::bootstrap);
    }

    private static void bootstrap() {
        registerViaApi();
        if (Config.CABLE_FACADES_CONFIG_SEED.get()) {
            boolean seeded = seedCableFacadesConfig();
            Config.CABLE_FACADES_CONFIG_SEED.set(false);
            Config.saveCommon();
            if (seeded) {
                AnotherDynamicsMod.LOGGER.info(
                        "Seeded Cable Facades blocks whitelist with {} (one-shot flag cleared)", ALLOW_PATTERN);
            } else {
                AnotherDynamicsMod.LOGGER.debug(
                        "Cable Facades config seed skipped or already present; one-shot flag cleared");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerViaApi() {
        try {
            Class<?> apiClass = Class.forName("com.portingdeadmods.cable_facades.api.CableFacadesAPI");
            Method enqueue = apiClass.getMethod("enqueueAPICallback", Consumer.class);
            enqueue.invoke(
                    null,
                    (Consumer<Object>) api -> {
                        try {
                            Method registerAllowed =
                                    api.getClass().getMethod("registerAllowedBlocks", String[].class);
                            registerAllowed.invoke(api, (Object) new String[] {ALLOW_PATTERN});
                            Method registerHidden =
                                    api.getClass().getMethod("registerHiddenBlocks", String[].class);
                            registerHidden.invoke(api, (Object) new String[] {ALLOW_PATTERN});
                        } catch (ReflectiveOperationException e) {
                            AnotherDynamicsMod.LOGGER.error(
                                    "Cable Facades API registerAllowedBlocks/Hidden failed", e);
                        }
                    });
        } catch (ReflectiveOperationException e) {
            AnotherDynamicsMod.LOGGER.error("Cable Facades API not available", e);
        }
    }

    /**
     * Appends {@link #ALLOW_PATTERN} to Cable Facades {@code blocks} list when missing and saves their common
     * config.
     *
     * @return true if the list was mutated
     */
    private static boolean seedCableFacadesConfig() {
        try {
            Class<?> cfConfig = Class.forName("com.portingdeadmods.cable_facades.CFConfig");
            Field blockStringsField = cfConfig.getDeclaredField("BLOCK_STRINGS");
            blockStringsField.setAccessible(true);
            Object configValue = blockStringsField.get(null);
            if (configValue == null) {
                return false;
            }
            Method get = configValue.getClass().getMethod("get");
            Method set = configValue.getClass().getMethod("set", Object.class);
            Object raw = get.invoke(configValue);
            if (!(raw instanceof List<?> current)) {
                return false;
            }
            List<String> next = new ArrayList<>(current.size() + 1);
            for (Object entry : current) {
                next.add(String.valueOf(entry));
            }
            if (next.contains(ALLOW_PATTERN)) {
                return false;
            }
            next.add(ALLOW_PATTERN);
            set.invoke(configValue, next);
            saveForeignSpec(cfConfig);
            return true;
        } catch (ReflectiveOperationException e) {
            AnotherDynamicsMod.LOGGER.warn("Could not seed Cable Facades common config blocks list", e);
            return false;
        }
    }

    private static void saveForeignSpec(Class<?> cfConfig) throws ReflectiveOperationException {
        Field specField = cfConfig.getDeclaredField("SPEC");
        specField.setAccessible(true);
        Object spec = specField.get(null);
        if (spec instanceof ModConfigSpec modConfigSpec) {
            modConfigSpec.save();
        }
    }
}
