---
navigation:
  title: Machine Connector
  icon: another_dynamics:machine_connector
  parent: hubs/logistics_devices.md
  position: 2
item_ids:
  - another_dynamics:machine_connector
---
# Machine Connector

<ItemImage id="another_dynamics:machine_connector" />

The **Machine Connector** extends a machine’s inventory and capability faces outward so ducts and pipes can attach where space is tight.

## Placement

Place against a machine (or another Machine Connector) → the front points at that block. Otherwise the front follows the direction you are looking.

## How faces map

Outer faces keep **world-aligned** directions: the top of the connector talks to the **top** of the machine, east to east, and so on. The face opposite the front extends that same contact side of the machine farther out.

You can **chain** several connectors in a line; each faces the next until the last one faces the machine.

## Tips

- Put ducts on the outer sides of the connector, not squeezed against the machine.
- Chain connectors to reach around corners or pull a face out by several blocks.
