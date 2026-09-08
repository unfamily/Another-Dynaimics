---
navigation:
  title: Filters
  parent: hubs/ducts.md
  position: 4
---
# Filters

Node faces can use **Deny** and **Allow** lists. Deny blocks matches; Allow only lets matching things through when filtering is on. List logic chooses whether deny wins or allow can bypass deny.

## How to write a filter line

This is the one place where **ids** and **tags** matter: each line is a short rule the duct checks against items, fluids, or gas.

- An **id** names one thing (the same style as in JEI when you look up an item).
- A **`#` tag** matches a whole group (for example all iron ingots in a common tag).
- Open **Valid Keys** in the GUI for the full list of operators and macros for that list type.

You can also drag from JEI into the ghost slot instead of typing.

## Ghost ingredients

With JEI (or similar), drag ingredients into filter slots as **ghost** entries — they set the rule without using up the real stack.

## Concat groups

Filters that share a concat letter are grouped. Matching can require every line in the group. Allow lines can also set **Insert Limit** and **Keep** for how much matching storage may hold.
