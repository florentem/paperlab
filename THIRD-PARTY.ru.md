# Чужая работа в PaperLab

[English version](THIRD-PARTY.md)

Здесь перечислено всё, что мы взяли у других проектов, и на каких условиях.
Список полный: если проекта тут нет, значит из него не взято ничего.

Разделение простое и оно важнее, чем кажется:

* **Формат** — имена каналов, номера пакетов, порядок полей, имена тегов NBT. Это
  интерфейс: без совпадения байт в байт клиентский мод просто не заработает. Такие
  совпадения неизбежны и лицензией исходников не покрываются — мы писали свой код,
  глядя на формат, а не копируя реализацию.
* **Выражение** — конкретные строки, тексты сообщений, структура алгоритма. Вот это
  и есть чужая работа, и она требует соблюдения условий.

---

## Выражение: откуда взят код

### Carpet Mod — MIT

Автор: **gnembon**, <https://github.com/gnembon/fabric-carpet>

Взято:

* мини-язык разметки `carpet.utils.Messenger` — таблица кодов стиля, алгоритм
  разбора поля, тепловая шкала, цвета категорий мобов. Портирован на Adventure
  в [`paperlab.text.Msg`](src/main/java/paperlab/text/Msg.java);
* тексты и раскладка команд: `/carpet` и его экраны правил
  ([`RuleCommands`](src/main/java/paperlab/command/RuleCommands.java)), `/log`
  ([`LabLogCommand`](src/main/java/paperlab/command/LabLogCommand.java)), краткая
  строка счётчика ([`LabCounters`](src/main/java/paperlab/counter/LabCounters.java));
* грамматика команды `/player` — имена и порядок аргументов (в репозитории ядра).

Это сделано намеренно и является целью, а не побочным эффектом: человек, пришедший
с Carpet, не должен гадать, что здесь значит другой оттенок или другой отступ.

MIT требует сохранять уведомление об авторстве. Оно приведено полностью:

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

MIT совместима с GPL-3.0: код можно включать в наш проект, сохраняя это уведомление.

---

## Формат: что реализовано заново по протоколу

Ни строки чужого кода. Совпадают только имена каналов, номера пакетов и имена полей —
без этого клиент не поймёт сервер.

| Проект | Лицензия | Что от него в нашем коде |
|---|---|---|
| [Servux](https://github.com/sakura-ryoko/servux) | LGPL-3.0 | каналы `servux:hud_metadata`, `structures`, `litematics`, `entity_data` |
| [malilib](https://github.com/maruohon/malilib) | LGPL-3.0 | обрамление пакетов, разрезание больших ответов |
| [MiniHUD](https://github.com/maruohon/minihud) | LGPL-3.0 | имена логгеров HUD, поля мобкапов и погоды |
| [Litematica](https://github.com/maruohon/litematica) | LGPL-3.0 | формат схематик: теги NBT, палитра, битмассив, подрегионы |
| [ChunkDebug](https://github.com/senseiwells/ChunkDebug) | MIT | каналы и формат карты чанков |
| [Capture & Playback / G4mespeed](https://github.com/G4me4u/g4mespeed) | **GPL-2.0** | канал `minecraft:mod/g4mespeed`, склейка id пакета, формат BlockPos |

**Про GPL-2.0 отдельно.** GPL-2.0 без оговорки «or later» несовместима с нашей
GPL-3.0: смешивать код нельзя. Поэтому в `paperlab.cplay` нет ни строки из g4mespeed —
только протокол. Совпадают шесть строк цикла varint и три константы битовых масок,
но это единственный способ записать этот формат, и тот же цикл дословно лежит в самом
Minecraft.

Раньше в репозитории лежали шесть файлов g4mespeed, скопированных дословно, — их
использовали дифференциальные тесты. Они удалены, история переписана. Тесты остались,
но чужие файлы теперь кладутся в `libs/` вручную, см. README.

---

## Идеи без кода

Взята постановка задачи, не реализация. Проверено: общих строк — ноль.

| Проект | Лицензия | Что переняли |
|---|---|---|
| [Carpet TIS Addition](https://github.com/TISUnion/Carpet-TIS-Addition) | LGPL-3.0 | сама мысль логгеров `microtiming`, `item`, `movement` и разметки блоков красителем |
| [Redstone Multimeter](https://github.com/SpaceWalkerRS/redstone-multimeter-fabric) | MIT | пока ничего: RSMM не реализован |

---

## Чего мы не трогали

В подборке исходников, по которым велось исследование, есть проекты **без файла
лицензии**: два серверных счётчика воронок, mcbenchmark и один из форков Servux.
Без лицензии код использовать нельзя вообще — по умолчанию все права сохранены за
автором. Мы из них ничего не брали, и это проверено сравнением: общих строк ноль
(четыре совпадения с форком Servux — это имя канала и имена полей протокола, те же,
что и у оригинального Servux).

---

## Наша лицензия

Сам PaperLab под **GPL-3.0** — см. [LICENSE.md](LICENSE.md). Она унаследована от
серверных внутренностей Paper, против которых мы компилируемся, а те получили её от
Spigot, Bukkit и CraftBukkit.
