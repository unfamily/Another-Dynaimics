package net.unfamily.another_dynamics.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/** Server -> client: full copier stack after Copy so components and item model refresh in the menu slot. */
public record SettingsCopierStackSyncPayload(ItemStack stack) implements CustomPacketPayload {
    public static final Type<SettingsCopierStackSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "settings_copier_stack_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SettingsCopierStackSyncPayload> STREAM_CODEC =
            ItemStack.STREAM_CODEC.map(SettingsCopierStackSyncPayload::new, SettingsCopierStackSyncPayload::stack);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
