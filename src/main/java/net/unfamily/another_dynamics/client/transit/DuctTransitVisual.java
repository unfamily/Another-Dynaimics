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
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.unfamily.another_dynamics.duct.logistics.OutboundShipment;
import net.unfamily.another_dynamics.duct.logistics.TransitPhase;

import org.jetbrains.annotations.Nullable;

/**
 * Client-only: ghost item along a duct path. The server does not move stacks through each segment; delivery happens
 * when travel completes. Rendering uses synced timing ({@link #journeyStartGameTime}, {@link #totalTravelTicks},
 * {@link #ductPath}) to simulate motion between network updates.
 */
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

    /** Client rebuild from disk/chunk {@code DuctOutbound} (there is no TransitV1 in saved NBT). */
    public static DuctTransitVisual fromOutboundShipment(BlockPos ownerDuct, OutboundShipment s) {
        return new DuctTransitVisual(
                ownerDuct,
                s.stack.copy(),
                List.copyOf(s.ductPath),
                s.transitPhase,
                s.totalTravelTicks,
                s.travelTicks,
                s.edgeTicks,
                s.journeyStartGameTime);
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
            if (stack.isEmpty()) {
                continue;
            }
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

    /**
     * Visual progress 0 = start of leg, 1 = end (not yet delivered). Uses world time when {@link #journeyStartGameTime}
     * was saved (non-zero); otherwise falls back to the last synced {@link #travelTicks} snapshot (legacy or sparse data).
     */
    public float progress01(@Nullable Level level, float partialTick) {
        if (totalTravelTicks <= 0) {
            return 1f;
        }
        if (level != null && journeyStartGameTime != 0L) {
            float elapsed = (level.getGameTime() + partialTick) - journeyStartGameTime;
            return Math.clamp(elapsed / (float) totalTravelTicks, 0f, 1f);
        }
        float predictedTravel = Math.max(0f, travelTicks - partialTick);
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

