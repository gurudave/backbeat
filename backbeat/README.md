# Backbeat ✧

A metronome for an Android phone. No adverts, no accounts, no tracking. It is the third
app in the family, after Djungleskwidgets (in `gurudave/reimagined-potato`) and Funhouse (in
`gurudave/psychic-fishstick`), with the same arcade-and-VCR look and the same six channels.

The app has no internet permission, so it *cannot* show ads or send anything anywhere. CI
fails the build if network access ever creeps in, or any permission beyond the short list the
manifest explains.

## What it does

- **20 to 300 BPM**, set on a **tuning dial** (drag it like the scale of an old radio), with
  **− and +** keys that speed up when held, or by **TAP**ping along with the music
- **13 time signatures**, from 1/4 to 12/8. The compound ones (6/8, 9/8, 12/8) count in
  dotted groups, and 5/8 and 7/8 come pre-grouped (3+2 and 2+2+3)
- **Accents you can see:** a light for every beat, as tall as it is loud. Tap one to step it
  through **accent, beat, ghost and rest**
- **Between the beats:** quavers, triplets, semiquavers or **swing**
- **Cross-rhythms:** 2 to 9 clicks spread evenly across the bar, in a sound of their own,
  against the beats: 3:4, 5:4, 2:3 and the rest. A second row on the screen lights each one,
  with a cursor sweeping across at its own pace, so you can see how the two fit together
- **Six sounds**, all synthesised on the phone: an arcade **blip**, a quartz **beep**, a
  **wood** block, a drum machine's **cowbell**, **drums** (kick on the accents, snare on the
  beats, hi-hat in between, clave for the cross-rhythm), and a **wind-up** metronome's tick
  with its little bell on the one. Tapping one while stopped plays a sample
- **A pixel ball** that hops along the beat lights and lands on each one *as it sounds*, so
  you see the beat coming rather than only see it go
- **Practice aids:**
  - **tempo ramp**: up (or down) a few BPM every few bars, until a target. A pixel firework
    goes off when you get there
  - **silent bars**: play two bars, hush for two, and see whether you are still on the beat
    when it comes back. The lights and ball carry on through the silence
  - **dropout**: a share of beats (10 to 50%) goes missing at random, for you to fill in. The
    one always plays, so there is something to come home to
  - **stop after** 5 to 60 minutes, always at the end of a bar
  - **a logbook**: minutes played each day, twelve weeks of them as a grid of pixels, with
    today's time, your streak and the total
- **The Ear** (see below): it listens as you play along and **scores every hit** against the
  click, arcade style, with a **hi-score table**; and it can **find the tempo** of music
  playing nearby
- **Tapes:** save a set-up as a cassette and write the song's name on its label. Its reels
  turn while it plays. The tapes are a **set list**: arrange them with ^, and step through
  them on the big screen or with a pedal
- **The big screen** (the little television beside START): the beat as a number filling the
  screen, flashing with every beat, for reading from a drum stool or across a room, with the
  tape loaded and the one after it
- **Pedals and keys:** a Bluetooth page-turner pedal steps through the tapes (page up and
  down, or the arrows), a headset's button starts and stops, and a keyboard's space bar,
  arrows, + and − and T (for tap) do what you would hope
- **Screen flash** on the one (for loud rooms) and **buzz** on every beat
- **Keep ticking** with the screen off or in another app. Otherwise, leaving the app stops the
  click, like shutting a tape deck's lid. Pulling out headphones always stops it, rather than
  carrying on out of the speaker
- **Six channels** for the look: arcade, high score, roller rink, camcorder, sunday drive and
  record shop. The television switches on as a cathode-ray tube did, and changing channel
  brings a burst of snow and the channel's number in green

### The Ear

Turn on **judge my timing** on the EAR tab, press START and play along: clap, play a pad, a
drum, a guitar. Every hit is graded against the nearest click, **perfect** (within 10 ms),
**great** (20), **good** (35) or **miss**, with points, and a multiplier that grows with every
hit in a row that is not a miss. A strip in the top of the screen shows each hit early or late
of the click, and a pip on the beat's light shows where it fell. At STOP comes the scorecard (on
the big screen, it waits until you leave it, so as not to get in the way mid-set): a
rank from S to D, the score, the best combo, how tight you were, and whether you **rushed or
dragged**, with the click and, separately, **on your own** in silent bars, rests and dropped
beats, which is where it counts. A good score goes on the hi-score table under your initials.

The first time, it **calibrates**: it plays a few clicks and listens to them, to learn how late
this phone's microphone hears things. Through the phone's own speaker the microphone hears the
click as well as you, so it only counts a hit that is clearly louder than the click; it hears
you best in silent bars and rests, or on a drum. With headphones it hears every beat. A **fine
tune** setting corrects by hand, for Bluetooth headphones that hide their delay.

**What tempo is this?** Press LISTEN with music playing nearby and it works out the tempo in a
few seconds, offering half and double as well, since music is often counted either way. SET
puts the metronome there.

The microphone is asked for only when the Ear is first used, and is only open while it
listens, with the app on the screen. Nothing is recorded or kept: only the times of hits. And
with no internet permission, nothing it hears could leave the phone anyway.

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

### Hearing the player

The Ear judges to the millisecond, so it has to know exactly when each hit happened and when
each click was heard, on one clock.

- **Every frame of the microphone is put on the speaker's clock.** The microphone says when
  each frame reached it, calibration says how far that is from the truth, and the engine says
  when each click comes out of the speaker. A hit is set against the click it was aimed at
  however long the phone takes to record and to play.
- **A hit is found to a fraction of a millisecond** (`engine/Onsets.kt`). The detector listens
  for a sudden rise, at least three times the loudest it had been a few milliseconds before,
  so a ghost note after a loud hit is heard but the ring of a cymbal is not. It then goes back
  to where the hit first reached 30% of its peak: where it began.
- **Its own click is shadowed.** Just around each click, a hit must be well louder than the
  click is to the microphone (as calibration measured it), and it is placed where it rose
  past that level, where the player's hit began, not the click's.
- **Tested on performances with known timings.** The unit tests clap, pluck strings and play
  drums at known moments, over a noisy room and the click's own echo, and check every hit is
  found and placed within a millisecond or so. The CI preview does the same on the phone
  (`ear.txt`), and plays the tempo finder drum loops of known tempo (`tempo.txt`).
- **The tempo finder** (`engine/TempoFinder.kt`) takes the spectrum every 5 ms, adds up how
  much louder each band has just become, and finds the spacing at which that pattern best
  repeats, favouring spacings that repeat over two and four beats as a bar does. It then
  measures over four beats rather than one, to a fraction of a BPM.

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
- `bars.png`: a few bars of nine grooves (compound time, swing, ghosts and rests, silent
  bars, a steep ramp, a cross-rhythm, dropout), with a line where each click was scheduled
- `timing.txt`: how soon each of those clicks is heard after its line
- `ear.txt` and `ear.png`: a synthetic player clapping along over the click's echo, and where
  the Ear placed each clap against where it really was
- `tempo.txt`: the tempo finder, played drum loops of known tempo
- `clock.txt`: for three seconds after START on the emulator's sound card, how evenly the ball
  moves, by the steadied clock and by the raw one
- `sounds/*.wav`: two bars of each kit, to listen to

It then walks through the app (playing, the practice aids, a tape, 6/8 swing, four
channels, the Ear, a cross-rhythm, the big screen driven by a pedal's keys, the logbook, and
ticking in the background with the notification showing) and takes screenshots. The emulator
has no microphone worth the name, so for the Ear the preview build swaps in a stand-in
(`FakeMicrophone.kt`) that claps along with the metronome, a touch late and a little loose,
over the click's own echo, with a clock deliberately out, for calibration to find; with the
metronome stopped, it plays a drum loop for the tempo finder. All of it lands in the **backbeat-screenshots** artifact. The **Screenshots as
text** job prints the pictures as base64 JPEGs, for tools that can read logs but not
download artifacts.

## How it works

| Piece | Where |
|---|---|
| Tempos, meters, accents, subdivisions, cross-rhythms, practice settings | `app/src/main/java/app/djungle/backbeat/engine/Rhythm.kt` |
| Where every click falls, to the frame | `…/engine/Sequencer.kt` |
| The six kits, synthesised in code, and the mixer | `…/engine/Sounds.kt` |
| Hearing a hit in the microphone's sound | `…/engine/Onsets.kt` |
| Grading hits, the scorecard, and calibration | `…/engine/Judge.kt` |
| Finding the tempo of music | `…/engine/TempoFinder.kt` |
| The Ear: the microphone, on the speaker's clock | `…/engine/Ear.kt` |
| Tap tempo | `…/engine/TapTempo.kt` |
| The audio thread, and what is being heard now | `…/engine/Metronome.kt` |
| The steadied clock the display reads | `…/engine/DisplayClock.kt` |
| Rendering to a buffer, for tests and the CI preview | `…/engine/Bounce.kt` |
| Ticking with the screen off | `…/TickService.kt` |
| Settings and tapes, kept on the phone | `…/data/AppSettings.kt` |
| The logbook and the hi-score table | `…/data/Records.kt` |
| How the screen is laid out: tall, wide or short, and the folding television | `…/ui/Layout.kt` |
| Jetpack Compose screens and the retro theme | `…/ui/` |
| The EAR tab and the scorecard | `…/ui/EarPanel.kt` |
| The big screen, and pedals and keys | `…/ui/Stage.kt` |
| CI demo driver and the stand-in microphone (preview build only) | `app/src/preview/` |

The sounds are built from sine waves, band-limited square waves and filtered noise when the
metronome starts, so there are no sample files, and any of them can be retuned in a line or
two. The engine (`engine/`, apart from `Metronome.kt` and `Ear.kt`) is plain Kotlin with no
Android in it, which is what lets the unit tests in `app/src/test/` play hours of music, and
clap along to it, in a second.

## Building locally

Needs JDK 17 and the Android SDK (Android Studio brings both). Without Android Studio, the
SDK's command-line tools will do: install `platforms;android-35` and `build-tools;34.0.0`,
and point `ANDROID_HOME` (or `sdk.dir` in `local.properties`) at it. The SDK and Google's
Maven repository both download from `dl.google.com`.

```sh
./gradlew testReleaseUnitTest assembleRelease   # → app/build/outputs/apk/release/app-release.apk
```

Fonts are from Google Fonts under the SIL Open Font License; see `font-licences/`.
