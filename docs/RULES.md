# Rules

```
/carpet list                              every rule
/carpet <rule>                            card: description, categories, options
/carpet <rule> <value>                    set — until restart
/carpet setDefault <rule> <value>         set and remember
/carpet removeDefault <rule>              forget the saved value
/carpet list defaults                     what applies after a restart
```

## Why a set does not survive a restart

This is the main thing to understand about rules, and it is deliberate.

A rule changes how the world behaves. A rule left on does not break the server and gives
no sign of itself — the server simply starts lying: the numbers look plausible and are
comparable to nothing. A week later nobody remembers which rules were on when the
measurement two days ago was taken.

So an ordinary set lives until restart, and the lab always starts in a known state. To
keep a value you have to say so separately — `setDefault`, which writes to
`plugins/PaperLab/rules.conf`. Every set prints a `[Change permanently?]` button next to
it, so that is one click, not a punishment.

---

## The list

| Rule | Vanilla | Categories | Core |
|---|---|---|---|
| [`fakePlayerNameSuffix`](#fakeplayernamesuffix) | `none` | bot, creative | yes |
| [`fillUpdates`](#fillupdates) | `true` | creative, command | yes |
| [`hardcodeTNTangle`](#hardcodetntangle) | `-1.0` | tnt, creative | no |
| [`microTiming`](#microtiming) | `false` | redstone, experimental | partial |
| [`perWorldTick`](#perworldtick) | `false` | tick, experimental | yes |
| [`tickCommandCarpetfied`](#tickcommandcarpetfied) | `true` | command, experimental | yes |

A rule that needs the core shows as `(needs our core)` on stock Paper and refuses to be
set: nothing there would read it, and quietly accepting a value while doing nothing
would be the worst outcome.

---

### `fakePlayerNameSuffix`

Suffix appended to bot names. Options: `none`, `_bot`, `_fake`, `_afk`, or your own up
to 8 characters.

A bot named after a live player takes their UUID, and that player can no longer log in.
A suffix makes the names diverge while **the skin is still fetched for the name without
it** — the bot looks like the intended player and blocks nobody.

### `fillUpdates`

Whether `/fill`, `/setblock` and `/clone` cause neighbour updates.

`false` places blocks quietly: observers do not fire, torches and repeaters do not pop
off, redstone does not start. Needed to assemble a contraption from a template and turn
it on once, instead of watching it start itself midway through the fill.

### `hardcodeTNTangle`

Fixed horizontal launch angle for primed TNT, in radians. `-1` is vanilla random
behaviour; otherwise 0 to 2π.

Vanilla TNT gets a random angle, so the same cannon fires slightly differently every
time and runs cannot be compared. With a fixed angle the contraption behaves identically.

No core needed: the rule is applied by an entity-spawn listener — the constructor has
already run, but nothing has read the velocity before the first tick.

### `microTiming`

Enables redstone microtiming collection for `/log microtiming`.

The logger watches components marked with wool or dye and records the order in which
they fire within a tick. Same idea as Carpet TIS Addition.

It is off by default not out of caution but because collection costs time: on a lab that
measures time, a collector left on by accident is the worst thing that can happen.

On stock Paper the rule still works, but coarsely: without core hooks the logger only
sees what reaches Bukkit events — not the in-tick order, not the call depth.

### `perWorldTick`

Independent tick rate, freeze and sprint per world.

`true` gives each world its own `ServerLevelTickRateManager`, so `/tick freeze` in the
nether does not stop the overworld. `false` keeps every world synchronised with the
overworld, as in vanilla.

Useful when a long run is going in one world and something has to be built in another.

### `tickCommandCarpetfied`

Whether our `/tick toggle` and `/tick warp` nodes are available.

Turning it off does not remove them from the command tree, it makes them unavailable —
the same way Carpet does it. The other `/tick` branches are vanilla and ignore the rule.
