# Tiny Colony

A RimWorld-lite colony sim for Android, written in Kotlin. A handful of colonists
with needs (food, rest, mood) on a grid, a storyteller throwing random events at
them, and a map you can keep expanding forever.

## Playing

Grab the latest APK from the **Releases** tab and install it (you may need to allow
installs from unknown sources).

- **Colonists are autonomous.** They eat, sleep and pick up work you've ordered.
- **Chop/Mine** trees and rocks for wood and stone.
- **Farm**: plant fields. Ripe crops are harvested automatically. Nothing grows in
  winter and stored food slowly spoils, so plan ahead.
- **Bed** (6 wood): colonists who sleep on the ground get grumpy.
- **Wall** (3 stone), **Demolish**, **Cancel**.
- **Expand** claims more land when you have the materials.
- Spare beds and good mood attract wanderers, which is how the colony grows.
  Low mood leads to mental breaks, and starvation kills.
- Random events: wanderers, refugees, cargo pods, boar attacks, flu, blight, warm
  rain, cold snaps, traders, meteorites, auroras, birthdays and rats.

Controls: drag to pan, pinch to zoom, tap a colonist to inspect them. With a build
tool selected, drag with one finger to paint orders and use two fingers to pan.
The game autosaves when you leave the app.

## Project layout

- `core/` is the simulation as a pure Kotlin/JVM module (map, pawns, AI,
  pathfinding, events, save format). It's unit-tested and needs no Android SDK.
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
