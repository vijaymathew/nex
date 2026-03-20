(ns nex.compiler.jvm.compiled-repl-soak-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.repl :as compiled-repl]
            [nex.compiler.jvm.runtime :as runtime]
            [nex.repl :as repl]))

(defn- delete-tree!
  [root]
  (doseq [f (reverse (file-seq (io/file root)))]
    (.delete f)))

(defn- run-step!
  [ctx {:keys [label code load-path source-id expect-substrings forbid-substrings check]}]
  (let [output (with-out-str
                 (if load-path
                   (repl/load-file-into-repl ctx load-path)
                   (repl/eval-code ctx code (or source-id "compiled-soak"))))
        session @repl/*compiled-repl-session*]
    (is (not (str/includes? output "Error:")) (str label " should not error"))
    (doseq [fragment expect-substrings]
      (is (str/includes? output fragment)
          (str label " should include " (pr-str fragment) " in output " (pr-str output))))
    (doseq [fragment forbid-substrings]
      (is (not (str/includes? output fragment))
          (str label " should not include " (pr-str fragment) " in output " (pr-str output))))
    (when check
      (check {:ctx ctx
              :output output
              :session session}))
    ctx))

(defn- eval-step-output
  [ctx {:keys [code load-path source-id]}]
  (with-out-str
    (if load-path
      (repl/load-file-into-repl ctx load-path)
      (repl/eval-code ctx code (or source-id "compiled-soak")))))

(defn- capture-steps!
  [backend steps]
  (binding [repl/*type-checking-enabled* (atom true)
            repl/*repl-var-types* (atom {})
            repl/*repl-backend* (atom backend)
            repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
    (let [ctx (repl/init-repl-context)
                          outputs (reduce (fn [acc step]
                            (conj acc {:label (:label step)
                                       :parity-ignore-output? (:parity-ignore-output? step)
                                       :output (eval-step-output ctx step)}))
                          []
                          steps)]
      {:outputs outputs
       :ctx ctx
       :session @repl/*compiled-repl-session*
       :var-types @repl/*repl-var-types*})))

(defn- normalize-output-line
  [line]
  (let [trimmed (str/trim line)]
    (cond
      (str/blank? trimmed)
      ""

      (or (re-find #"^#<.+ object>$" trimmed)
          (re-find #"^#object\[" trimmed)
          (re-find #"^Any #object\[" trimmed)
          (re-find #"^[A-Za-z_][A-Za-z0-9_]* #<.+ object>$" trimmed))
      "<object>"

      (re-find #"^(Any|Integer|Integer64|Real|Decimal|Boolean|Char|String)\s+.+$" trimmed)
      (second (re-find #"^(?:Any|Integer|Integer64|Real|Decimal|Boolean|Char|String)\s+(.+)$" trimmed))

      :else
      trimmed)))

(defn- normalized-step-outputs
  [run]
  (mapv (fn [{:keys [label output parity-ignore-output?]}]
          {:label label
           :output (if parity-ignore-output?
                     []
                     (->> (str/split-lines output)
                          (map normalize-output-line)
                          (remove str/blank?)
                          vec))})
        (:outputs run)))

(defn- run-steps!
  [steps]
  (binding [repl/*type-checking-enabled* (atom true)
            repl/*repl-var-types* (atom {})
            repl/*repl-backend* (atom :compiled)
            repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
    (let [ctx (repl/init-repl-context)]
      (reduce run-step! ctx steps))))

(defn- mixed-session-steps
  [tmp-root]
  [{:label "let x"
    :code "let x: Integer := 10"
    :expect-substrings ["10"]}
   {:label "let y"
    :code "let y: Real := 20.5"
    :expect-substrings ["20.5"]}
   {:label "mixed arithmetic"
    :code "x + y"
    :expect-substrings ["30.5"]}
   {:label "closure value"
    :code "let inc := fn (n: Integer): Integer do
  result := n + 1
end"}
   {:label "closure call"
    :code "inc(41)"
    :expect-substrings ["42"]}
   {:label "statement-tail function"
    :code "function choose(flag: Boolean): String
do
  if flag then
    result := \"yes\"
  else
    result := \"no\"
  end
end"}
   {:label "statement-tail function call"
    :code "choose(false)"
    :expect-substrings ["\"no\""]}
   {:label "deopt intern io/Path"
    :code "intern io/Path"}
   {:label "deopt path construction"
    :code (str "let root: Path := create Path.make(\"" tmp-root "\")")
    :check (fn [{:keys [session]}]
             (is (= "Path" (runtime/state-get-type (:state session) "root"))))}
   {:label "compiled after deopt"
    :code "let z: Integer := 5"
    :expect-substrings ["5"]}
   {:label "compiled arithmetic after deopt"
    :code "z + 1"
    :expect-substrings ["6"]}
   {:label "class definition"
    :code "class Point
create
  make(px, py: Integer) do
    this.x := px
    this.y := py
  end
feature
  x: Integer
  y: Integer

  move(dx, dy: Integer)
  do
    x := x + dx
    y := y + dy
  end
end"}
   {:label "instantiate point"
    :code "let pt: Point := create Point.make(1, 2)"
    :check (fn [{:keys [session]}]
             (is (= "Point" (runtime/state-get-type (:state session) "pt")))
             (is (contains? @(:class-asts session) "Point")))}
   {:label "mutate point"
    :code "pt.move(3, 4)"}
   {:label "read point state"
    :code "pt.x + pt.y"
    :expect-substrings ["10"]}
   {:label "final session check"
    :code "x + z"
    :expect-substrings ["15"]
    :check (fn [{:keys [session]}]
             (is (= 10 (runtime/state-get-value (:state session) "x")))
             (is (= "Integer" (runtime/state-get-type (:state session) "x")))
             (is (= "Real" (runtime/state-get-type (:state session) "y")))
             (is (= "Integer" (runtime/state-get-type (:state session) "z")))
             (is (contains? @(:function-asts session) "choose")))}])

(defn- load-object-method-steps
  [tmp-dir main-file]
  [{:label "load file"
    :load-path (.getPath main-file)}
   {:label "instantiate loaded counter"
    :code "let c: Counter := create Counter.make(1)"}
   {:label "first method call"
    :code "do
  c.add(1)
end"
    :parity-ignore-output? true}
   {:label "field after first method call"
    :code "c.value"
    :expect-substrings ["2"]}
   {:label "forced deopt via io/Path"
    :code "intern io/Path"}
   {:label "deopted Path create"
    :code (str "let object_root: Path := create Path.make(\"" (.getAbsolutePath tmp-dir) "\")")}
   {:label "second method call after deopt"
    :code "do
  c.add(3)
end"
    :parity-ignore-output? true}
   {:label "field after second method call"
    :code "c.value"
    :expect-substrings ["5"]}
   {:label "delta binding"
    :code "let delta: Integer := 2"
    :expect-substrings ["2"]}
   {:label "third method call after later compiled step"
    :code "do
  c.add(delta)
end"
    :parity-ignore-output? true}
   {:label "field read after repeated calls"
    :code "c.value"
    :expect-substrings ["7"]}])

(defn- import-intern-steps
  [tmp-dir main-file]
  [{:label "import java builder"
    :code "import java.lang.StringBuilder"}
   {:label "intern local counter"
    :code "intern Counter"
    :source-id (.getPath main-file)
    :check (fn [{:keys [session]}]
             (is (= "java.lang.StringBuilder"
                    (:qualified-name (first @(:import-asts session)))))
             (is (= "Counter" (:class-name (first @(:intern-asts session)))))
             (is (contains? @(:class-asts session) "Counter")))}
   {:label "instantiate imported builder"
    :code "let sb: StringBuilder := create StringBuilder"
    :check (fn [{:keys [session]}]
             (is (= "StringBuilder" (runtime/state-get-type (:state session) "sb")))
             (is (= "java.lang.StringBuilder"
                    (:qualified-name (first @(:import-asts session))))))}
   {:label "append initial text"
    :code "do
  sb.append(\"ab\")
end"
    :parity-ignore-output? true}
   {:label "instantiate interned counter"
    :code "let c: Counter := create Counter.make(1)"
    :check (fn [{:keys [session]}]
             (is (= "Counter" (runtime/state-get-type (:state session) "c")))
             (is (contains? @(:class-asts session) "Counter"))
             (is (= "Counter" (:class-name (first @(:intern-asts session))))))}
   {:label "call interned method before deopt"
    :code "do
  c.add(2)
end"
    :parity-ignore-output? true}
   {:label "builder length before deopt"
    :code "sb.length()"
    :expect-substrings ["2"]}
   {:label "counter value before deopt"
    :code "c.value"
    :expect-substrings ["3"]}
   {:label "forced deopt via io/Path"
    :code "intern io/Path"}
   {:label "deopted path binding"
    :code (str "let import_root: Path := create Path.make(\"" (.getAbsolutePath tmp-dir) "\")")
    :check (fn [{:keys [session]}]
             (is (= "Path" (runtime/state-get-type (:state session) "import_root"))))}
   {:label "reuse imported builder after deopt"
    :code "do
  sb.append(\"c\")
end"
    :parity-ignore-output? true}
   {:label "reuse interned method after deopt"
    :code "do
  c.add(3)
end"
    :parity-ignore-output? true}
   {:label "builder length after deopt"
    :code "sb.length()"
    :expect-substrings ["3"]}
   {:label "counter value after deopt"
    :code "c.value"
    :expect-substrings ["6"]}
   {:label "final import+intern metadata check"
    :code "c.value + sb.length()"
    :expect-substrings ["9"]
    :check (fn [{:keys [session]}]
             (is (= "StringBuilder" (runtime/state-get-type (:state session) "sb")))
             (is (= "Counter" (runtime/state-get-type (:state session) "c")))
             (is (= "Path" (runtime/state-get-type (:state session) "import_root")))
             (is (contains? @(:class-asts session) "Counter"))
             (is (= "java.lang.StringBuilder"
                    (:qualified-name (first @(:import-asts session)))))
             (is (= "Counter" (:class-name (first @(:intern-asts session))))))}])

(defn- load-intern-closure-concurrency-steps
  [tmp-dir main-file]
  [{:label "load mixed module"
    :load-path (.getPath main-file)
    :check (fn [{:keys [session]}]
             (is (contains? @(:class-asts session) "Counter"))
             (is (contains? @(:function-asts session) "loaded_add")))}
   {:label "captured closure over loaded function"
    :code "let add_base: Function := fn (n: Integer): Integer do
  result := loaded_add(n) + 5
end"}
   {:label "captured closure call before deopt"
    :code "add_base(1)"
    :expect-substrings ["16"]}
   {:label "channel setup"
    :code "let ch: Channel[Integer] := create Channel.with_capacity(1)"
    :check (fn [{:keys [session]}]
             (is (= {:base-type "Channel" :type-args ["Integer"]}
                    (runtime/state-get-type (:state session) "ch"))))}
   {:label "send closure result"
    :code "ch.send(add_base(2))"}
   {:label "select closure result"
    :code "select
  when ch.receive() as msg then
    print(msg)
  timeout 100 then
    print(0)
end"
    :expect-substrings ["17"]}
   {:label "spawn task using loaded function"
    :code "let worker: Task[Integer] := spawn do
  result := loaded_add(8)
end"
    :check (fn [{:keys [session]}]
             (is (= {:base-type "Task" :type-args ["Integer"]}
                    (runtime/state-get-type (:state session) "worker"))))}
   {:label "await task before deopt"
    :code "await_any([worker])"
    :expect-substrings ["18"]}
   {:label "forced deopt via io/Path"
    :code "intern io/Path"}
   {:label "path after deopt"
    :code (str "let mixed_root: Path := create Path.make(\"" (.getAbsolutePath tmp-dir) "\")")
    :check (fn [{:keys [session]}]
             (is (= "Path" (runtime/state-get-type (:state session) "mixed_root"))))}
   {:label "loaded class after deopt"
    :code "let c: Counter := create Counter.make(1)"
    :check (fn [{:keys [session]}]
             (is (= "Counter" (runtime/state-get-type (:state session) "c"))))}
   {:label "method call on loaded class after deopt"
    :code "do
  c.add(13)
end"
    :parity-ignore-output? true}
   {:label "state after mixed deopt recovery"
    :code "c.value"
    :expect-substrings ["14"]}
   {:label "final mixed metadata check"
    :code "type_of(worker)"
    :expect-substrings ["\"Task\""]
    :check (fn [{:keys [session]}]
             (is (contains? @(:class-asts session) "Counter"))
             (is (contains? @(:function-asts session) "loaded_add"))
             (is (= "Counter" (runtime/state-get-type (:state session) "c")))
             (is (= "Path" (runtime/state-get-type (:state session) "mixed_root")))
             (is (= {:base-type "Task" :type-args ["Integer"]}
                    (runtime/state-get-type (:state session) "worker"))))}])

(deftest compiled-repl-progressive-mixed-session-soak-test
  (testing "compiled REPL survives a long mixed session with deopt/reopt cycles and state checks"
    (let [tmp-root (.getAbsolutePath (io/file (System/getProperty "java.io.tmpdir")
                                              (str "nex-compiled-soak-mixed-" (System/nanoTime))))]
      (run-steps! (mixed-session-steps tmp-root)))))

(deftest compiled-repl-load-and-module-soak-test
  (testing "compiled REPL survives :load plus later deopt/reopt and keeps loaded definitions coherent"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-compiled-soak-load-" (System/nanoTime)))
          a-file (io/file tmp-dir "A.nex")
          main-file (io/file tmp-dir "main.nex")]
      (try
        (.mkdirs tmp-dir)
        (spit a-file "class A
feature
  answer(): Integer
  do
    result := 41
  end
end")
        (spit main-file "intern A

function from_file(n: Integer): Integer
do
  result := n + 1
end")
        (run-steps!
         [{:label "load file"
           :load-path (.getPath main-file)
           :check (fn [{:keys [session]}]
                    (is (contains? @(:class-asts session) "A"))
                    (is (contains? @(:function-asts session) "from_file")))}
          {:label "call loaded function"
           :code "from_file(41)"
           :expect-substrings ["42"]}
          {:label "instantiate loaded class"
           :code "let a: A := create A"
           :check (fn [{:keys [session]}]
                    (is (= "A" (runtime/state-get-type (:state session) "a"))))}
          {:label "call loaded class method"
           :code "a.answer()"
           :expect-substrings ["41"]}
          {:label "forced deopt via io/Path"
           :code "intern io/Path"}
          {:label "deopted Path create"
           :code (str "let loaded_root: Path := create Path.make(\"" (.getAbsolutePath tmp-dir) "\")")
           :check (fn [{:keys [session]}]
                    (is (= "Path" (runtime/state-get-type (:state session) "loaded_root"))))}
          {:label "compiled again after module deopt"
           :code "let post_load_counter: Integer := 2"
           :expect-substrings ["2"]}
          {:label "loaded definitions still work"
           :code "from_file(post_load_counter)"
           :expect-substrings ["3"]}
          {:label "final metadata check"
           :code "from_file(post_load_counter)"
           :expect-substrings ["3"]
           :check (fn [{:keys [session]}]
                    (is (contains? @(:class-asts session) "A"))
                    (is (contains? @(:function-asts session) "from_file"))
                    (is (= "Integer" (runtime/state-get-type (:state session) "post_load_counter"))))}])
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compiled-repl-redefinition-and-concurrency-soak-test
  (testing "compiled REPL preserves redefinitions and concurrent state across deopt/reopt boundaries"
    (let [tmp-root (.getAbsolutePath (io/file (System/getProperty "java.io.tmpdir")
                                              (str "nex-compiled-soak-concurrency-" (System/nanoTime))))]
      (run-steps!
       [{:label "define inc v1"
         :code "function inc(n: Integer): Integer
do
  result := n + 1
end"}
        {:label "call inc v1"
         :code "inc(40)"
         :expect-substrings ["41"]}
        {:label "redefine inc v2"
         :code "function inc(n: Integer): Integer
do
  result := n + 2
end"}
        {:label "call inc v2"
         :code "inc(40)"
         :expect-substrings ["42"]}
        {:label "channel setup"
         :code "let ch: Channel[String] := create Channel.with_capacity(1)"
         :check (fn [{:keys [session]}]
                  (is (= {:base-type "Channel" :type-args ["String"]}
                         (runtime/state-get-type (:state session) "ch"))))}
        {:label "channel send"
         :code "ch.send(\"ready\")"}
        {:label "select receive"
         :code "select
  when ch.receive() as msg then
    print(msg)
  timeout 100 then
    print(\"timeout\")
end"
         :expect-substrings ["\"ready\""]}
        {:label "spawn task"
         :code "let worker: Task[Integer] := spawn do
  result := 7
end"
         :check (fn [{:keys [session]}]
                  (is (= {:base-type "Task" :type-args ["Integer"]}
                         (runtime/state-get-type (:state session) "worker"))))}
        {:label "await any"
         :code "await_any([worker])"
         :expect-substrings ["7"]}
        {:label "force deopt via io/Path again"
         :code "intern io/Path"}
        {:label "deopted path binding"
         :code (str "let sync_root: Path := create Path.make(\"" tmp-root "\")")
         :check (fn [{:keys [session]}]
                  (is (= "Path" (runtime/state-get-type (:state session) "sync_root"))))}
        {:label "redefined function survives after deopt"
         :code "inc(1)"
         :expect-substrings ["3"]}
        {:label "final state and metadata check"
         :code "type_of(worker)"
         :expect-substrings ["\"Task\""]
         :check (fn [{:keys [session]}]
                  (is (contains? @(:function-asts session) "inc"))
                  (is (= {:base-type "Task" :type-args ["Integer"]}
                         (runtime/state-get-type (:state session) "worker"))))}]))))

(deftest compiled-repl-parity-mixed-session-test
  (testing "compiled and interpreter backends produce the same user-visible outputs for a mixed progressive session"
    (let [tmp-root (.getAbsolutePath (io/file (System/getProperty "java.io.tmpdir")
                                              (str "nex-compiled-soak-parity-" (System/nanoTime))))
          steps (mapv #(select-keys % [:label :code :load-path :source-id])
                      (mixed-session-steps tmp-root))
          interpreter-run (capture-steps! :interpreter steps)
          compiled-run (capture-steps! :compiled steps)]
      (is (= (mapv :label (:outputs interpreter-run))
             (mapv :label (:outputs compiled-run))))
      (is (= (normalized-step-outputs interpreter-run)
             (normalized-step-outputs compiled-run)))
      (is (= "Integer" (get (:var-types interpreter-run) "x")))
      (is (= (get (:var-types interpreter-run) "x")
             (get (:var-types compiled-run) "x")))
      (is (= (get (:var-types interpreter-run) "z")
             (get (:var-types compiled-run) "z"))))))

(deftest compiled-repl-parity-load-object-method-session-test
  (testing "compiled and interpreter backends agree on repeated object method calls across later deopts after :load"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-compiled-soak-load-object-" (System/nanoTime)))
          counter-file (io/file tmp-dir "Counter.nex")
          main-file (io/file tmp-dir "main.nex")]
      (try
        (.mkdirs tmp-dir)
        (spit counter-file "class Counter
create
  make(v: Integer) do
    this.value := v
  end
feature
  value: Integer

  add(n: Integer): Integer
  do
    this.value := this.value + n
    result := this.value
  end
end")
        (spit main-file "intern Counter")
        (let [steps (mapv #(select-keys % [:label :code :load-path :source-id :parity-ignore-output?])
                          (load-object-method-steps tmp-dir main-file))
              interpreter-run (capture-steps! :interpreter steps)
              compiled-run (capture-steps! :compiled steps)]
          (is (= (mapv :label (:outputs interpreter-run))
                 (mapv :label (:outputs compiled-run))))
          (is (= (normalized-step-outputs interpreter-run)
                 (normalized-step-outputs compiled-run)))
          (is (= "Counter" (get (:var-types interpreter-run) "c")))
          (is (= (get (:var-types interpreter-run) "c")
                 (get (:var-types compiled-run) "c"))))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compiled-repl-load-object-method-after-deopt-soak-test
  (testing "compiled REPL keeps loaded objects usable across repeated method calls after later deopts"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-compiled-soak-load-object-only-" (System/nanoTime)))
          counter-file (io/file tmp-dir "Counter.nex")
          main-file (io/file tmp-dir "main.nex")]
      (try
        (.mkdirs tmp-dir)
        (spit counter-file "class Counter
create
  make(v: Integer) do
    this.value := v
  end
feature
  value: Integer

  add(n: Integer): Integer
  do
    this.value := this.value + n
    result := this.value
  end
end")
        (spit main-file "intern Counter")
        (run-steps!
         (conj (vec (load-object-method-steps tmp-dir main-file))
               {:label "final loaded object state check"
                :code "c.value"
                :expect-substrings ["7"]
                :check (fn [{:keys [session]}]
                         (is (= "Counter" (runtime/state-get-type (:state session) "c")))
                         (is (= "Integer" (runtime/state-get-type (:state session) "delta")))
                         (is (contains? @(:class-asts session) "Counter")))}))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compiled-repl-import-and-intern-after-deopt-soak-test
  (testing "compiled REPL reuses imported Java classes and interned local classes after a later deopt"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-compiled-soak-import-intern-" (System/nanoTime)))
          counter-file (io/file tmp-dir "Counter.nex")
          main-file (io/file tmp-dir "main.nex")]
      (try
        (.mkdirs tmp-dir)
        (spit counter-file "class Counter
create
  make(v: Integer) do
    this.value := v
  end
feature
  value: Integer

  add(n: Integer): Integer
  do
    this.value := this.value + n
    result := this.value
  end
end")
        (spit main-file "intern Counter")
        (run-steps! (import-intern-steps tmp-dir main-file))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compiled-repl-load-intern-closure-concurrency-soak-test
  (testing "compiled REPL keeps loaded interned definitions coherent across closures, concurrency, and a later deopt"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-compiled-soak-load-intern-closure-" (System/nanoTime)))
          counter-file (io/file tmp-dir "Counter.nex")
          main-file (io/file tmp-dir "main.nex")]
      (try
        (.mkdirs tmp-dir)
        (spit counter-file "class Counter
create
  make(v: Integer) do
    this.value := v
  end
feature
  value: Integer

  add(n: Integer): Integer
  do
    this.value := this.value + n
    result := this.value
  end
end")
        (spit main-file "intern Counter

function loaded_add(n: Integer): Integer
do
  result := n + 10
end")
        (run-steps! (load-intern-closure-concurrency-steps tmp-dir main-file))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))
