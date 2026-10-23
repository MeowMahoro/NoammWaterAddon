**English** | [简体中文](README.zh-CN.md)

# NoammWaterAddon

A standalone Fabric addon for [NoammAddons](https://github.com/Noamm9/NoammAddons) that solves the
**Water Board** puzzle from the board's current state, instead of relying on a static lookup table.

## Usage

1. Bind **Recalculate Shortcut** in `Dungeon → Water Board Addon`.
2. Turn the water lever off and wait for the board to drain.
3. Press the shortcut, then follow the tracer and the countdowns in game.

Levers before the water switch show `CLICK` right away; the timeline starts when you flip the switch.
A step disappears only after you click that lever, and clicking late never breaks the plan.

## Settings

| Setting | Default | Description |
|---|---|---|
| `Click Color` | green | tracer color for the next lever |
| `Next Click Color` | yellow | line to the following lever |
| `Recalculate Solver` | on | master switch |
| `Recalculate Shortcut` | unbound | triggers a recalculation |
| `Debug: Record Recalc Trace` | off | dumps a plan to `config/NoammWaterAddon/` |

While a plan is active, NoammAddons' own Water Board solver is temporarily disabled and restored
afterwards at its original value.

## License

MIT — see [LICENSE](LICENSE).

Not affiliated with NoammAddons, Dungeons Guide or Hypixel.
