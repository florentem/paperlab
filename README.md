# PaperLab

Technical-server tooling for Minecraft farm research, on **Paper 26.2**.

What Carpet is for Fabric, adapted to what makes Paper different. Chiefly
**per-player mobcaps**: with `per-player-mob-spawns`, Paper caps every player
separately instead of the world as a whole. Neither vanilla nor the Fabric mods have
that, which is why no tool showed it until now.

Two halves. This is the **plugin**; the [**core fork**](https://github.com/oCreker/paperlab-core)
lives next door and carries a 67-line patch — only what a plugin physically cannot do.
The plugin also runs on stock Paper, which is what control runs need, though some
tools there are partial or absent.

[Русская версия](README.ru.md)

---

## Documentation

| | |
|---|---|
| [**Commands**](docs/COMMANDS.md) | full reference: syntax, arguments, behaviour |
| [**Rules**](docs/RULES.md) | `/carpet`, six rules, and why they do not survive a restart |
| [**Permissions**](docs/PERMISSIONS.md) | the whole tree, and the four nodes to read literally |
| [**Client mods**](docs/CLIENT-MODS.md) | ChunkDebug, MiniHUD, Litematica, Tweakeroo, Capture & Playback |
| [**Architecture**](docs/ARCHITECTURE.md) | why two halves, what is in the core, protocol lessons |
| [**Test plan**](docs/TESTING.md) | the release pass: what is verified, what is not, and the ranked risks |
| [**Third party**](THIRD-PARTY.md) | what is borrowed and under which terms |

---

## The three things this exists for

### Observer — zero ticking chunks

```
/ghost
```

The player stops affecting the simulation but keeps interacting with the world: blocks
place and break, containers open. Loads no chunks, takes no mobcap, wakes no mobs
through EAR, is not noticed by them, is invisible to others.

**Measured:** a player at `simulation-distance=5` holds 121 ticking chunks (11×11);
the observer holds **zero**. The measurement was taken with a live player present, not
in an empty world — the world total came to exactly 121, so the observer contributed
nothing against real load.

A personal simulation distance alone is not enough. Moonrise's `tickMap` at radius 0
still covers the chunk under the player, and entities there wake up the moment you walk
in. So `tickingQueue` is cleared — the single point where a chunk enters the TICK stage.

### Spawn trace — where the attempt actually dies

```
/labspawn
```

```
spawn monster  cap 113307 · passes 4 · position 0 · plugin 0 · spawned 1
spawn ambient  cap 0 · passes 113311 · position 479805 · plugin 0 · spawned 0
```

The engine never says which step a spawn attempt failed at, and the reasons differ in
kind. Here it reads at a glance: monsters are capped, while ambient has cap headroom
and fails on position. The `plugin` column is the one cause that should not exist on
clean Paper.

The units differ, so the columns do not add up: `cap` and `passes` count per
chunk × category pass, the rest count per attempted position.

### Bots — real players without a client

```
/player Steve spawn
/player Steve use continuous
```

They inherit position, look, dimension, game mode and flight from the caller. With
skins. Actions on a `once | continuous | interval N` rhythm, movement, vehicles,
auto-respawn after death.

`doTick()` is called in the connection phase — exactly where, and in the same order
relative to `tick()`, as for a live player. A plugin cannot reach that: its scheduler
runs at the start of `tickChildren`, before the level phase. Hence the core fork.

---

## Everything else, briefly

**Tab-list subscriptions** instead of chat spam — `/log tps`, `/log mobcaps <name>`,
`/log counter <colour>`, `/log spawn`, plus `item`, `microtiming` and `movement`
loggers in the spirit of Carpet TIS Addition. If TAB is installed, the footer is left
to it and our subscriptions coexist with its layout.

**Hopper counters** — `/counter`. Point a hopper into wool; the wool colour names the
counter.

**Carpet tools under their own names** — `/perimeterinfo`, `/info block`, `/distance`.
`perimeterinfo` has one deliberate difference: we walk loaded chunks only and report
how many were skipped. Carpet loads the world as it goes; we cannot, because loading
changes the very thing being measured.

**Client mods** get their channels: the ChunkDebug chunk map, MiniHUD overlays,
Litematica pasting without chat spam, Capture & Playback signal recording. See
[CLIENT-MODS.md](docs/CLIENT-MODS.md).

---

## Installing

Needs **Java 25** and the server jar from
[paperlab-core](https://github.com/oCreker/paperlab-core).

1. Drop `PaperLab-*.jar` into `plugins/`.
2. Start the server.
3. Grant permissions — everything defaults to OP:

```
/lp group admin permission set paperlab.* true
```

Permissions are registered with Bukkit, so LuckPerms suggests them on its own.
`/carpet perms` shows the tree and marks what you currently hold.

---

## Building

Needs **JDK 25** and the core fork built alongside. The plugin compiles against the
server jar rather than `paper-api`: the tools need server internals
(`ServerPlayer.mobCounts`, `ChunkMap.getMobCountNear`, Moonrise's `ChunkHolderManager`)
that the public API does not expose by design.

```bash
git clone https://github.com/oCreker/paperlab-core.git
git clone https://github.com/oCreker/paperlab.git
```

```bash
cd paperlab-core && ./gradlew applyPatches && ./gradlew createPaperclipJar
```

```bash
cd ../paperlab && ./gradlew jar
```

The order matters and the directory names cannot change: the core path is set in
`build.gradle.kts` as `../paperlab-core`. In the core, `applyPatches` and
`createPaperclipJar` must be **two separate Gradle invocations** — in one they fight
over the source tree.

Outputs:

```
paperlab-core/paper-server/build/libs/paper-paperclip-26.2.local-SNAPSHOT.jar
paperlab/build/libs/PaperLab-1.0.5.jar
```

Paper 26.2 runs on mojang mappings, so a plugin built against the server jar loads
without remapping. The flip side: it is tied to **this** core build and may not load
on a different 26.2 build. That is expected, not a defect.

### Tests

```bash
./gradlew test
```

58 tests. Another 25 are differential: they check our encoding against the **real code
of the client mods** rather than against our own expectations — we build the packet,
their decoder reads it. That is the only way to catch a mismatch that otherwise surfaces
only in front of a player. It is how we found that malilib reads network NBT while
Servux writes gzip, with every one of our own tests green at the time.

Third-party mods are not kept in the repository: each has its own licence, and
g4mespeed's is GPL-2.0, incompatible with our GPL-3.0. To run those tests, supply the
files yourself:

```
libs/malilib-fabric-26.2-*.jar
libs/minihud-fabric-26.2-*.jar
libs/src/com/g4mesoft/util/GS{Encode,Decode}Buffer.java
libs/src/com/g4mesoft/captureplayback/common/asset/*.java
```

Without them those tests are excluded from the build. `libs/` is in `.gitignore`
entirely — third-party code must not reach our commits even by accident.

---

## What is lost without our core

| | With our core | On stock Paper |
|---|---|---|
| `/ghost` | full: zero ticking chunks | partial: takes mobcap, wakes mobs, is noticed |
| spawn trace | cap / position / plugin / success | only "spawned / cancelled" |
| `/player` bots | yes | no |
| `/tick toggle`, `/tick warp` | yes | no |
| signal playback | yes | commands exist, signals never reach the world |
| `/log microtiming` | in-tick order, call depth | coarse, from Bukkit events |
| core rules | yes | marked `(needs our core)` |
| everything else | yes | yes |

Running on stock Paper is not a fallback but an instrument: the same measurement can be
run with and without the core, and the difference is the answer.

---

## Licence

**GPL-3.0** — [LICENSE.md](LICENSE.md). The plugin compiles against Paper server
internals, and those inherited GPL from Spigot, Bukkit and CraftBukkit.

What is borrowed and on what terms: [THIRD-PARTY.md](THIRD-PARTY.md). In short, the
command text and markup come from **Carpet Mod** (MIT, © gnembon), deliberately — someone
arriving from Carpet should not have to guess what a different shade means here. The
Servux, MiniHUD, Litematica, ChunkDebug and Capture & Playback protocols are
reimplemented from the wire format; none of their code is included.
