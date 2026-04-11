package net.unfamily.another_dynamics.duct.module;

import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;

/**
 * Shared per-face module column: validates declarations, {@link ModuleDefinition#maxSlots()}, and
 * {@link ModuleDefinition#incompatibleWith()} (with {@link ModuleDefinition#incompatibilityActivation()} gating).
 */
public final class DuctFaceModuleItemHandler extends ItemStackHandler {
    private final DuctBlockEntity duct;
    private final Direction face;

    public DuctFaceModuleItemHandler(DuctBlockEntity duct, Direction face, int size) {
        super(Math.max(0, size));
        this.duct = duct;
        this.face = face;
    }

    @Override
    protected void onContentsChanged(int slot) {
        duct.setChanged();
        Level level = duct.getLevel();
        if (level != null && !level.isClientSide()) {
            duct.clampAllTransportExtractBatchesForFace(face);
        }
    }

    @Override
    public int getSlotLimit(int slot) {
        ItemStack cur = getStackInSlot(slot);
        if (!cur.isEmpty()) {
            var id = DuctModuleHelper.resolvedDeclarationId(cur);
            if (id.isPresent()) {
                var def = ModuleDefinitionRegistry.get(id.get());
                if (def.isPresent()) {
                    return Math.min(def.get().stackSize(), cur.getMaxStackSize());
                }
            }
        }
        return super.getSlotLimit(slot);
    }

    /**
     * {@link ItemStackHandler#insertItem} uses this (not only {@link #getSlotLimit(int)}) to cap merges; without this,
     * empty slots used {@code min(64, item max)} and accepted more items than {@link ModuleDefinition#stackSize()}.
     */
    @Override
    protected int getStackLimit(int slot, ItemStack stack) {
        if (stack.isEmpty()) {
            return super.getStackLimit(slot, stack);
        }
        return limitForIncoming(slot, stack);
    }

    private int nonEmptyModuleSlots() {
        int c = 0;
        for (int i = 0; i < getSlots(); i++) {
            if (!getStackInSlot(i).isEmpty()) {
                c++;
            }
        }
        return c;
    }

    /**
     * Non-empty module slots on this column after placing {@code incoming} into {@code slot} (merge/swap do not change
     * the count; filling an empty slot increases it by one).
     */
    private int filledModuleSlotsAfter(int slot, ItemStack incoming) {
        if (incoming.isEmpty()) {
            return nonEmptyModuleSlots();
        }
        if (getStackInSlot(slot).isEmpty()) {
            return nonEmptyModuleSlots() + 1;
        }
        return nonEmptyModuleSlots();
    }

    /** Max stack size for {@code incoming} if placed into {@code slot} (swap / insert / slot UI cap). */
    private int limitForIncoming(int slot, ItemStack incoming) {
        var id = DuctModuleHelper.resolvedDeclarationId(incoming);
        if (id.isPresent()) {
            var def = ModuleDefinitionRegistry.get(id.get());
            if (def.isPresent()) {
                return Math.min(def.get().stackSize(), incoming.getMaxStackSize());
            }
        }
        return Math.min(super.getSlotLimit(slot), incoming.getMaxStackSize());
    }

    /**
     * Public cap for {@link net.neoforged.neoforge.items.SlotItemHandler#getMaxStackSize(ItemStack)} — that API only
     * calls {@link IItemHandler#getSlotLimit(int)} (no stack), so slots must use this for empty-slot placement.
     */
    public int stackInsertLimit(int slot, ItemStack incoming) {
        return limitForIncoming(slot, incoming);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        ItemStack use = stack;
        if (!use.isEmpty()) {
            int lim = limitForIncoming(slot, use);
            if (use.getCount() > lim) {
                use = use.copyWithCount(lim);
            }
        }
        super.setStackInSlot(slot, use);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        if (DuctModuleHelper.resolvedDeclarationId(stack).isEmpty()) {
            return false;
        }
        Level level = duct.getLevel();
        HolderLookup.Provider registries = level == null ? null : level.registryAccess();
        var incomingId = DuctModuleHelper.resolvedDeclarationId(stack);
        if (incomingId.isEmpty()) {
            return false;
        }
        ModuleDefinition def = ModuleDefinitionRegistry.get(incomingId.get()).orElse(null);
        if (def == null) {
            return false;
        }
        ItemStack present = getStackInSlot(slot);
        if (!present.isEmpty()) {
            if (ItemStack.isSameItemSameComponents(present, stack)) {
                if (registries != null) {
                    int m = filledModuleSlotsAfter(slot, stack);
                    for (int i = 0; i < getSlots(); i++) {
                        if (i == slot) {
                            continue;
                        }
                        ItemStack o = getStackInSlot(i);
                        if (o.isEmpty()) {
                            continue;
                        }
                        if (DuctModuleHelper.modulesConflict(stack, o, registries, m)) {
                            return false;
                        }
                    }
                }
                return true;
            }
            if (registries == null || !DuctModuleHelper.sameSwapGroup(present, stack)) {
                return false;
            }
            int mSwap = filledModuleSlotsAfter(slot, stack);
            for (int i = 0; i < getSlots(); i++) {
                if (i == slot) {
                    continue;
                }
                ItemStack o = getStackInSlot(i);
                if (o.isEmpty()) {
                    continue;
                }
                if (DuctModuleHelper.modulesConflict(stack, o, registries, mSwap)) {
                    return false;
                }
            }
            int sameDeclElsewhere = 0;
            for (int i = 0; i < getSlots(); i++) {
                if (i == slot) {
                    continue;
                }
                ItemStack o = getStackInSlot(i);
                if (o.isEmpty()) {
                    continue;
                }
                if (incomingId.get().equals(DuctModuleHelper.resolvedDeclarationId(o).orElse(null))) {
                    sameDeclElsewhere++;
                }
            }
            return sameDeclElsewhere < def.maxSlots();
        }

        int m = filledModuleSlotsAfter(slot, stack);
        if (registries != null) {
            for (int i = 0; i < getSlots(); i++) {
                if (i == slot) {
                    continue;
                }
                ItemStack o = getStackInSlot(i);
                if (o.isEmpty()) {
                    continue;
                }
                if (DuctModuleHelper.modulesConflict(stack, o, registries, m)) {
                    return false;
                }
            }
        }
        int sameDeclSlotsElsewhere = 0;
        for (int i = 0; i < getSlots(); i++) {
            if (i == slot) {
                continue;
            }
            ItemStack o = getStackInSlot(i);
            if (o.isEmpty()) {
                continue;
            }
            if (incomingId.get().equals(DuctModuleHelper.resolvedDeclarationId(o).orElse(null))) {
                sameDeclSlotsElsewhere++;
            }
        }
        if (sameDeclSlotsElsewhere >= def.maxSlots()) {
            return false;
        }
        return true;
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack existing = getStackInSlot(slot);
        if (!existing.isEmpty()
                && !ItemStack.isSameItemSameComponents(existing, stack)
                && isItemValid(slot, stack)) {
            int limit = limitForIncoming(slot, stack);
            if (stack.getCount() > limit) {
                return stack;
            }
            if (simulate) {
                return existing.copy();
            }
            ItemStack displaced = existing.copy();
            setStackInSlot(slot, stack.copy());
            onContentsChanged(slot);
            return displaced;
        }
        return super.insertItem(slot, stack, simulate);
    }
}
