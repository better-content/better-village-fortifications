# Village Walls (Forge 1.20.1)

Village Walls is a server-side mod that detects a village footprint from generated village structures or village POIs, expands it by a configurable buffer, and builds enclosing walls from data-driven styles. It then finds the flattest wall segments and places adjacent double-door gates there.

## Features

- Village perimeter tracing with configurable buffer radius.
- Data-driven wall styles loaded from datapack JSON under `data/villagewalls/wall_styles`.
- Automatic wall generation when village structure chunks load on the server.
- Automatic flatness scoring of wall segments.
- One primary adjacent double-door gate on the flattest segment.
- Additional gates on other equally flat segments (up to a configured cap).
- Four default styles included:
  - `villagewalls:cobble_spruce_thin`
  - `villagewalls:cobble_spruce_tall_walkable`
  - `villagewalls:stonebrick_thin`
  - `villagewalls:deepslate_tall_walkable`

## Command

Walls are built automatically with the default style when village structure chunks load. Use the command for manual builds, alternate styles, or custom radii:

- `/villagewalls styles`
- `/villagewalls build <style> <searchRadius> <buffer> <maxDoors>`

Example:

```text
/villagewalls build villagewalls:cobble_spruce_tall_walkable 128 6 8
```

## Wall Style JSON Schema

```json
{
  "primary_block": "minecraft:cobblestone",
  "accent_block": "minecraft:spruce_log",
  "accent_every": 4,
  "thickness": 1,
  "height": 3,
  "walkable": false
}
```

Rules:
- `accent_every >= 1`
- `thickness >= 1`
- `height >= 2`

## Build and Test

```bash
./gradlew verifyFast
./gradlew verifyFull
```

Coverage verification is enforced for core logic and config classes via JaCoCo in `verifyFast`. `verifyFull` adds the headless Forge GameTest pass.
