package net.unfamily.another_dynamics.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/**
 * Client → server: Sequential Buffer hub / Sequence editor actions.
 *
 * <p>Simple controls (dump, enable, clear, redstone cycles) use {@code action} + {@code listIndex}.
 * Step add/update also carry {@code filter}, {@code kindOrdinal}, {@code amount}, and {@code concatOrdinal}.
 */
public record SequentialBufferActionPayload(
        int action,
        int listIndex,
        int stepIndex,
        int kindOrdinal,
        int amount,
        String filter,
        int concatOrdinal)
        implements CustomPacketPayload {

    public static final int ACTION_DUMP = 0;
    public static final int ACTION_CYCLE_GATE = 1;
    public static final int ACTION_TOGGLE_ENABLE = 2;
    public static final int ACTION_CLEAR_LIST = 3;
    public static final int ACTION_CYCLE_LIST_OUTPUT = 4;
    public static final int ACTION_OPEN_EDIT = 5;
    public static final int ACTION_CLOSE_EDIT = 6;
    public static final int ACTION_ADD_STEP = 7;
    public static final int ACTION_UPDATE_STEP = 8;
    public static final int ACTION_REMOVE_STEP = 9;
    public static final int ACTION_SET_AMOUNT = 10;
    /** Copy machine-wide Sequence Lists into held Settings Copier (SEQUENTIAL). */
    public static final int ACTION_COPY_SETTINGS = 11;
    /** Paste SEQUENTIAL snapshot from held Settings Copier into this buffer. */
    public static final int ACTION_PASTE_SETTINGS = 12;
    /** Copy the currently edited Sequence List only (listIndex). */
    public static final int ACTION_COPY_LIST = 13;
    /** Paste a single-list SEQUENTIAL snapshot onto listIndex (or snapshot's index). */
    public static final int ACTION_PASTE_LIST = 14;
    /** Cycle machine gate backward ({@code previous()}). */
    public static final int ACTION_CYCLE_GATE_PREV = 15;
    /** Cycle per-list output redstone backward ({@code previous()}). */
    public static final int ACTION_CYCLE_LIST_OUTPUT_PREV = 16;
    /** Set concat channel ordinal for step at listIndex/stepIndex. */
    public static final int ACTION_SET_CONCAT = 17;
    /** Cycle concat channel forward for step at listIndex/stepIndex. */
    public static final int ACTION_CYCLE_CONCAT = 18;
    /** Cycle concat channel backward for step at listIndex/stepIndex. */
    public static final int ACTION_CYCLE_CONCAT_PREV = 19;
    /** Reorder steps in listIndex by filter weight / concat groups. */
    public static final int ACTION_REORDER_STEPS = 20;
    /**
     * Clear step content at listIndex/stepIndex while keeping the slot (empty filter, amount 1, ITEM).
     * Distinct from {@link #ACTION_UPDATE_STEP}, which rejects empty filters.
     */
    public static final int ACTION_CLEAR_STEP = 21;
    /**
     * Set inter-sequence delay ticks from {@code amount} (clamped 0..{@code MAX_INTER_SEQUENCE_DELAY}).
     */
    public static final int ACTION_SET_INTER_DELAY = 22;
    /** Set Sequence List custom name from {@code filter} (blank → default label). */
    public static final int ACTION_SET_LIST_NAME = 23;
    /** Toggle {@code strictSequentialIntake} on the buffer (default OFF). */
    public static final int ACTION_TOGGLE_STRICT_INTAKE = 24;

    public static final Type<SequentialBufferActionPayload> TYPE =
            new Type<>(
                    ResourceLocation.fromNamespaceAndPath(
                            AnotherDynamicsMod.MOD_ID, "sequential_buffer_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SequentialBufferActionPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        ByteBufCodecs.VAR_INT.encode(buf, p.action());
                        ByteBufCodecs.VAR_INT.encode(buf, p.listIndex());
                        ByteBufCodecs.VAR_INT.encode(buf, p.stepIndex());
                        ByteBufCodecs.VAR_INT.encode(buf, p.kindOrdinal());
                        ByteBufCodecs.VAR_INT.encode(buf, p.amount());
                        ByteBufCodecs.STRING_UTF8.encode(buf, p.filter() == null ? "" : p.filter());
                        ByteBufCodecs.VAR_INT.encode(buf, p.concatOrdinal());
                    },
                    buf ->
                            new SequentialBufferActionPayload(
                                    ByteBufCodecs.VAR_INT.decode(buf),
                                    ByteBufCodecs.VAR_INT.decode(buf),
                                    ByteBufCodecs.VAR_INT.decode(buf),
                                    ByteBufCodecs.VAR_INT.decode(buf),
                                    ByteBufCodecs.VAR_INT.decode(buf),
                                    ByteBufCodecs.STRING_UTF8.decode(buf),
                                    ByteBufCodecs.VAR_INT.decode(buf)));

    public SequentialBufferActionPayload(int action) {
        this(action, 0, 0, 0, 1, "", 0);
    }

    public SequentialBufferActionPayload(int action, int listIndex) {
        this(action, listIndex, 0, 0, 1, "", 0);
    }

    public SequentialBufferActionPayload(int action, int listIndex, int stepIndex) {
        this(action, listIndex, stepIndex, 0, 1, "", 0);
    }

    public SequentialBufferActionPayload(
            int action, int listIndex, int stepIndex, int kindOrdinal, int amount, String filter) {
        this(action, listIndex, stepIndex, kindOrdinal, amount, filter, 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
