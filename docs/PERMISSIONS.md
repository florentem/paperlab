# Permissions

Every node is registered with Bukkit, so LuckPerms suggests them on its own and
`/carpet perms` prints the tree and marks what you hold.

Everything defaults to OP. To grant it all:

```
/lp group admin permission set paperlab.* true
```

**The tree is flat.** `paperlab.*` is the parent of every node, but `paperlab.log.tps`
does not imply `paperlab.log`. That is deliberate: a permission for one logger should
not quietly open the whole command.

---

## Viewing is separate from changing

Wherever an action changes the world or someone else's state, it has its own node:

| View | Change |
|---|---|
| `paperlab.counter` | `paperlab.counter.edit` |
| `paperlab.ghost` | `paperlab.ghost.other` |
| `paperlab.servux.entities` | `paperlab.servux.entities.players` |
| rules | `paperlab.rule.setdefault` |

---

## Commands

| Permission | Opens |
|---|---|
| `paperlab.log` | the `/log` command as a whole |
| `paperlab.log.tps` | TPS and MSPT subscription |
| `paperlab.log.mobcaps` | local mobcaps, **including other players'** |
| `paperlab.log.counter` | counters in the tab list |
| `paperlab.log.spawn` | spawn trace in the tab list |
| `paperlab.log.item` | item lifecycle |
| `paperlab.log.microtiming` | redstone microtiming |
| `paperlab.log.movement` | movement calculation breakdown |
| `paperlab.counter` | viewing hopper counters |
| `paperlab.counter.edit` | `scan` and `reset` |
| `paperlab.ghost` | observer mode for yourself |
| `paperlab.ghost.other` | enabling it for others |
| `paperlab.spawn` | spawn trace and collection control |
| `paperlab.chunks` | chunk status summary |
| `paperlab.perimeterinfo` | spawnable spot counting |
| `paperlab.info` | `/info block` |
| `paperlab.distance` | `/distance` |
| `paperlab.player` | bots |
| `paperlab.tick` | our `/tick` nodes |
| `paperlab.rule.setdefault` | `setDefault` and `removeDefault` |
| `paperlab.rule.<rule name>` | one specific rule, name lowercased |

Rule permissions are granted one at a time: `paperlab.rule.fillupdates`,
`paperlab.rule.microtiming`, `paperlab.rule.perworldtick`, and so on. The list is in
[RULES.md](RULES.md).

---

## Client mods

These open the channels the server answers mods on. Without the permission a channel is
silently closed: the mod simply does not see a server side.

| Permission | Opens | Exposure |
|---|---|---|
| `paperlab.chunkmap` | the ChunkDebug chunk map | which chunks are loaded |
| `paperlab.servux.hud` | TPS, mobcaps, spawn point for MiniHUD | low |
| `paperlab.servux.seed` | **the world seed** | a seed locates any structure |
| `paperlab.servux.structures` | structure bounding boxes | nearby structure locations |
| `paperlab.servux.entities` | entity and block-entity NBT within 128 blocks | **reading other people's chests** |
| `paperlab.servux.entities.players` | the same for other players | **reading other people's inventories** |
| `paperlab.servux.tweaks` | inventory preview for Tweakeroo | as above |
| `paperlab.servux.litematics` | **schematic pasting** | **writes to the world** |
| `paperlab.debugdata` | MiniHUD debug renderers | low |

Four of these should be read literally rather than handed out alongside the HUD:

* **`paperlab.servux.litematics`** is the only node in the whole set that **writes to
  the world**. A player holding it, with Litematica, places blocks from a schematic.
* **`paperlab.servux.entities`** means seeing the contents of any chest within 128
  blocks. That is a good deal more than seeing TPS.
* **`paperlab.servux.entities.players`** is the same for player inventories.
* **`paperlab.servux.seed`** is the world seed. On a survival server that is normally
  not given out.

**`paperlab.debugdata` is about something else.** MiniHUD's debug renderers (mob paths,
neighbour updates, redstone order, POI) go through the vanilla subscription protocol in
26.2, and vanilla admits only operators. This node is the alternative, so nobody has to
be given OP for overlays.

---

## Capture & Playback

| Permission | Opens |
|---|---|
| `paperlab.cplay` | the mod's channel and `/cplay` |
| `paperlab.cplay.playback` | `/playback` |
| `paperlab.cplay.capture` | `/capture` |
| `paperlab.cplay.manage` | other people's compositions: read, duplicate, collaborators |

`paperlab.cplay` is not a formality. Without it the channel is closed entirely: the
player gets no handshake, no composition list, and cannot create or import anything.
Importing writes a file to the server, which is why the permission is checked at the
channel entry and not only on the commands.
