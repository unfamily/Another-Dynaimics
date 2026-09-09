package net.unfamily.another_dynamics.duct;

/**
 * Item transport parameters from datapack {@code can_transport} (dec {@code item}).
 * <p><strong>Material-lane family:</strong> parallel changes often belong in {@link DuctFluidTransportSpec},
 * {@link DuctGasTransportSpec}, and universal declarations that include item/fluid/gas.
 */
public record DuctItemTransportSpec(
        int batchDefault,
        int batchMax,
        int rateDefaultTicks,
        int rateMinTicks,
        int speedDefault,
        int speedMin,
        /** Shipments scheduled per rate fire (datapack {@code seq_stack.default}). */
        int sequentialStackDefault,
        /** Cap for face sequential-stack setting; negative = only default + modules (clamped by server hard cap). */
        int sequentialStackMax,
        /** Max allow-pattern entries per bank for non-hybrid modes and for legacy root filter lists. */
        int filterAllowSlots,
        int filterDenySlots,
        /** Max allow/deny entries per bank when the face is in a hybrid mode ({@code Extr/Filt}, {@code Retr/Extr}). */
        int filterAllowHybridSlots,
        int filterDenyHybridSlots
) {
    public static final int UNLIMITED_BATCH = Integer.MAX_VALUE;
    /** Absolute ceiling for sequential-stack steps per rate fire (server + GUI). */
    public static final int HARD_SEQUENTIAL_STACK_CAP = 16;

    public static DuctItemTransportSpec fallback() {
        return new DuctItemTransportSpec(8, UNLIMITED_BATCH, 10, 1, 20, 0, 1, HARD_SEQUENTIAL_STACK_CAP, 3, 3, 3, 3);
    }

    /**
     * Datapack {@code speed}: ticks per duct block on a path for billed travel.
     * {@code speed.min} may be {@code 0} for instant travel ({@code 0} ticks per block).
     */
    public int effectiveSpeed(int configured) {
        int s = configured <= 0 ? speedDefault : configured;
        int min = Math.max(0, speedMin);
        return Math.max(min, s);
    }

    public int clampedBatch(int requested) {
        int max = batchMax < 0 ? UNLIMITED_BATCH : Math.max(1, batchMax);
        return Math.min(Math.max(0, requested), max);
    }

    /**
     * Max extract/retrieve batch the player may configure: {@code batchDefault + moduleBonus}, then limited by datapack
     * {@code batch.max} when that value is non-negative; when {@code batch.max} is negative, only default + bonus applies.
     */
    public int extractBatchSettingCap(int moduleBonus) {
        int base = batchDefault + moduleBonus;
        if (batchMax < 0) {
            return Math.max(1, base);
        }
        return Math.max(1, Math.min(batchMax, base));
    }

    /**
     * Floors at {@code rate.min} (at least 1). Never remaps {@code 0}/negative to the datapack default —
     * aggressive module {@code rate.mult} must land on the minimum interval, not reset to base rate.
     */
    public int clampedRateTicks(int requested) {
        int min = Math.max(1, rateMinTicks);
        return Math.max(min, requested);
    }

    /**
     * Max sequential stacks the player may configure: {@code sequentialStackDefault + moduleBonus}, limited by datapack {@code seq_stack.max}
     * when non-negative, then by {@link #HARD_SEQUENTIAL_STACK_CAP}.
     */
    public int extractSequentialStackSettingCap(int moduleBonus) {
        int base = sequentialStackDefault + moduleBonus;
        int capped;
        if (sequentialStackMax < 0) {
            capped = Math.max(1, base);
        } else {
            capped = Math.max(1, Math.min(sequentialStackMax, base));
        }
        return Math.min(HARD_SEQUENTIAL_STACK_CAP, capped);
    }

    /** Floors at 1 and ceilings at the setting cap / hard cap. */
    public int clampedSequentialStack(int requested, int settingCap) {
        int cap = Math.max(1, Math.min(HARD_SEQUENTIAL_STACK_CAP, settingCap));
        int v = requested <= 0 ? sequentialStackDefault : requested;
        return Math.max(1, Math.min(cap, v));
    }
}
