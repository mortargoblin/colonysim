# Tiny Colony

A RimWorld-lite colony sim for Android, written in Kotlin. A handful of colonists
with needs (food, rest, mood) on a grid, a storyteller throwing random events at
them, and a map you can keep expanding forever.

## Playing

Grab the latest APK from the **Releases** tab and install it (you may need to allow
installs from unknown sources).

Pick a tab at the bottom (**Orders, Farm, Build, Furniture, Kitchen, Colony**),
then an option, then drag on the map.

- **Colonists are autonomous.** They eat, sleep, cook, take medicine and pick up
  whatever work you've ordered.
- **Resources:** raw food, meals, wood, stone, metal (mined from ore veins inside
  rock), and herbs.
- **Orders:** chop or mine, demolish (anything, refunding half the materials; it
  scraps unbuilt blueprints instantly), cancel.
- **Farm:** potatoes (fast), corn (slow, big harvests), healroot (herbs). Nothing
  grows in winter, and raw food spoils.
- **Build:** wood and stone walls, doors, wood and stone floors. Walls plus doors
  enclose **rooms**. Sleeping indoors beats sleeping outside, impressive rooms
  (floors, statues) are better still, and wolves and boars can't reach
  colonists indoors. Tap a tile to see and highlight its room.
- **Furniture:** beds, tables (nicer meals), statues (mood boost nearby).
- **Kitchen:** a campfire or a faster stove turns 3 raw food into 2 meals.
- **Colony:** expand the map, list colonists, log, help, new game.
- Spare beds and good mood attract wanderers, which is how the colony grows.
  Low mood leads to mental breaks, starvation kills, and herbs cure illness.
- Random events: wanderers, refugees, cargo pods, boar and wolf attacks, flu,
  blight, warm rain, cold snaps, traders, meteorites (with ore), auroras,
  birthdays, rats.

Controls: drag to pan, pinch to zoom, tap a colonist to inspect them. With a tool
selected, drag with one finger to paint orders and use two fingers to pan.
The game autosaves when you leave the app, and older saves are migrated.

## Project layout

- `core/` is the simulation as a pure Kotlin/JVM module (map, pawns, AI,
  pathfinding, rooms, buildings, events, save format). It's unit-tested and
  needs no Android SDK.
- `app/` is the Android front end: a single `Activity` plus a custom `View` that
  renders the grid and drives the game clock.

```
./gradlew :core:test            # simulation tests
./gradlew :app:assembleRelease  # APK (needs the Android SDK)
```

## CI / releases

`.github/workflows/android.yml` runs the tests and builds the APK on every push and
pull request, uploading it as a workflow artifact. On pushes to the default branch,
`v*` tags or a manual run, it also publishes a GitHub Release with the APK attached
(`v1.0.<run number>`, or the tag name).

By default the APK is signed with the runner's throwaway debug key, so each release
has a different signature. You'll need to uninstall before updating. For a
stable signature, add these repository secrets:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |
