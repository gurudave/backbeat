# Backbeat ✧

A metronome for an Android phone. No adverts, no accounts, no tracking. It is the third
app in the family, after Djungleskwidgets (in `gurudave/reimagined-potato`) and Funhouse (in
`gurudave/psychic-fishstick`), with the same arcade-and-VCR look and the same six channels.

The app has no internet permission, so it *cannot* show ads or send anything anywhere. CI
fails the build if network access ever creeps in.

## What it does

- **20 to 300 BPM**, set on a **tuning dial** (drag it like the scale of an old radio), with
  **− and +** keys that speed up when held, or by **TAP**ping along with the music
- **13 time signatures**, from 1/4 to 12/8. The compound ones (6/8, 9/8, 12/8) count in
  dotted groups, and 5/8 and 7/8 come pre-grouped (3+2 and 2+2+3)
- **Accents you can see:** a light for every beat, as tall as it is loud. Tap one to step it
  through **accent, beat, ghost and rest**
- **Between the beats:** quavers, triplets, semiquavers or **swing**
- **Five sounds**, all synthesised on the phone: an arcade **blip**, a quartz **beep**, a
  **wood** block, a drum machine's **cowbell**, and **drums** (kick on the accents, snare on the
  beats, hi-hat in between). Tapping one while stopped plays a sample
- **A pixel ball** that hops along the beat lights and lands on each one *as it sounds*, so
  you see the beat coming rather than only see it go
- **Practice aids:**
  - **tempo ramp**: up (or down) a few BPM every few bars, until a target. A pixel firework
    goes off when you get there
  - **silent bars**: play two bars, hush for two, and see whether you are still on the beat
    when it comes back. The lights and ball carry on through the silence
  - **stop after** 5 to 60 minutes, always at the end of a bar
- **Tapes:** save a set-up as a cassette and write the song's name on its label. Its reels
  turn while it plays
- **Screen flash** on the one (for loud rooms) and **buzz** on every beat
- **Keep ticking** with the screen off or in another app. Otherwise, leaving the app stops the
  click, like shutting a tape deck's lid. Pulling out headphones always stops it, rather than
  carrying on out of the speaker
- **Six channels** for the look: arcade, high score, roller rink, camcorder, sunday drive and
  record shop

### Room for the controls

The screen is one slim status line, the television, the tempo controls, the tabs and START.
Scroll a tab's settings up and the television first **folds into a strip** (tempo, time
signature, beat lights and ball, all still live), handing its room to the settings. Scroll
back to the top, or press START, and it unfolds. With the phone **on its side**, or on a
tablet, the screen splits in two: the television and tempo on the left, the tabs and START
on the right. A small window scrolls as one column.

## Keeping good time

A metronome that drifts is worse than none, so the timing does not depend on how busy the
phone is.

- **Clicks are placed, not timed.** The sequencer (`engine/Sequencer.kt`) counts in beats
  and works out the exact audio frame of every click from the sound card's sample clock.
  Nothing waits on a timer, and the count is worked out afresh from the frame number rather
  than added up, so no rounding error builds: the unit tests play an hour at 133 BPM and
  check every beat lands within a frame (1/48,000 of a second) of where it should.
- **A tenth of a second in hand.** The audio thread writes 5 ms blocks about 100 ms ahead of
  the speaker, so a busy moment on the phone cannot make a click late. A new tempo is still
  felt at once, mid-beat.
- **The lights follow your ears, not the code.** The engine asks the sound card which frame
  is coming out of the speaker *now*, so the lights, ball, flash and buzz happen when a click
  is heard, after the speaker's own delay, not when it was written.
- **The ball glides.** The sound card's clock arrives in uneven steps, and is often revised
  in the first moments after START. The display reads it through a steadied clock
  (`engine/DisplayClock.kt`) that runs on by itself, eases small errors out over a few
  frames and never runs backwards, and each frame is drawn for the moment it reaches the
  glass, by the display's own frame clock.
- **Every sound starts on its frame.** The CI preview renders bars of every kit and checks the
  first sound of each click is within a millisecond of its frame (`timing.txt`).

## One-time setup: a signing key

Android only lets an update replace an app signed with the same key. Backbeat uses the
**same family key as Djungleskwidgets and Funhouse**, under the same secret names. In this
repository on GitHub: **Settings → Secrets and variables → Actions → New repository
secret**, twice:

| Name | Value |
|---|---|
| `GLIMMER_KEYSTORE_BASE64` | the same base64 text as in reimagined-potato (`glimmer.jks.txt`) |
| `GLIMMER_KEYSTORE_PASSWORD` | the same password |

(The `GLIMMER_` names come from Djungleskwidgets' first name. They are kept so all three
repositories hold the same two secrets.) Without them, CI still builds a working APK but
warns that it is signed with a throwaway key, which cannot update an earlier install.

## Publishing a release

```sh
git tag v0.1.0
git push origin v0.1.0
```

CI builds `backbeat-0.1.0.apk`, and a **Backbeat v0.1.0** release appears on the
**Releases** page with the APK and its SHA-256 checksum. A tagged build refuses to run
without the signing key.

## Installing it on a phone

1. Download `backbeat-X.Y.Z.apk` from the latest release. (Untagged test builds are under
   **Actions** → **Backbeat Android** → a green run → the **backbeat-apk** artifact.)
2. Open it on the phone and allow installs from that app once, as with the other two.
3. Open **Backbeat** and press **START**.

It needs Android 10 or later.

## Checking a change before handing it over

Every push that touches `backbeat/` (other than Markdown) runs the unit tests, builds the
APK, then runs a "preview" build on an Android emulator. A demo activity in
`app/src/preview/` renders the engine to pictures and sound:

- `kits.png`: every sound of every kit as an oscilloscope trace
- `bars.png`: a few bars of seven grooves (compound time, swing, ghosts and rests, silent
  bars, a steep ramp), with a line where each click was scheduled
- `timing.txt`: how soon each of those clicks is heard after its line
- `clock.txt`: for three seconds after START on the emulator's sound card, how evenly the ball
  moves, by the steadied clock and by the raw one
- `sounds/*.wav`: two bars of each kit, to listen to

It then walks through the app (playing, the practice aids, a tape, 6/8 swing, four
channels, and ticking in the background with the notification showing) and takes
screenshots. All of it lands in the **backbeat-screenshots** artifact. The **Screenshots as
text** job prints the pictures as base64 JPEGs, for tools that can read logs but not
download artifacts.

## How it works

| Piece | Where |
|---|---|
| Tempos, meters, accents, subdivisions, practice settings | `app/src/main/java/app/djungle/backbeat/engine/Rhythm.kt` |
| Where every click falls, to the frame | `…/engine/Sequencer.kt` |
| The five kits, synthesised in code, and the mixer | `…/engine/Sounds.kt` |
| Tap tempo | `…/engine/TapTempo.kt` |
| The audio thread, and what is being heard now | `…/engine/Metronome.kt` |
| The steadied clock the display reads | `…/engine/DisplayClock.kt` |
| Rendering to a buffer, for tests and the CI preview | `…/engine/Bounce.kt` |
| Ticking with the screen off | `…/TickService.kt` |
| Settings and tapes, kept on the phone | `…/data/AppSettings.kt` |
| How the screen is laid out: tall, wide or short, and the folding television | `…/ui/Layout.kt` |
| Jetpack Compose screens and the retro theme | `…/ui/` |
| CI demo driver (preview build only) | `app/src/preview/` |

The sounds are built from sine waves, band-limited square waves and filtered noise when the
metronome starts, so there are no sample files, and any of them can be retuned in a line or
two. The engine (`engine/`, apart from `Metronome.kt`) is plain Kotlin with no Android in it,
which is what lets the unit tests in `app/src/test/` play hours of music in a second.

## Building locally

Needs JDK 17 and the Android SDK (Android Studio brings both). Without Android Studio, the
SDK's command-line tools will do: install `platforms;android-35` and `build-tools;34.0.0`,
and point `ANDROID_HOME` (or `sdk.dir` in `local.properties`) at it. The SDK and Google's
Maven repository both download from `dl.google.com`.

```sh
./gradlew testReleaseUnitTest assembleRelease   # → app/build/outputs/apk/release/app-release.apk
```

Fonts are from Google Fonts under the SIL Open Font License; see `font-licences/`.
