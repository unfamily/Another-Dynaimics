---
navigation:
  title: Project Duct
  icon: another_dynamics:project_duct
  parent: hubs/ducts.md
  position: 6
item_ids:
  - another_dynamics:project_duct
---
# Project Duct

<ItemImage id="another_dynamics:project_duct" />

**Project Duct** is a placement scaffold between “drawing a duct run” and real ducts. It has **no BlockEntity and no GUI** — a plain block that connects like a duct so mass-placement tools can lay out a network cheaply.

## Mass placement

Use it with tools that place many blocks at once (for example **Construction Sticks** and similar). Sketch the layout first, then convert.

## Convert to real ducts

Hold a definitive duct item and **Shift+right-click** a Project Duct to convert the connected Project Duct network into that duct kind. Connection / wrench disconnect masks are preserved where possible.

## Wrench

The **Bulky Wrench** and other compatible wrenches (common wrench tags) can disconnect Project Duct faces the same way as real ducts, so you can shape the scaffold before converting.

## Compatibility

- **FTB Ultimine** — mass-breaks connected Project Duct on the same scaffold network only (does **not** mass-place).
- **Cable Facades** — Project Duct is facade-capable; conversion keeps facades when the Cable Facades integration can preserve them.
