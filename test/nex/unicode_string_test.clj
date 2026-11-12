(ns nex.unicode-string-test
  "Strings are sequences of characters and a Char is a Unicode code point
   (Definition B.2), on both backends, including characters outside the
   Basic Multilingual Plane (an emoji): a Char holds one whole, and a
   String's length, positions and iteration count it as one character.

   A String has two runtime forms (nex.types.runtime \"Strings\"): a plain
   java.lang.String when it holds no surrogate, else a NexWideString. The
   form is a function of the text alone, so it never shows in a program;
   these tests check that it doesn't, wherever a string is made: literals,
   concatenation, String methods, collections, Java interop, files, regex
   and JSON."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
            [nex.types.runtime :as rt]))

(defn- run-backend
  [code interpret?]
  (let [f (java.io.File/createTempFile "unicode_string" ".nex")]
    (try
      (spit f code)
      (let [out (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))]
        (is (not (str/includes? out "falling back to the tree-walking interpreter"))
            (str "compiled backend declined this program:\n" out))
        (str/split-lines (str/trim-newline out)))
      (finally (.delete f)))))

(defn- both
  "Printed output lines of LINES (joined), asserted identical on both backends."
  [& lines]
  (let [code (str/join "\n" lines)
        compiled (run-backend code false)
        interpreted (run-backend code true)]
    (is (= interpreted compiled) "compiled and interpreted output must agree")
    compiled))

;; ---------------------------------------------------------------------------
;; The runtime forms

(deftest the-form-follows-the-text
  (is (string? (rt/->nex-string "héllo")) "a BMP-only string stays a java.lang.String")
  (is (rt/nex-wide-string? (rt/->nex-string "a😀")) "a surrogate makes it wide")
  (is (string? (rt/str-substring (rt/->nex-string "hi 😀") 0 2)) "a plain piece of a wide string is plain")
  (is (string? (rt/str-concat "ab" "cd")) "plain + plain is plain")
  (is (= (rt/->nex-string "a😀") (rt/str-concat "a" (rt/->nex-string "😀")))
      "equal text is equal, however it was made")
  (is (= (hash (rt/->nex-string "a😀")) (hash (rt/str-concat "a" (rt/->nex-string "😀"))))))

(deftest breadcrumbs-find-every-character
  (let [text (apply str "hi " (repeat 70 "😀x"))
        w (rt/->nex-string text)
        cps (vec (.toArray (.codePoints ^String text)))]
    (is (= (count cps) (rt/str-length w)))
    (is (every? #(= (nth cps %) (rt/char-code (rt/str-char-at w %))) (range (count cps))))
    (is (every? #(= % (rt/str-index-of-offset w (rt/str-offset w %))) (range (inc (count cps)))))))

;; ---------------------------------------------------------------------------
;; Char

(deftest chars-are-code-points
  (is (= ["#a" "97" "#😀" "128512" "true" "true" "#😀" "true" "[#x, #😀]" "#🎉" "0"]
         (both "print(#a)"
               "print(#a.to_integer)"
               "print(#128512)"
               "print((#128512).to_integer)"
               "print(#128512 = #128512)"
               "print(#a < #b)"
               "print((128512).to_char)"
               "print((97).to_char = #a)"
               "let cs: Array[Char] := [#x, #128512]"
               "print(cs)"
               "function f(c: Char): Char do result := c end"
               "print(f(#127881))"
               "let z: Char := #nul"
               "print(z.to_integer)")))
  (testing "a Char as a field, a map key, and inside Any"
    (is (= ["128513" "1" "#z"]
           (both "class Box"
                 "  create make(c: Char) do ch := c end"
                 "  feature"
                 "    ch: Char"
                 "    next(): Char do result := (ch.to_integer + 1).to_char end"
                 "end"
                 "print((create Box.make(#128512)).next().to_integer)"
                 "let m: Map[Char, Integer] := {#a: 1}"
                 "print(m.get(#a))"
                 "let any: Any := #z"
                 "print(any)"))))
  (testing "a code point past the last one is rejected"
    (is (thrown-with-msg? Exception #"Char literal out of range"
                          (run-backend "print(#1114112)" true)))))

;; ---------------------------------------------------------------------------
;; String

(deftest strings-count-characters
  (is (= ["1" "13" "#😀" "#space" "\"😀\"" "5" "8" "6"]
         (both "print(\"😀\".length)"
               "let s := \"héllo 😀 wörld\""
               "print(s.length)"
               "print(s.char_at(6))"
               "print(s.char_at(7))"
               "print(s.substring(6, 7))"
               "print(s.substring(0, 5).length)"
               "print(s.index_of(\"w\"))"
               "print(s.index_of(\"😀\"))")))
  (testing "iteration, chars, and the functional methods"
    (is (= ["#a" "#😀" "#b" "[#a, #😀]" "[97, 128512, 98]" "\"😀b\"" "2"]
           (both "across \"a😀b\" as c do print(c) end"
                 "print(\"a😀\".chars)"
                 "print(\"a😀b\".map(fn (c) => c.to_integer))"
                 "print(\"a😀b\".filter(fn (c) => c /= #a))"
                 "print(\"a😀b\".filter(fn (c) => c /= #a).length)"))))
  (testing "a long wide string, past several breadcrumbs"
    (is (= ["203" "\"😀😀end\"" "200"]
           (both "let big := \"😀\".replicate(200) + \"end\""
                 "print(big.length)"
                 "print(big.substring(198, 203))"
                 "print(big.index_of(\"end\"))")))))

(deftest string-operations-keep-the-form-hidden
  (is (= ["\"HÉLLO 😀\"" "7" "\"abcd\"" "true" "1" "true" "[\"x\", \"y\"]" "\"😀,b\"" "\"😀😀ab\"" "4"
          "[240, 159, 152, 128]" "true" "2" "1" "true" "2" "\"String\"" "true" "2"]
         (both "print(\"héllo 😀\".to_upper)"
               "print((\"héllo \" + \"😀\").length)"
               "print(\"ab\" + \"cd\")"
               "print(\"a😀\" = \"a\" + \"😀\")"
               "let m: Map[String, Integer] := {\"😀\": 1}"
               "print(m.get(\"\" + \"😀\"))"
               "print([\"b\", \"😀\"].contains(\"😀\"))"
               "print(\"x😀y\".split(\"😀\"))"
               "print(\",\".join([\"😀\", \"b\"]))"
               "print(\"ab\".pad_start(\"😀\", 4))"
               "print(\"ab\".pad_start(\"😀\", 4).length)"
               "print(\"😀\".to_bytes)"
               "print(create String.from_bytes(\"😀\".to_bytes) = \"😀\")"
               "print((create String.from_bytes(\"😀x\".to_bytes)).length)"
               "print(#128512.to_string.length)"
               "print(#128512.to_string = \"😀\")"
               "let any: Any := \"😀!\""
               "if convert any to t: String then print(t.length) end"
               "print(type_of(any))"
               "print(\"😀\".hash = (\"\" + \"😀\").hash)"
               "let ts: Set[String] := #{\"😀\", \"😀\", \"b\"}"
               "print(ts.size)"))))

(deftest strings-in-classes-and-extensions
  (is (= ["4" "#😀" "0" "\"🎉😀 hi\"" "5" "\"HI 😀!\"" "4" "3" "5"]
         (both "class Note"
               "  create"
               "    make(t: String) do text := t end"
               "  feature"
               "    text: String"
               "    title: String"
               "    size(): Integer do result := text.length end"
               "    first(): Char do result := text.char_at(0) end"
               "    set_title(t: String) do title := t end"
               "end"
               "let n := create Note.make(\"😀 hi\")"
               "print(n.size())"
               "print(n.first())"
               "print(n.title.length)"
               "n.set_title(\"🎉\")"
               "print(n.title + n.text)"
               "print((n.title + n.text).length)"
               "extend String"
               "  feature"
               "    shout(): String do result := this.to_upper + \"!\" end"
               "    width(): Integer do result := this.length end"
               "end"
               "print(\"hi 😀\".shout())"
               "print(\"hi 😀\".width())"
               "function count_chars(s: String): Integer do"
               "  result := 0"
               "  across s as c do result := result + 1 end"
               "end"
               "print(count_chars(\"😀😀x\"))"
               "print(count_chars(\"plain\"))"))))

;; ---------------------------------------------------------------------------
;; The boundaries: Java, files, regex, JSON

(deftest strings-cross-into-java-and-back
  (is (= ["3" "#😀" "1" "true"]
         (both "import java.lang.StringBuilder"
               "import java.lang.Character"
               "with \"java\" do"
               "  let sb := create StringBuilder.make()"
               "  sb.append(\"x😀\")"
               "  sb.append(\"y\")"
               "  let out: String := sb.toString()"
               "  print(out.length)"
               "  print(out.char_at(1))"
               "  let t: String := Character.toString(128512)"
               "  print(t.length)"
               "  print(t = \"😀\")"
               "end"))))

(deftest java-calls-back-with-a-string
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "nex-unicode" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (spit (java.io.File. dir "a😀.txt") "")
      (spit (java.io.File. dir "b.txt") "")
      (is (= ["1"]
             (both "import java.io.File"
                   "import java.io.FilenameFilter"
                   "import java.util.Arrays"
                   "class Emoji_Filter"
                   "  inherit FilenameFilter"
                   "  feature"
                   "    accept(dir: File, name: String): Boolean do"
                   "      result := name.length = 6 and name.char_at(1) = #128512"
                   "    end"
                   "end"
                   "with \"java\" do"
                   (str "  let d := create File.make(\"" (.getPath dir) "\")")
                   "  print(Arrays.asList(d.list(create Emoji_Filter)).size())"
                   "end")))
      (finally
        (doseq [f (.listFiles dir)] (.delete ^java.io.File f))
        (.delete dir)))))

(deftest strings-from-files-regex-and-json
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "nex-unicode" (make-array java.nio.file.attribute.FileAttribute 0)))
        path (.getPath (java.io.File. dir "a😀.txt"))]
    (try
      (is (= ["6" "9" "1" "[\"😀\", \"🎉\"]" "[1, 1]" "\"a-b\"" "[\"a\", \"b\", \"c\"]" "{\"k\": \"v😀\"}"]
             (both "intern io/Path"
                   "intern text/Regex"
                   (str "let p := create Path.make(\"" path "\")")
                   "print(p.name().length)"
                   "p.write_text(\"x😀y\\nline2\")"
                   "print(p.read_text().length)"
                   (str "let found := 0")
                   (str "across (create Path.make(\"" (.getPath dir) "\")).list() as f do")
                   "  if f.name().char_at(1) = #128512 then found := found + 1 end"
                   "end"
                   "print(found)"
                   "let r := create Regex.compile(\"[😀🎉]\")"
                   "print(r.find_all(\"a😀b🎉c\"))"
                   "print(r.find_all(\"a😀b🎉c\").map(fn (m) => m.length))"
                   "print(r.replace(\"a😀b\", \"-\"))"
                   "print(r.split(\"a😀b🎉c\"))"
                   "print(json_parse(\"{\\\"k\\\": \\\"v😀\\\"}\"))")))
      (finally
        (doseq [f (.listFiles dir)] (.delete ^java.io.File f))
        (.delete dir)))))

(deftest an-unpaired-surrogate-is-one-character-but-not-encodable
  (testing "host text can hold one (here through an escape); it indexes as one
            character, and to_bytes refuses it rather than write `?`, so that
            from_bytes(to_bytes(s)) is always s"
    (is (= ["3" "55296" "String.to_bytes: the text holds an unpaired surrogate, which is not a character"]
           (both "let s := \"a\\u{D800}b\""
                 "print(s.length)"
                 "print(s.char_at(1).to_integer)"
                 "do print(s.to_bytes) rescue print(exception) end")))))
