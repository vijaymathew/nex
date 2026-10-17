# Audio Libraries

This page documents the sound library shipped under `lib/audio`.

These are library classes, not core built-in classes. Load them with `intern`.
They are written in Nex on top of Java Sound (`javax.sound.sampled` and
`javax.sound.midi`, through `with "java"`), with no runtime, typechecker or
compiler support of their own, and need no third-party libraries.

## Platform Scope

- `audio/Audio_Device`: JVM interpreter, generated JVM
- `audio/Sound`: JVM interpreter, generated JVM
- `audio/Track`, `audio/Music`: JVM interpreter, generated JVM
- `audio/Midi`, `audio/Synth`, `audio/Midi_Score`, `audio/Midi_Song`: JVM interpreter, generated JVM

Playing sound needs a sound device. Everything else works without one, so the
same program runs headless, for example in tests or on a server: see
*Without a sound device*.

## Load

```nex
intern audio/Sound        -- short effects; also interns Audio_Device
intern audio/Music        -- recorded tracks; also interns Track
intern audio/Synth        -- notes played live; also interns Midi
intern audio/Midi_Song    -- MIDI files and scores; also interns Track, Midi_Score, Midi
```

## Example

```nex
intern audio/Sound
intern audio/Synth

let beep := create Sound.tone(880.0, 120)   -- no sound file needed
beep.play()                                  -- returns at once

let s := create Synth.make()
s.set_instrument(0, Midi.SQUARE_LEAD)
s.play_note(0, Midi.MIDDLE_C, 100, 150)     -- C for 150 ms
```

`examples/audio_example.nex` plays a generated beep, an arpeggio on the
synthesizer and a short tune written in code, and needs no sound files.

## Without a sound device

When there is no sound device, or the environment variable `NEX_AUDIO` (or the
Java system property `nex.audio`) is `off`, every audio class still works but
plays nothing:

- `Sound` still loads, knows its length and counts `play_count`.
- `Synth` still keeps each channel's instrument and counts `notes_played`.
- `Music` and `Midi_Song` still move through their states. A `Midi_Song`'s
  sequencer keeps running in real time, so it reaches its end as usual; a
  `Music` stays at position 0 and never reaches its end by itself.
- `is_audible()` reports `false`.

A test can therefore drive a program headless and check which sounds it asked
for — say, that the hit sound played three times.

## Files

`Sound` and `Music` read WAV, AIFF and AU files with uncompressed (PCM) audio;
MP3 and OGG are not supported. `Midi_Song` reads standard MIDI files. A
relative file name is resolved against the directory `nex` was started from
(`NEX_USER_DIR`), as in `Picture.load`.

## `Audio_Device`

| Feature | Type | Description |
|---|---|---|
| `make` | constructor | |
| `is_available()` | `Boolean` | Whether sound can be heard: a device is present and sound is not switched off. |
| `is_switched_off()` | `Boolean` | Whether `NEX_AUDIO` or `nex.audio` is `off`. |

## `Sound`

A short sound effect held in memory. `play()` starts a fresh copy at once and
returns without waiting, so a burst of plays sounds like a burst. Up to
`voices` copies (4 by default) play at once; beyond that, the copy that has
played longest starts over.

### Construction

| Constructor | Arguments | Description |
|---|---|---|
| `load` | `filename: String` | The WAV, AIFF or AU file. Raises if it is missing or unreadable. |
| `tone` | `frequency: Real, duration_ms: Integer` | A sine tone, made in memory, fading in and out over 5 ms. Requires `20.0 <= frequency <= 20000.0` and `0 < duration_ms <= 10000`. |

### Features

| Feature | Type | Description |
|---|---|---|
| `source` | `String` | The file name given to `load`; `""` for a tone. |
| `duration_ms` | `Integer` | Length in milliseconds. |
| `voices` | `Integer` | How many copies may play at once (1..16). |
| `volume` | `Real` | 0.0 (silent) to 1.0 (as recorded). |
| `play_count` | `Integer` | How many times `play()` has been called, heard or not. |
| `is_closed` | `Boolean` | Whether `close()` has been called. |
| `is_audible()` | `Boolean` | Whether the sound will be heard. |
| `has_volume_control()` | `Boolean` | Whether the device honours `volume`. When it does not, the sound plays as recorded. |
| `is_playing()` | `Boolean` | Whether any copy is playing now. Read live; a copy can finish at any moment. |
| `play()` | command | Start a copy. Requires `not is_closed`; ensures `play_count = old play_count + 1`. |
| `stop()` | command | Silence every copy. |
| `set_volume(v: Real)` | command | Requires `0.0 <= v <= 1.0`. |
| `set_voices(n: Integer)` | command | Requires `1 <= n <= 16`. |
| `close()` | command | Release the audio lines; the sound cannot be played again. |

## `Track`

The deferred class behind `Music` and `Midi_Song`: something that plays over
time, with the state machine below. Every contract here is inherited.

```
       play            pause
  STOPPED ──► PLAYING ───────► PAUSED
     ▲          │  ▲  resume     │
     └── stop ──┘  └─────────────┘   (stop from PAUSED too)
```

**The state changes only on the program's calls.** The audio itself runs on a
background thread, so a track can reach its end at any moment. That shows at
once in `reached_end()`, and the state follows it when `update()` is called —
a game calls it once per frame:

```nex
music.update()
if music.is_stopped() then next_level_music.play() end
```

This is what lets `play()` promise `is_playing()`: nothing can change the state
between the routine's body and the postcondition check.

| Feature | Type | Description |
|---|---|---|
| `state()` | `String` | `"STOPPED"`, `"PLAYING"` or `"PAUSED"` (also `STOPPED`, `PLAYING`, `PAUSED`). |
| `is_stopped()`, `is_playing()`, `is_paused()` | `Boolean` | The state. |
| `is_looping` | `Boolean` | Whether the track starts again when it ends. |
| `volume` | `Real` | 0.0 (silent) to 1.0. |
| `is_closed` | `Boolean` | Whether `close()` has been called. |
| `reached_end()` | `Boolean` | Whether the audio has run out (never while looping). Read live. |
| `update()` | command | A track that has reached its end stops and rewinds. |
| `play()` | command | Start from the beginning. Requires `not is_closed` and `is_stopped()`; ensures `is_playing()`. |
| `pause()` | command | Requires `is_playing()`; ensures `is_paused()`. |
| `resume()` | command | Carry on from where `pause()` left off. Requires `is_paused()`; ensures `is_playing()`. |
| `stop()` | command | Stop and rewind, from any state. Ensures `is_stopped()`. |
| `set_looping(b: Boolean)` | command | |
| `set_volume(v: Real)` | command | Requires `0.0 <= v <= 1.0`. |
| `close()` | command | Stop and release the device. Ensures `is_closed` and `is_stopped()`. |

## `Music`

A recorded track — background music, an ambience loop — held in memory, which
suits tracks of a few minutes. Inherits `Track`.

| Feature | Type | Description |
|---|---|---|
| `load(filename: String)` | constructor | The WAV, AIFF or AU file. Raises if it is missing or unreadable. |
| `source` | `String` | The file name given to `load`. |
| `duration_ms` | `Integer` | Length in milliseconds. |
| `is_audible()` | `Boolean` | Whether the track will be heard. |
| `position_ms()` | `Integer` | How far into the track playback is. |
| `seek(ms: Integer)` | command | Move to `ms` without changing the state. Requires `0 <= ms <= duration_ms`. |

## `Midi`

General MIDI constants, so a program writes `Midi.TRUMPET`, not `56`:
`DRUM_CHANNEL` (9), `MIDDLE_C` (60), instruments from `PIANO` to `SYNTH_DRUM`,
and drum notes such as `BASS_DRUM`, `SNARE` and `CLOSED_HI_HAT`. Notes are
numbered by semitone, so 61 is the C sharp above middle C and 72 the C an
octave up.

## `Synth`

Notes played live on Java's built-in General MIDI synthesizer. There are 16
channels (0..15), each set to one of 128 instruments; channel 9 plays drums,
where each note number is a different drum.

| Feature | Type | Description |
|---|---|---|
| `make` | constructor | Opens the synthesizer, every channel on `Midi.PIANO`. |
| `is_open` | `Boolean` | Whether the synthesizer is open. |
| `notes_played` | `Integer` | How many notes have been started, heard or not. |
| `is_audible()` | `Boolean` | Whether notes will be heard. |
| `instrument(channel: Integer)` | `Integer` | The channel's General MIDI program. |
| `set_instrument(channel, program: Integer)` | command | Requires a channel 0..15 and a program 0..127. |
| `note_on(channel, pitch, velocity: Integer)` | command | Start a note, sounding until `note_off`. Requires pitch 0..127 and velocity 1..127. |
| `note_off(channel, pitch: Integer)` | command | |
| `play_note(channel, pitch, velocity, duration_ms: Integer)` | command | `note_on` now and `note_off` after `duration_ms`, without waiting. |
| `set_channel_volume(channel, level: Integer)` | command | The channel's loudness, 0..127. |
| `all_notes_off()` | command | Silence every note on every channel. |
| `close()` | command | Close the synthesizer. |

`note` is a keyword in Nex, so the note number is called `pitch`.

## `Midi_Score`

A piece of music written in code, note by note. Time is counted in ticks, and a
beat is `ticks_per_beat` ticks; a song plays at 120 beats per minute unless
`Midi_Song.set_tempo` says otherwise.

```nex
let score := create Midi_Score.make(4)       -- 4 ticks per beat
score.set_instrument(0, Midi.SQUARE_LEAD, 0)
score.add_note(0, 60, 100, 0, 4)             -- C, one beat
score.add_note(0, 64, 100, 4, 4)             -- E
score.add_note(0, 67, 100, 8, 8)             -- G, two beats
let tune := create Midi_Song.from_score(score)
tune.play()
```

| Feature | Type | Description |
|---|---|---|
| `make(ticks_per_beat: Integer)` | constructor | An empty score. Requires `ticks_per_beat > 0`. |
| `ticks_per_beat` | `Integer` | |
| `note_count` | `Integer` | How many notes have been added. |
| `length_ticks` | `Integer` | The tick at which the last note ends. |
| `add_note(channel, pitch, velocity, start, length: Integer)` | command | Requires `start >= 0` and `length > 0`; ensures `length_ticks >= start + length`. |
| `set_instrument(channel, program, at: Integer)` | command | Switch the channel's instrument at tick `at`. |
| `save(filename: String)` | command | Write a standard MIDI file. Requires at least one note. |

## `Midi_Song`

A MIDI file or a `Midi_Score`, played by Java's sequencer on the built-in
synthesizer. Inherits `Track`.

| Feature | Type | Description |
|---|---|---|
| `load(filename: String)` | constructor | The standard MIDI file. Raises if it is missing or unreadable. |
| `from_score(score: Midi_Score)` | constructor | The notes of the score so far. Requires at least one note. |
| `source` | `String` | The file name given to `load`; `""` for a score. |
| `length_ticks` | `Integer` | Length in ticks. |
| `tempo_bpm` | `Real` | Beats per minute, from the song when it loads. |
| `speed` | `Real` | Playback rate: 2.0 plays twice as fast, at the same pitch. |
| `is_audible()` | `Boolean` | Whether the song will be heard. |
| `position_ticks()` | `Integer` | How far into the song playback is. |
| `set_tempo(bpm: Real)` | command | Requires `bpm > 0.0`. |
| `set_speed(factor: Real)` | command | Requires `0.25 <= factor <= 4.0`. |
| `set_channel_muted(channel: Integer, muted: Boolean)` | command | Mute or unmute one channel, e.g. the drums. |
| `is_channel_muted(channel: Integer)` | `Boolean` | |

`set_volume` sets the synthesizer's master volume, so it scales the whole song
whatever volumes the song sets on its own channels.
