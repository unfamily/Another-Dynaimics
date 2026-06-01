package net.unfamily.another_dynamics.duct.filterimport;

/** Transport channel for external filter import (energy ignored). */
public enum FilterImportChannel {
    ITEM,
    FLUID,
    GAS;

    public String pipezComponentId() {
        return name().toLowerCase();
    }
}
