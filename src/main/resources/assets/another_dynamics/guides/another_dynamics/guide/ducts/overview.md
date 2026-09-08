---
navigation:
  title: Overview
  icon: another_dynamics:duct
  parent: hubs/ducts.md
  position: 1
item_ids:
  - another_dynamics:duct
---
# Overview

All **ducts currently available** in your game (kinds that did not load simply do not appear):

<DuctGrid />

Place them like ducts. Matching kinds connect together and to inventories, tanks, or energy handlers on their faces. Right-click a face to open the **node** GUI: extract/insert, filters, modules, and display options.

For mass scaffolding and conversion layouts, see [Project Duct](project_duct.md).

## Nodes and GUI

Each duct face can act as a **node** toward an adjacent inventory, tank, or energy buffer.

### Face modes

- **Default** — the network can push into the attached block.
- **Filtering** — inserts respect your deny/allow lists.
- **Extract / retrieve** — pulls from the attached storage into the duct (or toward a bound destination).

### Redstone and visuals

Nodes can wait for redstone before working. **Opaque** options hide duct visuals if they feel heavy: network-only or everything you see.

### Modules and filters

The node GUI has module slots, allow/deny lists, and **Valid Keys** help. More detail: [Filters](filters.md), [Modules](modules.md).

## Compatibility

- **FTB Ultimine** — mass-breaks only; selects ducts of the **same logical type** on the **same connected network**. Project Duct scaffolds use the same rule on their Project Duct network. Ultimine does **not** mass-place.
- **Cable Facades** — ducts and Project Duct support facades; converting a Project Duct network preserves facades when possible.

See also: [Transport kinds](transport_kinds.md), [Filters](filters.md), [Modules](modules.md), [Project Duct](project_duct.md).
