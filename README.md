# Village Walls (Forge 1.20.1)

Village Walls is a server-side mod that detects a village footprint from generated village structures or village POIs, expands it by a configurable buffer, and builds enclosing walls from data-driven styles. It then finds the flattest wall segments and places adjacent double-door gates there.

## Features

- Village perimeter tracing with configurable buffer radius.
- Data-driven regional wall styles loaded from datapack JSON under `data/village_walls/wall_styles`.
- Deterministic foundations, mixed wall courses, supports, caps, weathering, and sparse attachments.
- Automatic biome-family selection through datapack selectors under `data/village_walls/wall_style_selectors`.
- Optional modded palette entries that disappear safely when their blocks are unavailable.
- Automatic wall generation when village structure chunks load near a player. Missing village and wall-placement chunks are preloaded in a bounded queue before building, then released.
- Automatic flatness scoring of wall segments.
- One primary adjacent double-door gate on the flattest segment.
- Additional gates on other equally flat segments (up to a configured cap).
- Seven regional styles are included: temperate, taiga, snowy, savanna, desert, swamp, and tropical.
- Large villages retain their dedicated walkable stone-brick rampart style.

## Command

Walls are built automatically with the default style when village structure chunks load. Use the command for manual builds, alternate styles, or custom radii:

- `/village_walls styles`
- `/village_walls build <style> <searchRadius> <buffer> <maxDoors>`

Example:

```text
/village_walls build village_walls:taiga 128 6 8
```

## Wall Style JSON Schema

```json
{
  "thickness": 1,
  "height": 3,
  "walkable": false,
  "support_every": 5,
  "palettes": {
    "foundation": [
      { "block": "minecraft:cobblestone", "weight": 5 },
      { "block": "minecraft:mossy_cobblestone", "weight": 1 }
    ],
    "body": [
      { "block": "minecraft:stone_bricks", "weight": 7 },
      { "block": "quark:limestone_bricks", "weight": 2, "optional": true }
    ],
    "support": [{ "block": "minecraft:stripped_oak_log[axis=y]" }],
    "cap": [{ "block": "minecraft:stone_bricks" }]
  },
  "details": [
    {
      "block": "minecraft:wall_torch",
      "spacing": 17,
      "salt": 41,
      "side": "inside",
      "vertical_offset": 2
    }
  ]
}
```

Rules:
- `thickness >= 1`
- `height >= 2`
- `support_every >= 2`
- Every palette must contain a non-optional `minecraft` fallback.
- Palette weights must be positive. Block-state properties use `namespace:block[property=value]` syntax.
- Detail spacing must be at least 6. `side` is `inside`, `outside`, or `both`.
- Missing optional blocks are ignored. A missing required block rejects the datapack reload.
- `parapet` is optional and defaults to `cap`; walkable ramparts can define it separately.

Selector files contain an ordered `selectors` array. Higher priorities win; each entry names a style and one or more case-sensitive fragments matched against the biome registry ID:

```json
{
  "selectors": [
    {
      "style": "village_walls:snowy",
      "priority": 70,
      "biome_patterns": ["snowy", "frozen", "tundra"]
    }
  ]
}
```

Selectors affect automatic generation only. A style explicitly supplied to `/village_walls build` is always used as requested.

## Build and Test

```bash
./gradlew verifyFast
./gradlew verifyFull
```

Coverage verification is enforced for core logic and config classes via JaCoCo in `verifyFast`. `verifyFull` adds the headless Forge GameTest pass.

## Community and support

For modpack and mod discussion, playtest feedback, and bug reports, join the [Better Content Discord](https://discord.gg/EkRnZbzqS9).

## Canonical identity

- Repository and release artifact: `village-walls`
- Mod ID and resource namespace: `village_walls`
- Java package: `com.bettercontent.villagewalls`
- Validation: `./gradlew verifyFull`

This normalization is a clean break. Worlds, configuration files, and integrations created for earlier identities are not migrated or aliased.
