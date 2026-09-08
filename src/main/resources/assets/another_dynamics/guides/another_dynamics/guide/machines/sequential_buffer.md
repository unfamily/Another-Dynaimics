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

The **Sequential Buffer** gathers matching **items**, **fluids**, and (with Mekanism) **chemicals / gas**, then releases them as ordered **Sequence Lists**. Use it when a process must wait for exact amounts and leave the machine in a fixed step order — not as a free-for-all buffer.

## Placement and sides

The block **faces** the destination. That front face is the **output**: the buffer pushes staged resources into whatever inventory, tank, or chemical handler sits there. Every other face is **input** for ducts, hoppers, and similar inserts.

Craft it with iron, a Resonating Conductor, a wooden chest or barrel, a hopper, and two comparators (chest and barrel recipes share the same group).

## How a sequence runs

1. **Intake** — matching resources fill the input buffer (27 item slots; large fluid / chemical tanks).
2. **Ready** — when every step of an enabled Sequence List is satisfied in the input, that list can stage.
3. **Stage** — the machine moves one ready list into the output buffer (only one list is active at a time).
4. **Eject** — steps leave toward the front **in list order**, with a short pause between steps (5 ticks) and backpressure if the destination cannot accept more.
5. **Cooldown** — after a list finishes, an **inter-sequence delay** runs before another list may stage (default 20 ticks; set minutes / seconds / ticks in the hub GUI, up to 15 minutes).

Several lists can fill in parallel in the input. Only one sequence ejects at a time.

## Sequence Lists

Open the GUI → **Sequence Lists**. You get **10** list slots.

Per list you can:

- **Enable / disable** — disabled lists neither accept for that list’s need nor stage.
- **Name** the list (shown while editing).
- **Edit** steps (filters and amounts).
- **Clear** the list.
- Set a **Ready List Signal** (redstone output for that list — see below).

### Steps

Each step has:

- **Type** — Item, Fluid, or Gas (cycle in the step editor). Gas needs Mekanism.
- **Filter** — same style as duct filters (ids, tags, `@mod`, macros, `|` OR). Open **Valid Keys** in the editor for the full syntax for that type. Sequential steps do **not** allow the `&anything_else` item macro.
- **Amount** — how much of a matching resource that step needs (items as counts; fluids / chemicals as their usual units).

You can drag from JEI into the ghost slot, type a filter, or use **Convert** to turn a filled bucket / tank / chemical container into a fluid or gas filter line. **Reorder** sorts steps by filter weight (same order rules as duct filters).

## Strict intake

On the hub: **Strict ON** / **Strict OFF**.

| Mode | Behavior |
|------|----------|
| **Strict ON** | Each enabled list only accepts resources for its **first incomplete** step. Safer with buffered ducts that may already be shipping the next kinds of items. |
| **Strict OFF** | Matching future steps can fill in parallel. Faster, but buffered duct runs can jam if later steps fill while earlier ones are still waiting. |

## Redstone gate (machine)

The right-column gate controls whether the machine may **intake / stage** new sequences (eject already in progress continues with backpressure as usual).

| Mode | Meaning |
|------|---------|
| **Ignored** | Always allowed. Neighbor redstone is ignored. |
| **Low** | Works only while the machine is **unpowered**. |
| **High** | Works only while the machine is **powered**. |
| **Disabled** | Never stages a new sequence. |
| **Auto** (default) | Comparator-style on the **front destination**: if that block holds any **items, fluids, or chemicals**, intake and staging turn off until it is empty again. |

While the buffer is emitting a ready redstone pulse, neighbor power is ignored for the gate so the machine does not fight its own signal.

## Ready List Signal (per list)

Each Sequence List can emit redstone when its output-side readiness matches the mode:

| Mode | Signal |
|------|--------|
| **Disabled** | No ready signal for this list. |
| **Low** | Powered while this list is **not** fully gathered in the output. |
| **High** | Powered while this list **is** ready in the output. |
| **Pulse** | Short pulse when the list becomes ready. |

## Dump and cleanup

**D** (Dump) empties the **input** item buffer into your inventory (overflow drops in the world). Fluids and gas in the input are discarded.

Editing lists can also clear input that no longer matches any enabled list (orphaned stacks go to you / the world; unmatched fluids / gas are cleared).

## Settings Copier

Put a **Settings Copier** in the GUI slot:

- **C** — copy **all** Sequence Lists (and related buffer settings) into the copier.
- **P** — paste from the copier.
- Available on the hub, Sequence Lists view, and list editor.

You can also **Shift+right-click** the block with the copier, or open **Configure** on the copier in Sequential mode for a virtual editor. Details: [Settings Copier](../tools/settings_copier.md).

## Tips

- Point the front at the machine or chest that should receive the ordered eject; feed the other faces with ducts.
- Prefer **Strict ON** when extractors already buffer several item kinds toward the Sequential Buffer.
- Use **Auto** when the front inventory / tank should empty before the next sequence starts filling again.
- For filter syntax shared with ducts, see [Filters](../ducts/filters.md).
