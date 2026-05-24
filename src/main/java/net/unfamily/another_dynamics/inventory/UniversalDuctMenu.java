package net.unfamily.another_dynamics.inventory;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.ContainerData;
import net.unfamily.another_dynamics.duct.DuctFaceNode;

/**
 * Shared duct-node GUI surface for world ducts and settings-copier virtual editor.
 */
public interface UniversalDuctMenu {
    ContainerData getSyncData();

    String getClientDuctLogicalId();

    boolean isDuctAlwaysOpaqueLocked();

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

    void receiveFilterSync(
            BlockPos pos,
            Direction face,
            int transportKindOrdinal,
            int filterBankOrdinal,
            List<String> allow,
            List<String> deny,
            List<Integer> allowCaps,
            List<Integer> allowCaps2,
            boolean denyOverridesAllow);

    void ensureClientFilterBufferSizes(boolean hybridFilterContext);

    void pushFilterConfigToServer(
            DuctFaceNode.FilterBank bank,
            List<String> allow,
            List<String> deny,
            List<Integer> allowCaps,
            List<Integer> allowCaps2,
            boolean denyOverridesAllow,
            boolean editingAllowList);

    /** Client tick: sync deny-override flag from {@link DuctMenuSync}. */
    default void updateClientDenyOverridesFromSync() {}

    /** Settings copier virtual editor only. */
    default boolean isSettingsCopierVirtualEditor() {
        return false;
    }
}
