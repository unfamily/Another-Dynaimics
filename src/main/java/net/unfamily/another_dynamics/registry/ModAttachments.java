package net.unfamily.another_dynamics.registry;

import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, AnotherDynamicsMod.MOD_ID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Boolean>> DUCT_TRANSIT_OPAQUE =
            TYPES.register(
                    "duct_transit_opaque",
                    () -> AttachmentType.builder(() -> false).sync(ByteBufCodecs.BOOL).build());

    private ModAttachments() {}
}

