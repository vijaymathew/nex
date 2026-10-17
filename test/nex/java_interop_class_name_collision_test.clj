(ns nex.java-interop-class-name-collision-test
  "A Java object reached through `with \"java\"` interop whose class has the
   same simple name as a class in the Nex program (javax.sound.midi.Track beside
   a Nex class Track, as in lib/audio) must still be called as a Java object. Both backends used to
   recognise compiled Nex objects by simple class name alone, so such an object
   was taken for a Nex instance and the call failed. Only classes the compiler
   generated (packages nex.file.* and nex.repl.*) count as Nex objects now."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e])
  (:import (java.io File)))

(defn- run-backend
  [code interpret?]
  (let [f (File/createTempFile "collision" ".nex")]
    (try
      (spit f code)
      (str/split-lines (str/trim-newline
                        (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))))
      (finally (.delete f)))))

(def program
  "import javax.sound.midi.Sequence

class Track
feature
  label: String
create
  make() do
    label := \"nex\"
  end
end

let mine := create Track.make()
print(mine.label)
let events: Integer := 0
with \"java\" do
  let java_track: Any := Sequence.new(Sequence.PPQ, 24).createTrack()
  events := java_track.size()
end
print(events)")

(deftest java-object-sharing-a-nex-class-name-is-called-as-java
  (testing "compiled backend"
    (is (= ["\"nex\"" "1"] (run-backend program false))))
  (testing "interpreter"
    (is (= ["\"nex\"" "1"] (run-backend program true)))))
