---
navigation:
  title: Settings Copier
  icon: another_dynamics:settings_copier
  parent: hubs/tools.md
  position: 1
item_ids:
  - another_dynamics:settings_copier
---
# Settings Copier

<ItemImage id="another_dynamics:settings_copier" />

Copies duct **node** settings, **filter lists**, or **Sequential Buffer** sequence data.

- Put it in a Copy slot and press **Copy** / **Paste**, or Shift+right-click a duct node face.
- Right-click air or a block to open the editor; use **Configure** for a virtual setup (ducts or Sequential Buffer, depending on mode).
- Place it in a crafting grid to clear stored data completely.
- For Sequential Buffer: hold it in the GUI and use **C** / **P**, or Shift+right-click the block.

The tooltip tells you what mode is active and whether something is stored.

## Pipez import

When **Pipez** is installed, the Settings Copier can **import** (one-way) into Another Dynamics — it never writes back to Pipez.

- **Shift+right-click** a Pipez pipe to copy face settings into the copier as **whole** duct settings.
- In the copier GUI, **Import** can pull filter lists from compatible Pipez filter upgrades/modules when Pipez is present.

Paste the result onto AD duct nodes.

## Other mods

The Settings Copier is also wired into other Unfamily mods — for example **Iskandert's Utilities** and **Another Quarries**. Compatible machines expose a dedicated Settings Copier slot in their GUI (with Copy / Paste); put the item there and use those buttons.

Other mods may add the same kind of slot. If you see one in a GUI, it works the same way: place the copier, then copy or paste.
