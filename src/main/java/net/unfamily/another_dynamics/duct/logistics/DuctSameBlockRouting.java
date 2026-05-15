package net.unfamily.another_dynamics.duct.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Same-block (distance 0) routing rules aligned with {@link DuctTargetSelector} item logistics.
 */
public final class DuctSameBlockRouting {
    private DuctSameBlockRouting() {}

    /**
     * Skip delivering to the extractor's own inventory face on the same duct block (unless self-feed is enabled).
     */
    public static boolean skipSameBlockDestFace(
            BlockPos srcPos,
            Direction sourceFace,
            BlockPos destPos,
            Direction destFace,
            boolean allowSelfFeed) {
        return !allowSelfFeed && srcPos.equals(destPos) && destFace == sourceFace;
    }

    /** Skip using the retriever's own inventory face as a donor on the same duct block. */
    public static boolean skipSameBlockDonorFace(
            BlockPos retrieverPos, Direction retrieverFace, BlockPos donorPos, Direction donorFace) {
        return retrieverPos.equals(donorPos) && donorFace == retrieverFace;
    }
}
