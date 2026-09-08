package net.unfamily.another_dynamics.guide;

import guideme.compiler.PageCompiler;
import guideme.compiler.tags.BlockTagCompiler;
import guideme.document.block.LytBlockContainer;
import guideme.document.block.LytItemGrid;
import guideme.libs.mdast.mdx.model.MdxJsxElementFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.registry.ModItems;

/**
 * GuideME tag {@code <DuctGrid />} — shows every duct currently loaded in {@link DuctDefinitionRegistry}.
 * Renders nothing if the registry is empty (reload not ready / no definitions).
 */
public final class DuctGridTagCompiler extends BlockTagCompiler {
    @Override
    public Set<String> getTagNames() {
        return Set.of("DuctGrid", "another_dynamics:DuctGrid");
    }

    @Override
    protected void compile(PageCompiler compiler, LytBlockContainer parent, MdxJsxElementFields el) {
        List<DuctDefinition> defs = new ArrayList<>(DuctDefinitionRegistry.all().values());
        if (defs.isEmpty()) {
            return;
        }
        defs.sort(Comparator.comparing(DuctDefinition::logicalId));
        LytItemGrid grid = new LytItemGrid();
        for (DuctDefinition def : defs) {
            grid.addItem(ModItems.createDuctStack(def.logicalId()));
        }
        parent.append(grid);
    }
}
