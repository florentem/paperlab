# Права

[English version](../PERMISSIONS.md)

Все узлы регистрируются в Bukkit, поэтому LuckPerms подсказывает их сам, а
`/carpet perms` печатает дерево и отмечает, что есть у тебя.

По умолчанию всё на OP. Выдать целиком:

```
/lp group admin permission set paperlab.* true
```

**Дерево плоское.** `paperlab.*` — родитель всех узлов, но `paperlab.log.tps` не
включает `paperlab.log`. Так сделано намеренно: право на конкретный логгер не должно
незаметно открывать всю команду.

---

## Принцип: просмотр отдельно от вмешательства

Везде, где действие меняет мир или чужое состояние, у него своё право:

| Смотреть | Менять |
|---|---|
| `paperlab.counter` | `paperlab.counter.edit` |
| `paperlab.ghost` | `paperlab.ghost.other` |
| `paperlab.servux.entities` | `paperlab.servux.entities.players` |
| правила | `paperlab.rule.setdefault` |

---

## Команды

| Право | Что открывает |
|---|---|
| `paperlab.log` | команду `/log` целиком |
| `paperlab.log.tps` | подписку на TPS и MSPT |
| `paperlab.log.mobcaps` | локальные мобкапы, **в том числе чужие** |
| `paperlab.log.counter` | счётчики в таб-листе |
| `paperlab.log.spawn` | трассу спавна в таб-листе |
| `paperlab.log.item` | жизненный цикл предметов |
| `paperlab.log.microtiming` | микротайминги редстоуна |
| `paperlab.log.movement` | раскладку расчёта движения |
| `paperlab.counter` | смотреть счётчики воронок |
| `paperlab.counter.edit` | `scan` и `reset` |
| `paperlab.ghost` | режим наблюдателя у себя |
| `paperlab.ghost.other` | включать его другим |
| `paperlab.spawn` | трассу спавна и управление сбором |
| `paperlab.chunks` | сводку по статусам чанков |
| `paperlab.perimeterinfo` | подсчёт мест спавна |
| `paperlab.info` | `/info block` |
| `paperlab.distance` | `/distance` |
| `paperlab.player` | ботов |
| `paperlab.tick` | наши узлы `/tick` |
| `paperlab.rule.setdefault` | `setDefault` и `removeDefault` |
| `paperlab.rule.<имя правила>` | одно конкретное правило, имя строчными |

Права на правила выдаются по одному: `paperlab.rule.fillupdates`,
`paperlab.rule.microtiming`, `paperlab.rule.perworldtick` и так далее. Список —
в [RULES.md](RULES.md).

---

## Клиентские моды

Эти права открывают каналы, на которых сервер отвечает модам. Без права канал молча
закрыт: мод просто не увидит серверной части.

| Право | Что открывает | Риск |
|---|---|---|
| `paperlab.chunkmap` | карту чанков для ChunkDebug | видно, какие чанки загружены |
| `paperlab.servux.hud` | TPS, мобкапы, точку спавна для MiniHUD | низкий |
| `paperlab.servux.seed` | **сид мира** | сид позволяет найти любую структуру |
| `paperlab.servux.structures` | рамки структур | расположение структур рядом |
| `paperlab.servux.entities` | NBT сущностей и тайл-энтити в радиусе 128 | **чтение чужих сундуков** |
| `paperlab.servux.entities.players` | то же для других игроков | **чтение чужих инвентарей** |
| `paperlab.servux.tweaks` | предпросмотр инвентарей для Tweakeroo | то же, что выше |
| `paperlab.servux.litematics` | **вставку схематик** | **запись в мир** |
| `paperlab.debugdata` | отладочные рендеры MiniHUD | низкий |

Четыре из них стоит понимать буквально, а не выдавать «за компанию» с HUD:

* **`paperlab.servux.litematics`** — единственное право во всём наборе, которое
  **пишет в мир**. Игрок с ним и с Litematica ставит блоки схематикой.
* **`paperlab.servux.entities`** — видеть содержимое любого сундука в радиусе 128
  блоков. Это заметно больше, чем видеть TPS.
* **`paperlab.servux.entities.players`** — то же про инвентари игроков.
* **`paperlab.servux.seed`** — сид мира. На сурвайвале это обычно не раздают.

**`paperlab.debugdata` — про другое.** Отладочные рендеры MiniHUD (пути мобов,
обновления соседей, порядок редстоуна, POI) в 26.2 работают через ванильный протокол
подписок, а ваниль пускает к нему только операторов. Это право — альтернатива, чтобы
не выдавать OP ради оверлеев.

---

## Capture & Playback

| Право | Что открывает |
|---|---|
| `paperlab.cplay` | канал мода и `/cplay` |
| `paperlab.cplay.playback` | `/playback` |
| `paperlab.cplay.capture` | `/capture` |
| `paperlab.cplay.manage` | чужие композиции: чтение, дублирование, соавторы |

`paperlab.cplay` — не формальность. Без него канал закрыт целиком: игрок не получит
ни рукопожатия, ни списка композиций, и не сможет создавать или импортировать их.
Импорт — это запись файла на сервер, поэтому право проверяется на входе в канал,
а не только у команд.
