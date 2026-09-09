package net.unfamily.another_dynamics.machine.sequential;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.unfamily.another_dynamics.duct.DuctTransportKind;

/** One filter-like step inside a {@link SequentialTaskData}. */
public final class SequenceStepData {
    public enum Kind {
        ITEM,
        FLUID,
        GAS;

        public static Kind fromOrdinal(int o) {
            Kind[] values = values();
            if (o < 0 || o >= values.length) {
                return ITEM;
            }
            return values[o];
        }

        public DuctTransportKind toTransportKind() {
            return switch (this) {
                case ITEM -> DuctTransportKind.ITEM;
                case FLUID -> DuctTransportKind.FLUID;
                case GAS -> DuctTransportKind.GAS;
            };
        }
    }

    private String filter = "";
    private Kind kind = Kind.ITEM;
    private int amount = 1;

    public String filter() {
        return filter;
    }

    public void setFilter(String filter) {
        this.filter = filter == null ? "" : filter;
    }

    public Kind kind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind == null ? Kind.ITEM : kind;
    }

    public int amount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = Math.max(1, amount);
    }

    public boolean isEmpty() {
        return filter == null || filter.isBlank();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Filter", filter == null ? "" : filter);
        tag.putByte("Kind", (byte) kind.ordinal());
        tag.putInt("Amount", amount);
        return tag;
    }

    public void load(CompoundTag tag) {
        filter = tag.contains("Filter", Tag.TAG_STRING) ? tag.getString("Filter") : "";
        kind = Kind.fromOrdinal(tag.contains("Kind", Tag.TAG_BYTE) ? tag.getByte("Kind") & 0xFF : 0);
        amount = Math.max(1, tag.contains("Amount", Tag.TAG_INT) ? tag.getInt("Amount") : 1);
    }

    public SequenceStepData copy() {
        SequenceStepData copy = new SequenceStepData();
        copy.filter = this.filter;
        copy.kind = this.kind;
        copy.amount = this.amount;
        return copy;
    }
}
