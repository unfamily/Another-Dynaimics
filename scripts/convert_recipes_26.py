#!/usr/bin/env python3
"""Convert Another Dynamics recipe JSON files to Minecraft 26.x string ingredient format."""

from __future__ import annotations

import json
from pathlib import Path

RECIPE_ROOT = Path(
    "/home/unfamily/IdeaProjects/Another-Dynaimics-26.1.2/src/main/resources/data/another_dynamics/recipe"
)


def ingredient_to_string(value: object) -> str:
    if isinstance(value, str):
        return value
    if not isinstance(value, dict):
        raise ValueError(f"Unsupported ingredient: {value!r}")
    if "tag" in value:
        tag = value["tag"]
        return tag if str(tag).startswith("#") else f"#{tag}"
    if "item" in value:
        return str(value["item"])
    raise ValueError(f"Unsupported ingredient object: {value!r}")


def convert_obj(obj: object) -> object:
    if isinstance(obj, dict):
        if set(obj.keys()) <= {"tag", "item"} and ("tag" in obj or "item" in obj):
            return ingredient_to_string(obj)
        return {k: convert_obj(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [convert_obj(v) for v in obj]
    return obj


def main() -> None:
    count = 0
    for path in sorted(RECIPE_ROOT.rglob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        converted = convert_obj(data)
        path.write_text(json.dumps(converted, indent=2) + "\n", encoding="utf-8")
        count += 1
        print("converted", path.relative_to(RECIPE_ROOT))
    print(f"Done: {count} recipes")


if __name__ == "__main__":
    main()
