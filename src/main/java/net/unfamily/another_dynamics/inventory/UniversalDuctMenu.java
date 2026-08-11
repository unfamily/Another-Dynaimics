package net.unfamily.another_dynamics.inventory;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.ContainerData;
import net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctMenuSync;
import net.unfamily.another_dynamics.duct.DuctTransportKind;

import org.jetbrains.annotations.Nullable;

/**
 * Shared duct-node GUI surface for world ducts and settings-copier virtual editor.
 */
public interface UniversalDuctMenu {
    ContainerData getSyncData();

    String getClientDuctLogicalId();

    boolean isDuctAlwaysOpaqueLocked();

    /** True when the opened duct's physical component is server network-opaque. */
    default boolean isClientComponentNetworkOpaque() {
        return false;
    }

    BlockPos getDuctBlockPos();

    Direction getAccessFace();

    int moduleSlotCount();

    int copySettingsSlotIndex();

    int filterAllowCap(boolean hybridFilterContext);

    int filterDenyCap(boolean hybridFilterContext);

    List<String> getClientAllowFilters(DuctFaceNode.FilterBank bank);

    List<String> getClientDenyFilters(DuctFaceNode.FilterBank bank);

    boolean getClientDenyOverridesAllow(DuctFaceNode.FilterBank bank);

    List<Integer> getClientAllowCaps(DuctFaceNode.FilterBank bank);

    List<Integer> getClientFilterKeepCaps();

    List<Integer> getClientExtractorLimitCaps();

    @Nullable
    List<Integer> getClientAllowCaps2(DuctFaceNode.FilterBank bank);

    List<Integer> getClientAllowConcatChannels(DuctFaceNode.FilterBank bank);

    List<Integer> getClientDenyConcatChannels(DuctFaceNode.FilterBank bank);

    List<@Nullable DuctDirectionalEndpoint> getClientAllowRemoteNodes(DuctFaceNode.FilterBank bank);

    List<@Nullable DuctDirectionalEndpoint> getClientDenyRemoteNodes(DuctFaceNode.FilterBank bank);

    List<Boolean> getClientAllowRemoteIgnoreChannel(DuctFaceNode.FilterBank bank);

    List<Boolean> getClientDenyRemoteIgnoreChannel(DuctFaceNode.FilterBank bank);

    List<Boolean> getClientAllowRemoteAnyFace(DuctFaceNode.FilterBank bank);

    List<Boolean> getClientDenyRemoteAnyFace(DuctFaceNode.FilterBank bank);

    void receiveFilterSync(
            BlockPos pos,
            Direction face,
            int transportKindOrdinal,
            int filterBankOrdinal,
            List<String> allow,
            List<String> deny,
            List<Integer> allowCaps,
            List<Integer> allowCaps2,
            List<Integer> allowConcat,
            List<Integer> denyConcat,
            List<@Nullable DuctDirectionalEndpoint> allowRemote,
            List<@Nullable DuctDirectionalEndpoint> denyRemote,
            List<Boolean> allowRemoteIgnoreChannel,
            List<Boolean> denyRemoteIgnoreChannel,
            List<Boolean> allowRemoteAnyFace,
            List<Boolean> denyRemoteAnyFace,
            boolean denyOverridesAllow);

    void ensureClientFilterBufferSizes(boolean hybridFilterContext);

    void pushFilterConfigToServer(
            DuctFaceNode.FilterBank bank,
            List<String> allow,
            List<String> deny,
            List<Integer> allowCaps,
            List<Integer> allowCaps2,
            List<Integer> allowConcat,
            List<Integer> denyConcat,
            List<@Nullable DuctDirectionalEndpoint> allowRemote,
            List<@Nullable DuctDirectionalEndpoint> denyRemote,
            List<Boolean> allowRemoteIgnoreChannel,
            List<Boolean> denyRemoteIgnoreChannel,
            List<Boolean> allowRemoteAnyFace,
            List<Boolean> denyRemoteAnyFace,
            boolean denyOverridesAllow,
            boolean editingAllowList);

    /** Transport lane used for client filter mirrors and filter update packets. */
    default DuctTransportKind filterTransportKind() {
        DuctTransportKind[] kinds = DuctTransportKind.values();
        return kinds[
                Mth.clamp(
                        getSyncData().get(DuctMenuSync.ACTIVE_TRANSPORT_KIND),
                        0,
                        kinds.length - 1)];
    }

    default int filterTransportKindOrdinal() {
        return filterTransportKind().ordinal();
    }

    /** Client tick: sync deny-override flag from {@link DuctMenuSync}. */
    default void updateClientDenyOverridesFromSync() {}

    /** Settings copier virtual editor only. */
    default boolean isSettingsCopierVirtualEditor() {
        return false;
    }

    /** True after all filter banks were received from server for the active transport kind. */
    default boolean clientFiltersHydrated() {
        return false;
    }

    /** True after the player edited filter lines locally in this menu session. */
    default boolean clientFiltersDirty() {
        return false;
    }

    default void markClientFiltersDirty() {}

    /** Push pending filter edits on GUI close when synced and edited. */
    default boolean shouldPushClientFiltersOnClose() {
        return clientFiltersHydrated() && clientFiltersDirty();
    }

    /** True when the given transport kind has synced and edited filters to flush. */
    default boolean shouldPushFiltersForTransport(int transportKindOrdinal) {
        return clientFiltersHydrated(transportKindOrdinal) && clientFiltersDirty(transportKindOrdinal);
    }

    /** True when filter banks for the given transport kind were synced from the server. */
    default boolean clientFiltersHydrated(int transportKindOrdinal) {
        return false;
    }

    /** True when the given transport kind has local filter edits. */
    default boolean clientFiltersDirty(int transportKindOrdinal) {
        return false;
    }

    /** Push all filter banks for a transport kind (multi-lane tab switch / flush). */
    default void pushAllFilterBanksForTransport(int transportKindOrdinal) {}

    /** Sort filter rows in the mirror for a non-active transport kind before flush. */
    default void reorderClientFilterBankForTransport(
            int transportKindOrdinal,
            DuctFaceNode.FilterBank bank,
            net.minecraft.core.RegistryAccess registryAccess) {}
}
