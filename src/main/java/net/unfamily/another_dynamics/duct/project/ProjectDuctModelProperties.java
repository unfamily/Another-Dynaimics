package net.unfamily.another_dynamics.duct.project;

import net.neoforged.neoforge.model.data.ModelProperty;

/**
 * Client model data for project ducts (node preview is not stored in block state to keep the state table small).
 */
public final class ProjectDuctModelProperties {
    public static final ModelProperty<Integer> NODE_PREVIEW_MASK = new ModelProperty<>();

    private ProjectDuctModelProperties() {}
}
