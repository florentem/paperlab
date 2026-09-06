# Changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
No releases yet; below is what is already in `master`.

## Unreleased

### Added
- Port of Carpet's markup (`paperlab.text.Msg`): command, rule and logger output now
  matches the mod exactly — same headers, buttons and colours.
- Rule categories, `/carpet list <category>` and `/carpet list defaults`.
- `item`, `microtiming` and `movement` loggers in the spirit of Carpet TIS Addition.
- `microTiming` and `perWorldTick` rules.
- `servux:tweaks` channel — inventory preview for Tweakeroo.
- Server side of the Capture & Playback protocol: `/capture`, `/playback`, `/cplay`.
- Differential tests: our packets are decoded by the client mods' real code.
- Documentation in English and Russian: commands, rules, permissions, client mods,
  architecture.

### Fixed
- **TPS was computed wrongly.** It used `Bukkit.getTPS()[0]` — a one-minute rolling
  average, sluggish and different from what Carpet shows. Now the instantaneous
  `1000 / max(target mspt, actual mspt)`, accounting for `/tick` freeze and sprint.
- **The Capture & Playback channel was open to everyone.** A player with the mod could
  create and import compositions — that is, write files to the server — without ever
  being granted `paperlab.cplay`. The permission is now checked at the channel entry.
- `paperlab.cplay.admin` did not exist as a permission: it was never registered, so
  there was effectively no admin override. Replaced with `paperlab.cplay.manage`.
- `hardcodeTNTangle` options did not match the printed value, so a duplicate button was
  appended to the list.
- `/log` from the console answers `For players only` instead of vanishing from the
  command tree.

### Removed
- Third-party mod jars and copies of g4mespeed sources. g4mespeed is GPL-2.0, which is
  incompatible with our GPL-3.0. The differential tests remain, but the files are now
  placed in `libs/` by hand, and the directory is in `.gitignore`.

### Licensing
- `THIRD-PARTY.md`: full audit. Expression was taken from Carpet (MIT) — the markup and
  command text; the attribution notice has been added. The other protocols are
  reimplemented and contain none of their code.
