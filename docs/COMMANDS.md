# Commands

Complete reference. Everything listed here works; anything not listed does not exist.

Notation: `<required>`, `[optional]`, `a|b` — choice. The "core" column marks commands
that need our Paper fork; on stock Paper they are absent or partial.

`/carpet` holds rules and our own tools. Everything that exists in Carpet keeps its
Carpet name at top level and is not duplicated under `/carpet`: diverging from the mod
breaks muscle memory, which costs more than a single entry point is worth.

| | Command | Core |
|---|---|---|
| rules | [`/carpet`](#carpet--rules) | partial |
| observer | [`/ghost`](#ghost--observer) | yes |
| spawn trace | [`/labspawn`](#labspawn--spawn-trace) | yes |
| chunk statuses | [`/labchunks`](#labchunks--chunk-statuses) | no |
| HUD subscriptions | [`/log`](#log--tab-list-subscriptions) | no |
| hopper counters | [`/counter`](#counter--hopper-counters) | no |
| bots | [`/player`](#player--bots) | **yes** |
| ticks | [`/tick`](#tick--ticks) | **yes** |
| spawnable spots | [`/perimeterinfo`](#perimeterinfo--spawnable-spots) | no |
| block state | [`/info block`](#info-block--block-state) | no |
| distance | [`/distance`](#distance) | no |
| signal recording | [`/capture`, `/playback`, `/cplay`](#capture--playback) | **yes** |

---

## `/carpet` — rules

Alias: `/lab`. Rules and our own tools only, like `/carpet` in the mod.

```
/carpet                                   changed rules, version, categories
/carpet list                              every rule
/carpet list <category>                   rules in a category
/carpet list defaults                     what applies after a restart
/carpet <rule>                            rule card
/carpet <rule> <value>                    set — until restart
/carpet setDefault <rule> <value>         set and remember
/carpet removeDefault <rule>              forget the saved value
/carpet perms                             permission tree and what you hold
```

Categories: `bot`, `command`, `creative`, `experimental`, `redstone`, `tick`, `tnt`.

Rules are documented separately in [RULES.md](RULES.md). The essential part:
**an ordinary set does not survive a restart**, and that is not caution. A forgotten
rule quietly spoils every later measurement — the numbers come out plausible and
comparable to nothing. Use `setDefault` to make a value stick.

Our tools are reachable both ways:

```
/carpet ghost   =  /ghost
/carpet spawn   =  /labspawn
/carpet chunks  =  /labchunks
/carpet cplay   =  /cplay
```

---

## `/ghost` — observer

The player stops affecting the simulation but keeps interacting with the world: blocks
place and break, containers open, buttons press.

```
/ghost              toggle for yourself
/ghost <player>     toggle for someone else       (paperlab.ghost.other)
```

What switches off: chunks stop ticking, mobcap is not taken, mob EAR is not woken, mobs
do not target the observer, other players do not see them.

**Turning it on is not instant.** Moonrise releases already-issued tickets lazily, so it
settles in about 30 seconds; turning it off takes seconds. The command says so.

Without our core the mode is partial — mobcap, EAR and mob attention remain. The command
says that too, in red, so a partial mode is not mistaken for the full one.

---

## `/labspawn` — spawn trace

Answers "why is the farm not spawning". The engine never says which step an attempt
stopped at, and the reasons differ in kind.

```
/labspawn                     summary
/labspawn on | off            start or stop collecting
/labspawn reset               zero the counters
/labspawn <category>          one mob category only
```

Categories: `monster`, `creature`, `ambient`, `axolotls`, `underground_water_creature`,
`water_creature`, `water_ambient`, `misc`.

```
spawn monster  cap 113307 · passes 4 · position 0 · plugin 0 · spawned 1
spawn ambient  cap 0 · passes 113311 · position 479805 · plugin 0 · spawned 0
```

Monsters are capped; ambient has cap headroom but fails on position. The `plugin` column
is the one cause that should not exist on clean Paper.

**The units differ, so the columns do not add up:** `cap` and `passes` count per
chunk × category pass, the rest count per attempted position.

Without our core only "spawned / cancelled" remains, with no breakdown.

---

## `/labchunks` — chunk statuses

```
/labchunks                    around yourself
/labchunks <player>           around someone else
/labchunks hello              enable the map for a ChunkDebug client
/labchunks hello <player>     same for someone else
```

Reports how many chunks sit in each stage: `INACCESSIBLE`, `FULL`, `BLOCK_TICKING`,
`ENTITY_TICKING`. The boundary between `BLOCK_TICKING` and `ENTITY_TICKING` is where
the mechanics this lab was built for live.

---

## `/log` — tab-list subscriptions

The value sits in the tab list and refreshes itself. Nothing is written to chat, by
design.

```
/log                          logger list with buttons
/log <logger>                 toggle
/log <logger> <option>        subscribe with an option
/log <logger> <option> full   detailed variant where one exists
/log <logger> clear           drop every subscription of that logger
/log clear                    drop everything
```

| Logger | Options | Shows |
|---|---|---|
| `tps` | — | TPS and MSPT, computed as in Carpet |
| `mobcaps` | `[name] [full]` | the **local** mobcap of a player or bot |
| `counter` | `<colour> [full]` | a hopper counter |
| `spawn` | `[category]` | the spawn trace |
| `item` | `despawn`, `die`, `create`, or comma-separated | item lifecycle |
| `microtiming` | `merged`, `all`, `unique` | redstone microtiming; needs the `microTiming` rule |
| `movement` | selector, e.g. `non_zero:@a[distance=..10]` | movement calculation breakdown |

**Difference from Carpet.** There a logger has one subscription; here it can have
several, one per target. That is what lets the HUD hold the mobcaps of several players
and bots at once. Repeating the command with the same target turns it off; with a
different flag, replaces it.

**Mobcaps matter most here.** With `per-player-mob-spawns`, Paper's spawner is limited
by each player's local cap, and the world total is only informative. So `/log mobcaps`
shows the real cap, and MiniHUD's world figures legitimately differ.

From the console the command answers `For players only`, as in Carpet.

---

## `/counter` — hopper counters

A hopper pointed into wool counts everything that passes through it. The wool colour is
the counter's name.

```
/counter                      every counter in this world
/counter <colour>             detail: items, per hour
/counter <colour> reset       reset one            (paperlab.counter.edit)
/counter reset                reset all            (paperlab.counter.edit)
/counter scan [radius]        find hoppers nearby  (paperlab.counter.edit)
```

Scan radius 1–64, default 16.

---

## `/player` — bots

Real `ServerPlayer` instances without a client. Registered by the **core**: it needs
vanilla argument types and a `doTick()` call in the connection phase — exactly where,
and in the same order relative to `tick()`, as for a live player. A plugin cannot do
that.

```
/player list
/player <name> spawn [at <x y z>] [facing <yaw pitch>] [in <dimension>] [<gamemode>]
/player <name> kill
/player <name> stop
/player <name> ghost
```

Anything not given explicitly is inherited from the caller: position, look, dimension,
game mode and flight.

**Actions**, each with a rhythm:

```
/player <name> attack     [once | continuous | interval <ticks>]
/player <name> use        [once | continuous | interval <ticks>]
/player <name> jump       [once | continuous | interval <ticks>]
/player <name> drop       [once | continuous | interval <ticks>]
/player <name> dropStack  [once | continuous | interval <ticks>]
/player <name> swapHands  [once | continuous | interval <ticks>]
```

**Movement, look, posture:**

```
/player <name> move forward|back|left|right|stop
/player <name> turn left|right|back|<yaw pitch>
/player <name> look north|south|east|west|up|down|<yaw pitch>
/player <name> sneak | unsneak | sprint | unsprint
/player <name> hotbar <1-9>
```

Movement holds until changed: it is a held key, not a step. The values go into `zza` and
`xxa` — the same fields the movement packet handler writes for a live player — and they
are written every tick, because the engine zeroes them as the tick proceeds.

**Vehicles and death:**

```
/player <name> mount [anything]     boats, minecarts, horses
/player <name> dismount
/player <name> respawn on|off
```

Auto-respawn brings the bot back a second later at its spawn point. Without it an
overnight run ends at the first death: a bot has no client to send the respawn request.

**Names.** A bot named after a live player takes their UUID, and that player can no
longer log in. The `fakePlayerNameSuffix` rule exists for this: the names diverge while
the skin is still fetched for the name **without** the suffix, so the bot looks like the
intended player and blocks nobody.

---

## `/tick` — ticks

The vanilla command, with two nodes added by the core. Everything else under `/tick` is
vanilla and behaves normally.

```
/tick toggle              freeze or release, on one key
/tick warp <time>         run forward
/tick warp stop           abort the warp
```

`toggle` exists for keybinds: `freeze` and `unfreeze` cannot share a key.

Availability of these nodes is governed by the `tickCommandCarpetfied` rule. Turning it
off does not remove them from the tree, it makes them unavailable — as in Carpet.

---

## `/perimeterinfo` — spawnable spots

How many places in the spawn sphere a mob could appear at all.

```
/perimeterinfo                around yourself
/perimeterinfo <x y z>        around a point
```

Answers "why is the farm under-producing": if thousands of eligible spots surround it,
the farm is competing with them for one cap.

**Difference from Carpet.** We walk **loaded** chunks only. Carpet reads blocks as it
goes and loads the world; we cannot, because loading changes the very thing being
measured. The number of skipped chunks is printed next to the result — otherwise the
figure reads as complete.

---

## `/info block` — block state

```
/info block                   the block underfoot
/info block <x y z>           a specific block
```

State and properties, light, scheduled block and fluid ticks, block entity data.

---

## `/distance`

```
/distance <x y z>                        from you to a point
/distance <x1 y1 z1> <x2 y2 z2>          between two points
```

Per axis, direct, flat, and in blocks.

---

## Capture & Playback

Server side of the [Capture & Playback](https://modrinth.com/mod/capture-playback)
protocol: recording block changes and redstone signals in a region, and playing them
back.

```
/capture start <name> <x1 y1 z1> <x2 y2 z2>     start recording a region
/capture stop <asset>                            stop
/capture list                                    what is recording now
```

```
/playback start <asset> [<delay in ticks> [<repeats>]]
/playback stop <asset>
/playback stopAll
/playback list
```

```
/cplay                    summary
/cplay status             core bridge state
/cplay assets             stored compositions
```

Compositions live in `plugins/PaperLab/cplay/assets/`, named by server-assigned UUIDs.
A client with the mod syncs on its own if the player holds `paperlab.cplay`.

Signal playback needs the core: the signal override lives in `SignalGetter` and the
block-event ordering in the `runBlockEvents` loop. Without the core the commands remain
but no signal reaches the world.
