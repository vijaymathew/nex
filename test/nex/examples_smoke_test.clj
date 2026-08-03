(ns nex.examples-smoke-test
  "Smoke test: every example under examples/ that is self-checking (i.e. not a
   live network program) must parse, type-check, and run without error through
   the real `nex run` path (`nex.eval/eval-file`). This guards the examples
   against drift as the language and standard library evolve.

   The handful of network programs (servers/clients that bind ports or make live
   connections) are excluded — they are meant to run against a live peer, not in
   a non-interactive test. examples/contracts_at_work/ is a work-in-progress
   sample-app series with real CLI tools that call System.exit on a
   missing-argument error path (fatal to an in-process smoke test that gives
   every example zero args), live server/GUI mains meant to be launched by hand,
   and known-incomplete spikes (spike_swing/) — none of those run here. But each
   project's own checks.nex is a self-contained, self-checking regression suite
   (verified individually: pure computation in 01-04, loopback-only sockets/HTTP
   in 06/07, a headless Swing check in 08, and scratch-directory I/O under
   NEX_USER_DIR in 05/09 — see ci.yml's NEX_USER_DIR env var), so those run
   alongside everything else."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nex.eval :as e]))

(def ^:private excluded-network-examples
  #{"echo_server.nex"
    "echo_client.nex"
    "http_server.nex"
    "http_client_to_server.nex"})

(defn- excluded-contracts-at-work-path?
  "True for anything under contracts_at_work/ except a checks.nex — CLI mains,
   server/GUI mains, and spike_swing/'s known-incomplete spikes."
  [^java.io.File f]
  (and (str/includes? (.getPath f) (str java.io.File/separator "contracts_at_work" java.io.File/separator))
       (not= (.getName f) "checks.nex")))

;; Relative directories the file-I/O examples create; they normally clean up
;; after themselves (delete_tree), but we remove these defensively in case an
;; example fails part-way through. tmp_dupcheck/tmp_radar_check come from
;; contracts_at_work/05_dup_finder and .../09_radar's checks.nex, created
;; under NEX_USER_DIR (see ci.yml).
(def ^:private example-temp-dirs ["tmp_io" "tmp_bin_io" "tmp_dupcheck" "tmp_radar_check"])

(defn- example-files []
  (->> (file-seq (io/file "examples"))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".nex"))
       (remove #(contains? excluded-network-examples (.getName ^java.io.File %)))
       (remove excluded-contracts-at-work-path?)
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
        (is (<= 100 (count files))
            "sanity check: the example corpus was found")
        (doseq [^java.io.File f files]
          (let [failure (run-failure f)]
            (is (nil? failure)
                (str "example failed: " (.getPath f)
                     (when failure (str "\n  " failure)))))))
      (finally
        (doseq [d example-temp-dirs]
          (delete-recursively (io/file d)))))))
