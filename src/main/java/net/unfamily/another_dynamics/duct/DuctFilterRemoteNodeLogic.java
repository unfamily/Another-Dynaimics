package net.unfamily.another_dynamics.duct;

import java.util.List;
import java.util.Objects;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import org.jetbrains.annotations.Nullable;

/** Per-line remote node binding helpers (parallel to filter text rows). */
public final class DuctFilterRemoteNodeLogic {
    private DuctFilterRemoteNodeLogic() {}

    /** Row applies when unbound or the physical attachment matches the bound endpoint. */
    public static boolean lineApplicable(
            int index,
            @Nullable List<DuctDirectionalEndpoint> remoteNodes,
            @Nullable DuctDirectionalEndpoint counterparty) {
        return lineApplicable(index, remoteNodes, null, counterparty);
    }

    /** When {@code anyFaceFlags} is set for the row, only block position must match. */
    public static boolean lineApplicable(
            int index,
            @Nullable List<DuctDirectionalEndpoint> remoteNodes,
            @Nullable List<Boolean> anyFaceFlags,
            @Nullable DuctDirectionalEndpoint counterparty) {
        DuctDirectionalEndpoint bound = endpointAt(remoteNodes, index);
        if (bound == null) {
            return true;
        }
        if (counterparty == null) {
            return false;
        }
        if (anyFaceAt(anyFaceFlags, index)) {
            return bound.pos().equals(counterparty.pos());
        }
        return bound.matches(counterparty.pos(), counterparty.face());
    }

    public static boolean anyFaceAt(@Nullable List<Boolean> flags, int index) {
        return ignoreChannelAt(flags, index);
    }

    @Nullable
    public static DuctDirectionalEndpoint endpointAt(
            @Nullable List<DuctDirectionalEndpoint> remoteNodes, int index) {
        if (remoteNodes == null || index < 0 || index >= remoteNodes.size()) {
            return null;
        }
        return remoteNodes.get(index);
    }

    public static void syncToLineSize(
            @Nullable List<DuctDirectionalEndpoint> remoteNodes, int lineSize) {
        if (remoteNodes == null) {
            return;
        }
        while (remoteNodes.size() < lineSize) {
            remoteNodes.add(null);
        }
        while (remoteNodes.size() > lineSize) {
            remoteNodes.remove(remoteNodes.size() - 1);
        }
    }

    public static void syncIgnoreChannelToLineSize(@Nullable List<Boolean> flags, int lineSize) {
        if (flags == null) {
            return;
        }
        while (flags.size() < lineSize) {
            flags.add(Boolean.FALSE);
        }
        while (flags.size() > lineSize) {
            flags.remove(flags.size() - 1);
        }
    }

    public static boolean ignoreChannelAt(@Nullable List<Boolean> flags, int index) {
        if (flags == null || index < 0 || index >= flags.size()) {
            return false;
        }
        Boolean v = flags.get(index);
        return v != null && v;
    }

    public static void putIgnoreChannelArray(CompoundTag tag, String key, List<Boolean> flags) {
        if (flags == null || flags.isEmpty()) {
            return;
        }
        boolean any = false;
        byte[] arr = new byte[flags.size()];
        for (int i = 0; i < flags.size(); i++) {
            boolean v = flags.get(i) != null && flags.get(i);
            arr[i] = v ? (byte) 1 : (byte) 0;
            if (v) {
                any = true;
            }
        }
        if (any) {
            tag.putByteArray(key, arr);
        }
    }

    public static void readIgnoreChannelInto(
            CompoundTag tag, String key, List<Boolean> out, int expectedSize) {
        out.clear();
        if (tag.contains(key, Tag.TAG_BYTE_ARRAY)) {
            for (byte b : tag.getByteArray(key)) {
                out.add(b != 0);
            }
        }
        syncIgnoreChannelToLineSize(out, expectedSize);
    }

    public static boolean hasAnyApplicableNonEmpty(
            List<String> lines,
            @Nullable List<DuctDirectionalEndpoint> remoteNodes,
            @Nullable DuctDirectionalEndpoint counterparty) {
        if (lines == null) {
            return false;
        }
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            if (s != null
                    && !s.trim().isEmpty()
                    && lineApplicable(i, remoteNodes, counterparty)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Shared allow/deny precedence with per-line remote node gating (item/fluid/gas filter family).
     */
    public static boolean evaluatePrecedence(
            boolean denyOverridesAllow,
            List<String> allowFilters,
            List<String> denyFilters,
            @Nullable List<DuctDirectionalEndpoint> allowRemote,
            @Nullable List<DuctDirectionalEndpoint> denyRemote,
            @Nullable DuctDirectionalEndpoint counterparty,
            java.util.function.BiPredicate<Integer, String> lineMatches) {
        boolean hasA = hasAnyApplicableNonEmpty(allowFilters, allowRemote, counterparty);
        boolean hasD = hasAnyApplicableNonEmpty(denyFilters, denyRemote, counterparty);
        if (!hasA && !hasD) {
            return true;
        }
        boolean A =
                hasA
                        && matchesApplicableAny(
                                allowFilters, allowRemote, counterparty, lineMatches);
        boolean D =
                hasD
                        && matchesApplicableAny(
                                denyFilters, denyRemote, counterparty, lineMatches);
        if (denyOverridesAllow) {
            if (D) {
                return false;
            }
            if (hasA && !A) {
                return false;
            }
            return true;
        }
        if (hasA && A) {
            return true;
        }
        if (D) {
            return false;
        }
        if (hasA && !A) {
            return false;
        }
        return true;
    }

    private static boolean matchesApplicableAny(
            List<String> entries,
            @Nullable List<DuctDirectionalEndpoint> remoteNodes,
            @Nullable DuctDirectionalEndpoint counterparty,
            java.util.function.BiPredicate<Integer, String> lineMatches) {
        if (entries == null) {
            return false;
        }
        for (int i = 0; i < entries.size(); i++) {
            String line = entries.get(i);
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            if (!lineApplicable(i, remoteNodes, counterparty)) {
                continue;
            }
            if (lineMatches.test(i, line.trim())) {
                return true;
            }
        }
        return false;
    }

    public static void putRemoteNodeList(CompoundTag tag, String key, List<DuctDirectionalEndpoint> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        boolean any = false;
        ListTag list = new ListTag();
        for (DuctDirectionalEndpoint ep : nodes) {
            if (ep != null) {
                list.add(ep.toTag());
                any = true;
            } else {
                list.add(new CompoundTag());
            }
        }
        if (any) {
            tag.put(key, list);
        }
    }

    public static void readRemoteNodeListInto(
            CompoundTag tag, String key, List<DuctDirectionalEndpoint> out, int expectedSize) {
        out.clear();
        if (tag.contains(key, Tag.TAG_LIST)) {
            ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                out.add(DuctDirectionalEndpoint.fromTag(list.getCompound(i)));
            }
        }
        syncToLineSize(out, expectedSize);
    }

    public static void readRemoteNodeListInto(
            CompoundTag tag,
            String key,
            List<DuctDirectionalEndpoint> out,
            int expectedSize,
            @Nullable net.minecraft.world.level.Level level) {
        readRemoteNodeListInto(tag, key, out, expectedSize);
        if (level != null) {
            migrateLegacyEndpointsInPlace(level, out);
        }
    }

    /** Converts duct-position legacy bindings to physical attachment endpoints in place. */
    public static boolean migrateLegacyEndpointsInPlace(
            net.minecraft.world.level.Level level, List<DuctDirectionalEndpoint> list) {
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            DuctDirectionalEndpoint migrated =
                    DuctDirectionalEndpoint.migrateLegacyStoredEndpoint(level, list.get(i));
            if (!Objects.equals(migrated, list.get(i))) {
                list.set(i, migrated);
                changed = true;
            }
        }
        return changed;
    }
}
