(ns nex.shebang-test
  "A `#!` first line (`#!/usr/bin/env nex`) lets a Nex file run as a script."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
            [nex.fmt :as fmt]
            [nex.parser :as p]))

(def ^:private script
  "#!/usr/bin/env nex
function boom(n: Integer): Integer
  require
    positive: n > 0
  do
    result := n
  end
print(\"hi\")
print(boom(0))
")

(defn- temp-script
  "SCRIPT written to a fresh directory as NAME."
  [name]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "nex-shebang" (make-array java.nio.file.attribute.FileAttribute 0)))
        f (io/file dir name)]
    (spit f script)
    f))

(defn- delete-script [^java.io.File f]
  (.delete f)
  (.delete (.getParentFile f)))

(deftest parser-skips-the-shebang-line
  (testing "the line is skipped and later lines keep their numbers"
    (let [ast (p/ast "#!/usr/bin/env nex\nlet x: Integer := 1\n")]
      (is (= 2 (:dbg/line (first (:statements ast)))))))
  (testing "a Windows line break after the line is fine"
    (is (= 2 (:dbg/line (first (:statements (p/ast "#!/usr/bin/env nex\r\nlet x: Integer := 1\r\n"))))))))

(deftest only-the-first-line-is-a-shebang
  (testing "the `#!` character literal still works elsewhere"
    (let [ast (p/ast "let c: Char := #!\n")]
      (is (= \! (:value (:value (first (:statements ast))))))))
  (testing "a file without the line is unchanged"
    (is (nil? (p/shebang-line "let x: Integer := 1\n")))
    (is (nil? (p/shebang-line " #!/usr/bin/env nex\n")))))

(deftest formatter-keeps-the-shebang-line
  (let [formatted (fmt/format-code "#!/usr/bin/env nex\nprint(1)\n")]
    (is (str/starts-with? formatted "#!/usr/bin/env nex\nprint(1)"))
    (is (= formatted (fmt/format-code formatted)))))

(deftest script-without-extension-runs-and-reports-lines
  (doseq [name ["greet" "greet.nex"]
          interpret? [false true]]
    (testing (str name (when interpret? " (interpreter)"))
      (let [f (temp-script name)]
        (try
          (let [code (atom nil)
                out (with-out-str
                      (reset! code (e/run-file (.getPath f) {:interpret? interpret?})))]
            (is (= 1 @code))
            (is (str/includes? out "\"hi\""))
            (is (str/includes? out "Precondition violation: positive"))
            (when-not interpret?
              (is (str/includes? out "The call at line 9"))))
          (finally (delete-script f)))))))
