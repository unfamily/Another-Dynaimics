---
navigation:
  title: Nodes and GUI
  parent: hubs/ducts.md
  position: 3
---
# Nodes and GUI

Each duct face can act as a **node** toward an adjacent inventory, tank, or energy buffer.

## Face modes

- **Default** — the network can push into the attached block.
- **Filtering** — inserts respect your deny/allow lists.
- **Extract / retrieve** — pulls from the attached storage into the duct (or toward a bound destination).

## Redstone and visuals

Nodes can wait for redstone before working. **Opaque** options hide pipe visuals if they feel heavy: network-only or everything you see.

## Modules and filters

The node GUI has module slots, allow/deny lists, and **Valid Keys** help. More detail: [Filters](filters.md), [Modules](modules.md).
