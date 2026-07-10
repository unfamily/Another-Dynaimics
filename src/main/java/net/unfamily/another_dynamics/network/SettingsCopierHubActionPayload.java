package net.unfamily.another_dynamics.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/** Client -> server: hub GUI actions (configure, mode toggle, rename). */
public record SettingsCopierHubActionPayload(int action, String renameText) implements CustomPacketPayload {
    public static final int ACTION_CONFIGURE = 0;
    public static final int ACTION_MODE_TOGGLE = 1;
    public static final int ACTION_RENAME = 2;
    public static final int ACTION_ENTER_IMPORT = 3;
    public static final int ACTION_BACK_FROM_IMPORT = 4;

    public static final Type<SettingsCopierHubActionPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "settings_copier_hub_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SettingsCopierHubActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    SettingsCopierHubActionPayload::action,
                    ByteBufCodecs.STRING_UTF8,
                    SettingsCopierHubActionPayload::renameText,
                    SettingsCopierHubActionPayload::new);

    public SettingsCopierHubActionPayload(int action) {
        this(action, "");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
