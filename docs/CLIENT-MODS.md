# Client mods

The server works fully with a **vanilla client**. Nothing has to be installed — commands,
counters, bots and the tab list all work as they are.

If a player runs Fabric with mods, the server answers them on their own channels, and
things a vanilla client cannot do appear: the chunk map, MiniHUD overlays, schematic
pasting without chat spam.

**The server sides are written by us from scratch.** The real Servux is a Fabric mod and
does not run on Paper; the client does not care who answers on a channel.

---

## What each mod adds

| Mod | Channels | What appears | Permission |
|---|---|---|---|
| [ChunkDebug](https://modrinth.com/mod/chunkdebug) | `chunkdebug:*` | chunk status and ticket map, F6 | `paperlab.chunkmap` |
| [MiniHUD](https://modrinth.com/mod/minihud) | `servux:hud_metadata`, `servux:structures`, `servux:entity_data` | TPS, mobcaps, structure boxes, NBT under the crosshair | `paperlab.servux.*` |
| [Litematica](https://modrinth.com/mod/litematica) | `servux:litematics` | **server-side schematic pasting** | `paperlab.servux.litematics` |
| [Tweakeroo](https://modrinth.com/mod/tweakeroo) | `servux:tweaks` | inventory preview at range | `paperlab.servux.tweaks` |
| [Capture & Playback](https://modrinth.com/mod/capture-playback) | `minecraft:mod/g4mespeed` | recording and replaying redstone signals | `paperlab.cplay` |

MiniHUD, Litematica and Tweakeroo need [malilib](https://modrinth.com/mod/malilib).

---

## Client-side setup

### Litematica — schematic pasting

Probably the main reason to install mods at all: the server places the blocks itself,
instead of thousands of `/setblock` lines in chat.

1. Enable **`entityDataSync`** in Litematica's settings. The name is misleading: it is
   the master switch for the channel that pasting also rides on. It is off by default,
   and it is the first thing everyone trips over.
2. **`pasteUsingServux`** is on by default; worth checking.
3. Pasting requires creative mode and `paperlab.servux.litematics`.

Rotations, mirroring, subregions, replace mode and block entity data are supported.

**Blocks are placed without side effects.** Litematica asks for flags `0x12`, which is
not enough: `onPlace` still runs, and for redstone dust that is `updatePowerStrength` —
it recomputes the signal and overwrites the state it was given. A schematic with powered
dust came out unpowered, and the contraption started itself. So we add
`UPDATE_SKIP_ON_PLACE`.

### MiniHUD

Enable `HUD data sync` in the settings; the data arrives on its own after that.

**The mobcaps MiniHUD shows are world-wide.** With `per-player-mob-spawns`, Paper's
spawner is limited by each player's local cap, and the world total is only informative.
The real cap is what `/log mobcaps` shows, and the two legitimately differ. This is not
desync and not a bug — they are different quantities.

**Debug renderers** (mob paths, neighbour updates, redstone order, POI, brain) do not
need Servux at all: in 26.2 they go through vanilla's own subscription system. Vanilla
admits only operators; the `paperlab.debugdata` permission is the alternative, so nobody
has to be given OP for overlays.

### ChunkDebug

Nothing to configure; the map opens on F6.

The server sends **deltas** rather than full snapshots: standing still produces no
traffic at all, while flying sends a few hundred changed chunks plus unload messages.

### Capture & Playback

The client syncs on its own if the player holds `paperlab.cplay`. Compositions are stored
server-side, in `plugins/PaperLab/cplay/`.

Without the permission the channel is closed entirely and the mod simply does not see a
server side. That is deliberate: importing a composition writes a file to the server.

---

## When something does not work

The first thing to check is the **permission**. Channels close silently: the mod gets no
answer and behaves as if the server were ordinary. That is the right behaviour — there is
no reason to advertise a channel to everyone — but it does make diagnosis harder.

```
/carpet perms
```

prints the tree and marks what you hold.

Second: whether **sync is enabled in the mod itself** — `entityDataSync` for Litematica,
`HUD data sync` for MiniHUD.

For protocol work there is a verbose server log:

```
-Dpaperlab.servux.debug=true
```

It prints incoming and outgoing packet types and, while parsing, a hex dump. That is what
caught the framing mismatches: malilib reads network NBT while Servux writes
`int length + gzip`, and it only became visible in a dump of a real Litematica packet.
