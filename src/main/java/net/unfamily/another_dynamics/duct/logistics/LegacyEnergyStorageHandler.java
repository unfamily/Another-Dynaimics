package net.unfamily.another_dynamics.duct.logistics;

import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * Exposes a legacy {@link IEnergyStorage} (immediate-mutation, non-transactional) as the 26.x transfer
 * {@link EnergyHandler} capability. Rollback on an aborted transaction is emulated by snapshotting the stored
 * amount and re-applying the delta with {@link IEnergyStorage#receiveEnergy}/{@link IEnergyStorage#extractEnergy}.
 */
public final class LegacyEnergyStorageHandler extends SnapshotJournal<Integer> implements EnergyHandler {
    private final IEnergyStorage storage;

    public LegacyEnergyStorageHandler(IEnergyStorage storage) {
        this.storage = storage;
    }

    @Override
    protected Integer createSnapshot() {
        return storage.getEnergyStored();
    }

    @Override
    protected void revertToSnapshot(Integer snapshot) {
        int current = storage.getEnergyStored();
        if (snapshot < current) {
            storage.extractEnergy(current - snapshot, false);
        } else if (snapshot > current) {
            storage.receiveEnergy(snapshot - current, false);
        }
    }

    @Override
    public long getAmountAsLong() {
        return storage.getEnergyStored();
    }

    @Override
    public long getCapacityAsLong() {
        return storage.getMaxEnergyStored();
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        if (amount <= 0 || !storage.canReceive()) {
            return 0;
        }
        updateSnapshots(transaction);
        return storage.receiveEnergy(amount, false);
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        if (amount <= 0 || !storage.canExtract()) {
            return 0;
        }
        updateSnapshots(transaction);
        return storage.extractEnergy(amount, false);
    }
}
