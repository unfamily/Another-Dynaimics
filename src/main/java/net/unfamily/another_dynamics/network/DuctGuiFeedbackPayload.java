package net.unfamily.another_dynamics.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/** Transient duct node GUI status line (settings copier copy/paste). */
public record DuctGuiFeedbackPayload(int messageId) implements CustomPacketPayload {
    public static final int COPIED = 0;
    public static final int PASTED = 1;
    public static final int PASTE_FAILED = 2;

    public static final Type<DuctGuiFeedbackPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "duct_gui_feedback"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DuctGuiFeedbackPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, DuctGuiFeedbackPayload::messageId, DuctGuiFeedbackPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
