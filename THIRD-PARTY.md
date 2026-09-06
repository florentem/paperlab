# Third-party work in PaperLab

Everything taken from other projects, and the terms it comes under. The list is
complete: if a project is not here, nothing was taken from it.

The distinction below matters more than it looks:

* **Format** — channel names, packet ids, field order, NBT tag names. This is an
  interface: without a byte-for-byte match the client mod simply does not work. Such
  overlap is unavoidable and is not covered by the source licence — we wrote our own code
  against the format, not by copying an implementation.
* **Expression** — specific strings, message text, the shape of an algorithm. That is
  someone else's work, and its terms apply.

---

## Expression: where code came from

### Carpet Mod — MIT

Author: **gnembon**, <https://github.com/gnembon/fabric-carpet>

Taken:

* the `carpet.utils.Messenger` markup mini-language — style code table, field parsing,
  the heatmap scale, mob category colours. Ported to Adventure in
  [`paperlab.text.Msg`](src/main/java/paperlab/text/Msg.java);
* command text and layout: `/carpet` and its rule screens
  ([`RuleCommands`](src/main/java/paperlab/command/RuleCommands.java)), `/log`
  ([`LabLogCommand`](src/main/java/paperlab/command/LabLogCommand.java)), the counter
  summary line ([`LabCounters`](src/main/java/paperlab/counter/LabCounters.java));
* the `/player` command grammar — argument names and order (in the core repository).

This is deliberate and is the goal, not a side effect: someone arriving from Carpet
should not have to guess what a different shade or a different indent means here.

MIT requires the copyright notice to be preserved. In full:

```
MIT License

Copyright (c) 2020 gnembon

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR OTHER DEALINGS IN THE SOFTWARE.
```

MIT is compatible with GPL-3.0: the code may be included provided this notice stays.

---

## Format: reimplemented from the protocol

Not a line of anyone else's code. Only channel names, packet ids and field names
coincide — without that the client cannot understand the server.

| Project | Licence | What of it is in our code |
|---|---|---|
| [Servux](https://github.com/sakura-ryoko/servux) | LGPL-3.0 | the `servux:hud_metadata`, `structures`, `litematics`, `entity_data` channels |
| [malilib](https://github.com/maruohon/malilib) | LGPL-3.0 | packet framing, splitting of large responses |
| [MiniHUD](https://github.com/maruohon/minihud) | LGPL-3.0 | HUD logger names, mobcap and weather fields |
| [Litematica](https://github.com/maruohon/litematica) | LGPL-3.0 | schematic format: NBT tags, palette, bit array, subregions |
| [ChunkDebug](https://github.com/senseiwells/ChunkDebug) | MIT | chunk map channels and format |
| [Capture & Playback / G4mespeed](https://github.com/G4me4u/g4mespeed) | **GPL-2.0** | the `minecraft:mod/g4mespeed` channel, packet id composition, BlockPos layout |

**On GPL-2.0 specifically.** GPL-2.0 without an "or later" clause is incompatible with
our GPL-3.0: the code cannot be mixed. So `paperlab.cplay` contains no line from
g4mespeed — only the protocol. Six lines of the varint loop and three bit-mask constants
coincide, but that is the only way to write this format, and the same loop sits verbatim
in Minecraft itself.

The repository used to contain six files copied verbatim from g4mespeed, used by the
differential tests. They have been removed and the history rewritten. The tests remain,
but third-party files are now placed in `libs/` by hand — see the README.

---

## Ideas without code

The problem statement was adopted, not the implementation. Verified: zero shared strings.

| Project | Licence | What was adopted |
|---|---|---|
| [Carpet TIS Addition](https://github.com/TISUnion/Carpet-TIS-Addition) | LGPL-3.0 | the idea of the `microtiming`, `item` and `movement` loggers and of marking blocks with dye |
| [Redstone Multimeter](https://github.com/SpaceWalkerRS/redstone-multimeter-fabric) | MIT | nothing yet: RSMM is not implemented |

---

## What was not touched

The research source collection includes projects **with no licence file**: two
server-side hopper counters, mcbenchmark, and one Servux fork. Without a licence, code
cannot be used at all — by default all rights stay with the author. Nothing was taken
from them, and that was verified by comparison: zero shared strings (the four matches
against the Servux fork are a channel name and protocol field names, the same ones the
original Servux uses).

---

## Our licence

PaperLab itself is **GPL-3.0** — see [LICENSE.md](LICENSE.md). It is inherited from the
Paper server internals we compile against, which in turn got it from Spigot, Bukkit and
CraftBukkit.
