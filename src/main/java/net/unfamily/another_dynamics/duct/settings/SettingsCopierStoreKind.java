package net.unfamily.another_dynamics.duct.settings;

/** Stored payload kind on {@link net.unfamily.another_dynamics.item.SettingsCopierItem}. */
public enum SettingsCopierStoreKind {
    ALL,
    FILTER;

    public static final String TAG = "Kind";

    public byte toTag() {
        return (byte) ordinal();
    }

    public static SettingsCopierStoreKind fromTag(byte b) {
        int o = b & 0xFF;
        if (o == FILTER.ordinal()) {
            return FILTER;
        }
        return ALL;
    }

    public static SettingsCopierStoreKind fromCompound(net.minecraft.nbt.CompoundTag tag) {
        if (tag != null && tag.contains(TAG, net.minecraft.nbt.Tag.TAG_BYTE)) {
            return fromTag(tag.getByte(TAG));
        }
        return ALL;
    }
}
