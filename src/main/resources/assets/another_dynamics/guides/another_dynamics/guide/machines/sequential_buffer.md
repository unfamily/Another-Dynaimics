---
navigation:
  title: Sequential Buffer
  icon: another_dynamics:sequential_buffer
  parent: hubs/machines.md
  position: 1
item_ids:
  - another_dynamics:sequential_buffer
---
# Sequential Buffer

<ItemImage id="another_dynamics:sequential_buffer" />

A machine that runs **Sequence Lists**: ordered steps that wait for the right amounts of items or fluids before continuing.

## Lists and steps

Each list is a chain of steps with amount targets. Resources fill the input until a list is complete, then the buffer releases that sequence toward the front — one step at a time, keeping order. Several lists can be prepared independently; only one sequence leaves at a time, with a short pause between them (adjustable in the main GUI).

## Settings Copier

Hold a **Settings Copier** while the buffer GUI is open to copy lists (**C**) or paste (**P**), or Shift+right-click the block. You can also open **Configure** on the copier in Sequential mode. See [Settings Copier](../tools/settings_copier.md).
