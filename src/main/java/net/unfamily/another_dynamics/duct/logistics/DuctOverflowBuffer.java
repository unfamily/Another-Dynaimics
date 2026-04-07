package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
/**
 * Per-duct internal overflow: adaptive list of stacks (hidden; not a GUI inventory).
 * {@link #SCHEDULING_UNAVAILABLE_LINE_THRESHOLD} only gates new pulls (node “busy”); the list may grow without a hard cap.
 */
public final class DuctOverflowBuffer {
    /** Fixed in code: at or above this many non-empty buffer lines, the duct stops accepting new extraction/retrieve schedules. */
    public static final int SCHEDULING_UNAVAILABLE_LINE_THRESHOLD = 5;

    private final ArrayList<ItemStack> stacks = new ArrayList<>();

    public List<ItemStack> viewStacks() {
        return List.copyOf(stacks);
    }

    public int nonEmptyLineCount() {
        int n = 0;
        for (ItemStack s : stacks) {
            if (!s.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    public boolean isSchedulingUnavailableForNewPulls() {
        return nonEmptyLineCount() >= SCHEDULING_UNAVAILABLE_LINE_THRESHOLD;
    }

    /**
     * Merge into existing stacks where possible; otherwise append (list grows past threshold when needed).
     */
    public void absorb(ServerLevel level, DuctBlockEntity duct, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack rest = stack.copy();
        mergeOrAppend(rest, duct);
        if (!rest.isEmpty()) {
            stacks.add(rest);
            duct.setChanged();
        }
    }

    private void mergeOrAppend(ItemStack rest, DuctBlockEntity duct) {
        for (int i = 0; i < stacks.size() && !rest.isEmpty(); i++) {
            ItemStack slot = stacks.get(i);
            if (slot.isEmpty()) {
                continue;
            }
            if (ItemStack.isSameItemSameComponents(slot, rest)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                int move = Math.min(space, rest.getCount());
                if (move > 0) {
                    slot.grow(move);
                    rest.shrink(move);
                    duct.setChanged();
                }
            }
        }
    }

    public void save(HolderLookup.Provider registries, CompoundTag tag) {
        ListTag list = new ListTag();
        for (ItemStack s : stacks) {
            if (s.isEmpty()) {
                continue;
            }
            CompoundTag st = new CompoundTag();
            s.save(registries, st);
            list.add(st);
        }
        tag.put("OverflowBuf", list);
    }

    public void load(HolderLookup.Provider registries, CompoundTag tag) {
        stacks.clear();
        if (!tag.contains("OverflowBuf", Tag.TAG_LIST)) {
            return;
        }
        ListTag list = tag.getList("OverflowBuf", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ItemStack.parse(registries, list.getCompound(i))
                    .ifPresent(s -> {
                        if (!s.isEmpty()) {
                            stacks.add(s);
                        }
                    });
        }
    }

    public void pruneEmptyLines() {
        stacks.removeIf(ItemStack::isEmpty);
    }

    /**
     * Try to move one logical line into attached storages; merge-compatible lines may combine afterward.
     */
    public void tickTryDrainOne(ServerLevel level, DuctBlockEntity duct) {
        pruneEmptyLines();
        if (stacks.isEmpty()) {
            return;
        }
        ItemStack head = stacks.get(0);
        if (head.isEmpty()) {
            stacks.remove(0);
            duct.setChanged();
            return;
        }
        ItemStack left = duct.tryInsertIntoAllStorageFacesRespectingRules(level, head.copy());
        if (left.isEmpty()) {
            stacks.remove(0);
            duct.setChanged();
        } else if (left.getCount() != head.getCount()) {
            stacks.set(0, left);
            duct.setChanged();
        }
    }
}
