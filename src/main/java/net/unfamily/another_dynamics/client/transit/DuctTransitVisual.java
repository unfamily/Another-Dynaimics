package net.unfamily.another_dynamics.client.transit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.unfamily.another_dynamics.duct.logistics.TransitPhase;

public final class DuctTransitVisual {
    public final BlockPos ownerDuct;
    public final ItemStack stack;
    public final List<BlockPos> ductPath;
    public final TransitPhase phase;
    public final int totalTravelTicks;
    public final int travelTicks;
    public final int edgeTicks;
    public final long journeyStartGameTime;

    public DuctTransitVisual(
            BlockPos ownerDuct,
            ItemStack stack,
            List<BlockPos> ductPath,
            TransitPhase phase,
            int totalTravelTicks,
            int travelTicks,
            int edgeTicks,
            long journeyStartGameTime) {
        this.ownerDuct = ownerDuct;
        this.stack = stack;
        this.ductPath = ductPath;
        this.phase = phase;
        this.totalTravelTicks = totalTravelTicks;
        this.travelTicks = travelTicks;
        this.edgeTicks = Math.max(1, edgeTicks);
        this.journeyStartGameTime = journeyStartGameTime;
    }

    public static List<DuctTransitVisual> listFromUpdateTag(
            BlockPos ownerDuct, CompoundTag root, HolderLookup.Provider registries) {
        if (!root.contains("TransitV1", Tag.TAG_LIST)) {
            return List.of();
        }
        ListTag list = root.getList("TransitV1", Tag.TAG_COMPOUND);
        ArrayList<DuctTransitVisual> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            if (!t.contains("Stack", Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag stackTag = t.getCompound("Stack");
            if (stackTag.isEmpty()) {
                continue;
            }
            ItemStack stack = ItemStack.parse(registries, stackTag).orElse(ItemStack.EMPTY);
            ListTag plist = t.getList("Path", Tag.TAG_COMPOUND);
            ArrayList<BlockPos> path = new ArrayList<>(plist.size());
            for (int j = 0; j < plist.size(); j++) {
                CompoundTag pt = plist.getCompound(j);
                path.add(new BlockPos(pt.getInt("X"), pt.getInt("Y"), pt.getInt("Z")));
            }
            out.add(
                    new DuctTransitVisual(
                            ownerDuct,
                            stack,
                            Collections.unmodifiableList(path),
                            TransitPhase.fromOrdinal(t.getByte("Ph")),
                            t.getInt("Tot"),
                            t.getInt("Tr"),
                            t.getInt("Ed"),
                            t.getLong("J0")));
        }
        return Collections.unmodifiableList(out);
    }

    public float progress01(float partialTicks) {
        if (totalTravelTicks <= 0) {
            return 1f;
        }
        float predictedTravel = Math.max(0f, travelTicks - partialTicks);
        return Math.clamp((totalTravelTicks - predictedTravel) / (float) totalTravelTicks, 0f, 1f);
    }

    public Vec3 positionAt(float progress01) {
        if (ductPath.isEmpty()) {
            return Vec3.atCenterOf(ownerDuct);
        }
        float p = Math.clamp(progress01, 0f, 1f);
        float scaled = p * Math.max(0, ductPath.size() - 1);
        int i0 = (int) Math.floor(scaled);
        int i1 = Math.min(ductPath.size() - 1, i0 + 1);
        float frac = scaled - i0;
        Vec3 a = Vec3.atCenterOf(ductPath.get(i0));
        Vec3 b = Vec3.atCenterOf(ductPath.get(i1));
        return a.lerp(b, frac);
    }
}

