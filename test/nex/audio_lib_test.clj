(ns nex.audio-lib-test
  "audio/Sound, audio/Music, audio/Synth, audio/Midi_Score, audio/Midi_Song
   (lib/audio/): sound playback built entirely on Java interop.

   Every test runs with sound switched off (the nex.audio system property),
   so nothing is heard and no sound device is needed. That is the headless
   behaviour the library promises: state, counters and contracts work exactly
   as with a device, and playback is silent.

   Behaviour is asserted on both backends."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [nex.eval :as e])
  (:import (java.io ByteArrayInputStream File)
           (javax.sound.sampled AudioFileFormat$Type AudioFormat AudioInputStream AudioSystem)))

(use-fixtures :once
  (fn [run]
    (let [before (System/getProperty "nex.audio")]
      (System/setProperty "nex.audio" "off")
      (try (run)
           (finally
             (if before
               (System/setProperty "nex.audio" before)
               (System/clearProperty "nex.audio")))))))

;; ---------------------------------------------------------------------------
;; Helpers (mirrors test/nex/graphics_lib_test.clj)
;; ---------------------------------------------------------------------------

(defn- run-backend
  [code interpret?]
  (let [f (File/createTempFile "audio" ".nex")]
    (try
      (spit f code)
      (let [out (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))]
        (is (not (str/includes? out "falling back to the tree-walking interpreter"))
            (str "compiled backend declined this program:\n" out))
        (str/split-lines (str/trim-newline out)))
      (finally (.delete f)))))

(defn- both
  "Printed output lines of CODE, asserted identical on both backends."
  [code]
  (let [compiled (run-backend code false)
        interpreted (run-backend code true)]
    (is (= interpreted compiled) "compiled and interpreted output must agree")
    compiled))

(defn- raises-on-both?
  "Whether CODE fails at runtime on both backends with a message containing MSG."
  [code msg]
  (every? (fn [interpret?]
            (let [f (File/createTempFile "audio" ".nex")]
              (try
                (spit f code)
                (try (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))
                     false
                     (catch Throwable t
                       (loop [x t]
                         (cond
                           (str/includes? (str (ex-message x)) msg) true
                           (.getCause x) (recur (.getCause x))
                           :else false))))
                (finally (.delete f)))))
          [false true]))

(defn- program [& lines] (str/join "\n" lines))

(defn- silent-wav
  "A temporary mono 16-bit WAV file of MS milliseconds of silence at 22050 Hz."
  [ms]
  (let [frames (quot (* 22050 ms) 1000)
        fmt (AudioFormat. 22050.0 16 1 true false)
        stream (AudioInputStream. (ByteArrayInputStream. (byte-array (* 2 frames))) fmt frames)
        f (File/createTempFile "audio" ".wav")]
    (AudioSystem/write stream AudioFileFormat$Type/WAVE f)
    (.deleteOnExit f)
    (.getPath f)))

;; ---------------------------------------------------------------------------
;; Audio_Device
;; ---------------------------------------------------------------------------

(deftest switched-off-device-is-unavailable
  (is (= ["true" "false"]
         (both (program
                "intern audio/Audio_Device"
                "let d := create Audio_Device.make()"
                "print(d.is_switched_off())"
                "print(d.is_available())")))))

;; ---------------------------------------------------------------------------
;; Sound
;; ---------------------------------------------------------------------------

(deftest tone-counts-plays-silently
  (is (= ["120" "false" "0" "3" "false"]
         (both (program
                "intern audio/Sound"
                "let beep := create Sound.tone(880.0, 120)"
                "print(beep.duration_ms)"
                "print(beep.is_audible())"
                "print(beep.play_count)"
                "beep.play()"
                "beep.play()"
                "beep.play()"
                "print(beep.play_count)"
                "print(beep.is_playing())")))))

(deftest sound-loads-a-wav-file
  (let [path (silent-wav 250)]
    (is (= ["250" "true" "1"]
           (both (program
                  "intern audio/Sound"
                  (str "let s := create Sound.load(\"" path "\")")
                  "print(s.duration_ms)"
                  (str "print(s.source = \"" path "\")")
                  "s.play()"
                  "print(s.play_count)"))))))

(deftest sound-settings-are-checked
  (is (= ["0.25" "2"]
         (both (program
                "intern audio/Sound"
                "let s := create Sound.tone(440.0, 50)"
                "s.set_volume(0.25)"
                "s.set_voices(2)"
                "print(s.volume)"
                "print(s.voices)"))))
  (is (raises-on-both? (program "intern audio/Sound"
                                "let s := create Sound.tone(10.0, 50)")
                       "audible_pitch"))
  (is (raises-on-both? (program "intern audio/Sound"
                                "let s := create Sound.tone(440.0, 50)"
                                "s.set_volume(1.5)")
                       "in_range"))
  (is (raises-on-both? (program "intern audio/Sound"
                                "let s := create Sound.tone(440.0, 50)"
                                "s.close()"
                                "s.play()")
                       "open"))
  (is (raises-on-both? (program "intern audio/Sound"
                                "let s := create Sound.load(\"/no/such/file.wav\")")
                       "no such file")))

;; ---------------------------------------------------------------------------
;; Music and the Track state machine
;; ---------------------------------------------------------------------------

(deftest music-moves-through-its-states
  (let [path (silent-wav 300)]
    (is (= ["300" "\"STOPPED\"" "\"PLAYING\"" "\"PAUSED\"" "\"PLAYING\"" "\"STOPPED\"" "true"]
           (both (program
                  "intern audio/Music"
                  (str "let m := create Music.load(\"" path "\")")
                  "print(m.duration_ms)"
                  "print(m.state())"
                  "m.play()"
                  "print(m.state())"
                  "m.pause()"
                  "m.seek(100)"
                  "print(m.state())"
                  "m.resume()"
                  "print(m.state())"
                  "m.close()"
                  "print(m.state())"
                  "print(m.is_closed)"))))))

(deftest track-transitions-are-guarded
  (let [path (silent-wav 100)]
    (is (raises-on-both? (program "intern audio/Music"
                                  (str "let m := create Music.load(\"" path "\")")
                                  "m.pause()")
                         "playing"))
    (is (raises-on-both? (program "intern audio/Music"
                                  (str "let m := create Music.load(\"" path "\")")
                                  "m.play()"
                                  "m.play()")
                         "stopped"))
    (is (raises-on-both? (program "intern audio/Music"
                                  (str "let m := create Music.load(\"" path "\")")
                                  "m.close()"
                                  "m.play()")
                         "open"))
    (is (raises-on-both? (program "intern audio/Music"
                                  (str "let m := create Music.load(\"" path "\")")
                                  "m.seek(5000)")
                         "in_track"))))

;; ---------------------------------------------------------------------------
;; Synth
;; ---------------------------------------------------------------------------

(deftest synth-keeps-instruments-and-counts-notes
  (is (= ["false" "0" "80" "2" "false"]
         (both (program
                "intern audio/Synth"
                "let s := create Synth.make()"
                "print(s.is_audible())"
                "print(s.instrument(0))"
                "s.set_instrument(0, Midi.SQUARE_LEAD)"
                "print(s.instrument(0))"
                "s.note_on(0, Midi.MIDDLE_C, 100)"
                "s.note_off(0, Midi.MIDDLE_C)"
                "s.play_note(Midi.DRUM_CHANNEL, Midi.SNARE, 110, 100)"
                "print(s.notes_played)"
                "s.close()"
                "print(s.is_open)")))))

(deftest synth-ranges-are-checked
  (is (raises-on-both? (program "intern audio/Synth"
                                "let s := create Synth.make()"
                                "s.note_on(16, 60, 100)")
                       "valid_channel"))
  (is (raises-on-both? (program "intern audio/Synth"
                                "let s := create Synth.make()"
                                "s.note_on(0, 128, 100)")
                       "valid_pitch"))
  (is (raises-on-both? (program "intern audio/Synth"
                                "let s := create Synth.make()"
                                "s.note_on(0, 60, 0)")
                       "audible_velocity"))
  (is (raises-on-both? (program "intern audio/Synth"
                                "let s := create Synth.make()"
                                "s.close()"
                                "s.note_on(0, 60, 100)")
                       "open")))

;; ---------------------------------------------------------------------------
;; Midi_Score and Midi_Song
;; ---------------------------------------------------------------------------

(def ^:private three-note-score
  ["let score := create Midi_Score.make(4)"
   "score.set_instrument(0, Midi.SQUARE_LEAD, 0)"
   "score.add_note(0, 60, 100, 0, 2)"
   "score.add_note(0, 64, 100, 2, 2)"
   "score.add_note(0, 67, 100, 4, 2)"])

(deftest score-tracks-notes-and-length
  (is (= ["3" "6"]
         (both (apply program "intern audio/Midi_Song"
                      (concat three-note-score
                              ["print(score.note_count)"
                               "print(score.length_ticks)"]))))))

(deftest song-from-score-plays-to-its-end
  ;; Without a device the sequencer still runs in real time, silently, so a
  ;; song reaches its end and update() stops it. At speed 4 the six ticks
  ;; take under a quarter of a second.
  (is (= ["6" "120.0" "\"PLAYING\"" "\"STOPPED\"" "true"]
         (both (apply program "intern audio/Midi_Song" "import java.lang.Thread"
                      (concat three-note-score
                              ["let song := create Midi_Song.from_score(score)"
                               "print(song.length_ticks)"
                               "print(song.tempo_bpm)"
                               "song.set_speed(4.0)"
                               "song.play()"
                               "print(song.state())"
                               "let waited := 0"
                               "from until song.is_stopped() or waited > 500 do"
                               "  song.update()"
                               "  with \"java\" do Thread.sleep(10) end"
                               "  waited := waited + 1"
                               "end"
                               "print(song.state())"
                               "print(waited <= 500)"]))))))

(deftest score-saves-and-loads-as-a-midi-file
  (let [f (File/createTempFile "audio" ".mid")]
    (.deleteOnExit f)
    (is (= ["6" "true" "true"]
           (both (apply program "intern audio/Midi_Song"
                        (concat three-note-score
                                [(str "score.save(\"" (.getPath f) "\")")
                                 (str "let song := create Midi_Song.load(\"" (.getPath f) "\")")
                                 "print(song.length_ticks)"
                                 "song.set_channel_muted(Midi.DRUM_CHANNEL, true)"
                                 "print(song.is_channel_muted(Midi.DRUM_CHANNEL))"
                                 "song.set_looping(true)"
                                 "print(song.is_looping)"])))))))

(deftest song-settings-are-checked
  (is (raises-on-both? (apply program "intern audio/Midi_Song"
                              (concat three-note-score
                                      ["let song := create Midi_Song.from_score(score)"
                                       "song.set_speed(10.0)"]))
                       "sensible_speed"))
  (is (raises-on-both? (program "intern audio/Midi_Song"
                                "let empty := create Midi_Score.make(4)"
                                "let song := create Midi_Song.from_score(empty)")
                       "has_notes"))
  (is (raises-on-both? (program "intern audio/Midi_Song"
                                "let score := create Midi_Score.make(0)")
                       "positive_resolution")))

;; ---------------------------------------------------------------------------
;; Threads: a stopped song must not keep the JVM alive
;; ---------------------------------------------------------------------------

(defn- live-sequencer-threads []
  (count (filter #(and (.isAlive ^Thread %)
                       (not (.isDaemon ^Thread %))
                       (= "Java Sound Sequencer" (.getName ^Thread %)))
                 (keys (Thread/getAllStackTraces)))))

(deftest stopped-songs-leave-no-sequencer-thread
  ;; Java's sequencer thread is not a daemon, so one left open keeps the JVM
  ;; from exiting. A song holds one only while playing or paused.
  (let [before (live-sequencer-threads)]
    (is (= ["\"STOPPED\"" "\"STOPPED\""]
           (both (apply program "intern audio/Midi_Song"
                        (concat three-note-score
                                ["let song := create Midi_Song.from_score(score)"
                                 "song.play()"
                                 "song.pause()"
                                 "song.stop()"
                                 "print(song.state())"
                                 "let other := create Midi_Song.from_score(score)"
                                 "other.play()"
                                 "other.close()"
                                 "print(other.state())"])))))
    (Thread/sleep 200)
    (is (= before (live-sequencer-threads)))))
