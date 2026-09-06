# Architecture

## Why two halves

Technically all of it could live in the core fork: the core can do everything a plugin
can, and not the other way round. So the split is a decision about cost of ownership,
not about capability.

**A patch is paid for on every Paper update.** Patches are applied to decompiled Mojang
sources. When 26.3 arrives and upstream has touched `ChunkMap` or `NaturalSpawner`, the
patch has to be carried across by hand, resolving conflicts in someone else's code. At
67 lines that is an evening. At 6600 it is a second job, and the project quietly dies at
the first update. The plugin, meanwhile, survives a Paper update untouched.

**Development cycle.** A change in the plugin rebuilds in seconds. A change in the core
means `applyPatches`, a full compile, `createPaperclipJar` — minutes. The ChunkDebug and
Servux protocols were recovered by dump, guess, check: hundreds of iterations. In the
core they would simply never have been finished.

**Blast radius.** A bug in the plugin is a line in the log; the plugin disables itself
and the server lives. A bug in the core is a dead server or a corrupted world. And note
what ended up in the plugin: parsing untrusted NBT from clients, parsing schematics,
writing blocks into the world. That is exactly the code most likely to have bugs, and it
belongs outside the core.

**Control runs.** The plugin works on stock Paper, so the same measurement can be taken
with our core and without it. If everything lived in the core there would be nothing to
compare against — any discrepancy would have to be written off as "well, we do run a
fork".

Hence the rule the code is split by: **only what a plugin physically cannot do goes into
the patch.**

---

## What is in the core, and why exactly that

Branch `lab`, four patches. The main one is `0035-Paper-Lab-hooks.patch` — 12 files,
67 added lines.

| Upstream file | Why it is touched |
|---|---|
| `ChunkMap` (4 points) | observer excluded from the mobcap census and backoff, does not widen the spawn area, loads no chunks |
| `RegionizedPlayerChunkLoader` | observer makes no chunk tick |
| `ActivationRange` | observer does not wake mobs (EAR) |
| `LivingEntity.canBeSeenByAnyone` | mobs do not target the observer |
| `NaturalSpawner` | observer does not consume the chunk budget + 4 spawn-trace marks |
| `PlayerList.remove` | clear observer mode on quit |
| `MinecraftServer.tickChildren` | bot `doTick()` in the connection phase |
| `Commands` | registers `/player` and the nodes added to vanilla `/tick` |
| `FillCommand`, `SetBlockCommand`, `CloneCommands` | the `fillUpdates` rule |
| `ServerDebugSubscribers` | debug subscriptions by permission, not by OP alone |

Plus three smaller patches: Capture & Playback hooks, hooks for the `item` / `movement` /
`microtiming` loggers, and per-world tick control.

Each hook sits where a plugin cannot reach:

* **observer** — the decisions are made inside Moonrise's chunk system and inside the
  spawn loop; there are no Bukkit events there;
* **spawn trace** — the marks sit inside the `NaturalSpawner` loop, which is opaque from
  outside: all you see from there is "a mob appeared" or "it did not";
* **bots** — `doTick()` has to run in the connection phase, while a plugin's scheduler
  runs at the start of `tickChildren`, before the level phase;
* **command rules** — edits to the vanilla commands themselves.

---

## What is in the plugin

```
paperlab/
├── command/     command registration, permissions, rules
├── rules/       rule engine and persistence
├── log/         tab-list subscriptions, loggers, the TAB bridge
│   ├── item/          item lifecycle
│   ├── movement/      movement calculation breakdown
│   └── microtiming/   redstone microtiming
├── counter/     hopper counters
├── ghost/       plugin side of observer mode
├── spawn/       spawn trace presentation
├── mobcap/      reading local mobcaps
├── chunkmap/    the ChunkDebug protocol
├── servux/      Servux channels: HUD, structures, schematics, NBT, tweaks
├── cplay/       the Capture & Playback protocol
├── core/        bridge to the core: detects whether it is present
└── text/        port of Carpet's markup
```

`core/CoreBridge` is the single place that asks "is our core here?". Everything else asks
it rather than checking for itself. That is what lets the plugin work honestly on stock
Paper and say so, instead of crashing or quietly pretending.

---

## Protocol lessons

Three protocols were recovered from mod sources, and each cost a separate investigation.
The conclusion is the same one every time, and it is worth writing down:

> **Verify the wire format against the client's code, not against the mod's own server
> implementation.** They diverge silently.

Specifically:

* **ChunkDebug.** `hello` is clientbound only — the client never sends it, and we were
  waiting for one. `start_watching` carries a **list** of dimensions, not one. The
  refresh channel is called `refresh` even though the class is `ChunkRefreshPayload`.
* **Servux and malilib.** Servux writes `int length + gzip`; malilib reads network NBT.
  It only became visible in a hex dump of a real Litematica packet:
  `ff ff ff ff 0f | 0a | ...` — that is `varint(-1)` followed by network NBT.
* **MiniHUD.** The client sends a logger name as the enum's `.name()` (`MOB_CAPS`) but
  parses the response by its serialized name (`mob_caps`). We read case-insensitively and
  answer in lowercase.
* **Litematica.** The schematic bit array is **spanning**: an entry crosses the boundary
  between two `long`s, unlike the vanilla palette container. The region origin comes from
  the subregion, not from `Regions.<name>.Position`. Rotating the main placement by 90°
  changes the subregion's mirror axis.

Hence the differential tests: they run our packets through the mod's real decoder. A test
that checks us against our own expectations proves we are consistent, not that we are
right — and it would have been green in all three cases above.

---

## Tick ordering

The plugin runs one shared tick, from which counters, the HUD, the chunk map and the
Servux channels are driven. There are deliberately no separate schedulers: several
independent timers on a lab that measures time produce drifting snapshots of what should
be one moment.

The HUD refreshes once per second, as in Carpet. The chunk map sends deltas, so a
stationary player generates no traffic at all.
