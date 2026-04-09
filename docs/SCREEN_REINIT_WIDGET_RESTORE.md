## Screen re-init / resize can wipe widgets (JEI overlay case)

### Problem
In Minecraft client GUIs (`Screen` / `AbstractContainerScreen`), certain interactions (notably JEI opening its recipe GUI
or overlay transitions) can trigger a **screen re-init** path such as:

- `init()` being called again
- `resize(Minecraft, int, int)` -> `init()` (common in vanilla)

When this happens, many widget registries are rebuilt and your custom widgets (e.g. `EditBox`, `Button`) can be
**dropped** unless you recreate them in `init()`.

Symptoms:

- Custom widgets (text fields + buttons) disappear after JEI opens/changes UI.
- “Manual” elements rendered directly in `renderBg` or `render` (not stored as widgets) may still show up.
  - Example: a ghost-slot drawn as a texture + icon, not an actual `Slot` or widget, continues rendering.

### Root cause
Widgets added via `addRenderableWidget(...)` live in internal `Screen` lists (`renderables`, `children`, etc.). Those
lists are typically cleared during `init()` and rebuilt from scratch.

If your “mode state” is stored separately (e.g. `editModeIndex >= 0`), the screen can still think it is in “edit mode”,
but its corresponding widgets were lost during the re-init.

### Fix pattern
Treat `init()` as the single source of truth for widget existence:

- Always recreate your persistent widgets in `init()`
- Additionally, if you have **transient mode widgets** (only exist while in a certain UI state), restore them in `init()`
  when the state says they should exist.

Important detail:

- Do **not** steal keyboard focus during restoration (especially when JEI is active). Only restore positions/visibility
  and text values.

### Example (from `DuctNodeScreen`)
The screen uses an “edit mode” state (`inEditMode()`) and an edit UI block created by `createEditModeUI()`.
To make it robust against re-init, `init()` restores the transient edit widgets when needed:

```java
// At the end of init():
applySubViewVisibility();

// If state says we're editing, restore transient widgets without grabbing focus.
if (inEditMode()) {
    createEditModeUI();
    applySubViewVisibility();
    reloadFilterEntryTextBoxFromList(false); // false = do not focus
}
```

Why this works:

- `createEditModeUI()` re-adds the `EditBox` and buttons via `addRenderableWidget`.
- `applySubViewVisibility()` ensures JEI/UI transitions don't leave widgets hidden.
- `reloadFilterEntryTextBoxFromList(false)` repopulates text without forcing focus (so JEI search can still work).

### Where to apply elsewhere
Use this pattern in any screen that:

- Has a “mode” or “subview” state machine
- Dynamically creates/removes widgets based on state
- Needs to coexist with JEI overlays or other mods that may cause `init()`/`resize()` to run again

### Checklist
- [ ] Ensure all core widgets are created in `init()`
- [ ] Ensure transient widgets can be recreated from state (idempotent creation)
- [ ] Restore transient widgets at the end of `init()` based on state
- [ ] Avoid calling `setFocused(true)` during restore
- [ ] Re-run visibility/layout methods after restoring (`applySubViewVisibility`, `layout*`)

