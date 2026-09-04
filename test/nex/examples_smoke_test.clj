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
   NEX_USER_DIR in 05/09), so those run alongside everything else. The
   NEX_USER_DIR-dependent pair can't run in-process like the rest — see
   run-failure-with-nex-user-dir below — because Process.getenv reads the real
   OS environment, which is fixed for this JVM's whole lifetime; there is no
   way to make eval-file itself see a NEX_USER_DIR the test's own caller never
   set."
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
;; under NEX_USER_DIR (see run-failure-with-nex-user-dir).
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

(defn- needs-nex-user-dir?
  "Whether an example's own source reads NEX_USER_DIR (today: 05_dup_finder and
   09_radar's checks.nex, for their scratch directories) — detected from the
   source itself, not a hardcoded path list, so a future example gains the
   same treatment automatically instead of failing this same way again."
  [^java.io.File f]
  (str/includes? (slurp f) "NEX_USER_DIR"))

(defn- run-failure-with-nex-user-dir
  "Like run-failure, but for an example that reads NEX_USER_DIR: run it in a
   fresh subprocess with that env var set explicitly (mirroring what `bin/nex`
   exports before running a script), reusing this JVM's already-resolved
   classpath so it doesn't re-run dependency resolution."
  [^java.io.File f]
  (let [pb (ProcessBuilder. ^"[Ljava.lang.String;"
            (into-array String
                        ["java" "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-m" "nex.eval" (.getPath f)]))]
    (.put (.environment pb) "NEX_USER_DIR" (System/getProperty "user.dir"))
    (.redirectErrorStream pb true)
    (let [proc (.start pb)
          output (slurp (.getInputStream proc))
          code (.waitFor proc)]
      (when-not (zero? code)
        (str "subprocess exited " code ": " (first (str/split-lines output)))))))

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
          (let [failure (if (needs-nex-user-dir? f)
                          (run-failure-with-nex-user-dir f)
                          (run-failure f))]
            (is (nil? failure)
                (str "example failed: " (.getPath f)
                     (when failure (str "\n  " failure)))))))
      (finally
        (doseq [d example-temp-dirs]
          (delete-recursively (io/file d)))))))
