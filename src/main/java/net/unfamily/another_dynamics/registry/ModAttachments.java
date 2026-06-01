package net.unfamily.another_dynamics.registry;

import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctPlayerOpaqueState;

public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, AnotherDynamicsMod.MOD_ID);

    /** Per-player All opaque + remembered preference; synced to client, copied on death. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<DuctPlayerOpaqueState>> DUCT_PLAYER_OPAQUE =
            TYPES.register(
                    "duct_transit_opaque",
                    () -> AttachmentType.builder(() -> DuctPlayerOpaqueState.DEFAULT)
                            .serialize(DuctPlayerOpaqueState.CODEC)
                            .sync(DuctPlayerOpaqueState.STREAM_CODEC)
                            .copyOnDeath()
                            .build());

    /** @deprecated use {@link #DUCT_PLAYER_OPAQUE} */
    @Deprecated
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<DuctPlayerOpaqueState>> DUCT_TRANSIT_OPAQUE =
            DUCT_PLAYER_OPAQUE;

    private ModAttachments() {}
}
