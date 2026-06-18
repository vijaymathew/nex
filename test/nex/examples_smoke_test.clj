(ns nex.examples-smoke-test
  "Smoke test: every example under examples/ that is self-checking (i.e. not a
   live network program) must parse, type-check, and run without error through
   the real `nex run` path (`nex.eval/eval-file`). This guards the examples
   against drift as the language and standard library evolve.

   The handful of network programs (servers/clients that bind ports or make live
   connections) are excluded — they are meant to run against a live peer, not in
   a non-interactive test."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nex.eval :as e]))

(def ^:private excluded-network-examples
  #{"echo_server.nex"
    "echo_client.nex"
    "http_server.nex"
    "http_client_to_server.nex"})

;; Relative directories the file-I/O examples create; they normally clean up
;; after themselves (delete_tree), but we remove these defensively in case an
;; example fails part-way through.
(def ^:private example-temp-dirs ["tmp_io" "tmp_bin_io"])

(defn- example-files []
  (->> (file-seq (io/file "examples"))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".nex"))
       (remove #(contains? excluded-network-examples (.getName ^java.io.File %)))
       (sort-by #(.getPath ^java.io.File %))))

(defn- root-cause [^Throwable t]
  (loop [x t] (if-let [c (.getCause x)] (recur c) x)))

(defn- run-failure
  "Run an example through eval-file (suppressing its stdout). Returns nil on
   success, or a short failure description on error."
  [^java.io.File f]
  (try
    (binding [*out* (java.io.StringWriter.)]
      (e/eval-file (.getPath f)))
    nil
    (catch Throwable t
      (let [c (root-cause t)]
        (str (.getSimpleName (class c)) ": "
             (first (str/split-lines (or (.getMessage c) ""))))))))

(defn- delete-recursively [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [child (.listFiles f)] (delete-recursively child)))
    (.delete f)))

(deftest all-self-checking-examples-run
  (testing "every non-network example parses, type-checks, and runs without error"
    (try
      (let [files (example-files)]
        (is (<= 90 (count files))
            "sanity check: the example corpus was found")
        (doseq [^java.io.File f files]
          (let [failure (run-failure f)]
            (is (nil? failure)
                (str "example failed: " (.getPath f)
                     (when failure (str "\n  " failure)))))))
      (finally
        (doseq [d example-temp-dirs]
          (delete-recursively (io/file d)))))))
