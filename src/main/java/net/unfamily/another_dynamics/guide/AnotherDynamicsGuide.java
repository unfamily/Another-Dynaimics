package net.unfamily.another_dynamics.guide;

import guideme.Guide;
import guideme.GuideItemSettings;
import guideme.compiler.TagCompiler;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.ModList;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/**
 * Registers the Another Dynamics GuideME guidebook on the client.
 */
public final class AnotherDynamicsGuide {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    public static final Identifier GUIDE_ID = Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "guide");

    private AnotherDynamicsGuide() {}

    public static void registerClient() {
        if (!ModList.get().isLoaded("guideme")) {
            return;
        }
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            var guideItemSettings = new GuideItemSettings(
                    Optional.of(Component.translatable("item.another_dynamics.guide")),
                    List.of(Component.translatable("tooltip.another_dynamics.guide.line0")
                            .withStyle(ChatFormatting.DARK_GRAY)),
                    Optional.empty());
            Guide.builder(GUIDE_ID)
                    .itemSettings(guideItemSettings)
                    .extension(TagCompiler.EXTENSION_POINT, new DuctGridTagCompiler())
                    .build();
            AnotherDynamicsMod.LOGGER.info("GuideME guide registered");
        } catch (Exception e) {
            REGISTERED.set(false);
            AnotherDynamicsMod.LOGGER.error("Failed to register GuideME guide", e);
        }
    }
}
