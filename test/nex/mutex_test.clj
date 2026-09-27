(ns nex.mutex-test
  "data/Mutex: exclusive, scoped access to a wrapped value across tasks
   (lib/data/mutex.nex), built entirely on Java interop (`with \"java\"`
   around `java.util.concurrent.locks.ReentrantLock`) plus `private feature`
   fields — no runtime/typechecker/compiler changes of its own.

   Behaviour is asserted on both backends."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

;; ---------------------------------------------------------------------------
;; Helpers (mirrors test/nex/byte_array_test.clj's convention)
;; ---------------------------------------------------------------------------

(defn- run-backend
  [code interpret?]
  (let [f (java.io.File/createTempFile "mutex" ".nex")]
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
            (let [f (java.io.File/createTempFile "mutex" ".nex")]
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

;; ---------------------------------------------------------------------------
;; Behaviour
;; ---------------------------------------------------------------------------

(deftest use-runs-body-with-the-wrapped-value
  (is (= ["2"]
         (both "intern data/Mutex

let counters: Mutex[Map[String, Integer]] := create Mutex.make({})

counters.use(fn(m: Map[String, Integer]) do
  m.put(\"hits\", m.try_get(\"hits\", 0) + 1)
end)
counters.use(fn(m: Map[String, Integer]) do
  m.put(\"hits\", m.try_get(\"hits\", 0) + 1)
end)
counters.use(fn(m: Map[String, Integer]) do
  print(m.get(\"hits\"))
end)"))))

(deftest exception_inside_use_releases_the_lock_and_propagates
  (is (= ["\"caught: boom\"" "[1, 2]"]
         (both "intern data/Mutex

let m: Mutex[Array[Integer]] := create Mutex.make([1])

do
  m.use(fn(a: Array[Integer]) do
    a.add(2)
    raise \"boom\"
  end)
rescue
  print(\"caught: \" + exception)
end

m.use(fn(a: Array[Integer]) do
  print(a)
end)"))))

(deftest nested-use-on-the-same-mutex-from-the-same-task-raises
  (testing "self-reentrancy is detected and raised, not silently allowed (Java's\n            synchronized) or silently deadlocked (Rust's std::sync::Mutex,\n            a plain POSIX mutex)"
    (is (raises-on-both?
         "intern data/Mutex

let m: Mutex[Integer] := create Mutex.make(0)

m.use(fn(x: Integer) do
  m.use(fn(y: Integer) do
    print(\"should not get here\")
  end)
end)"
         "Mutex.use: already held by this task"))))

(deftest concurrent-increments-through-use-do-not-lose-updates
  (testing "real mutual exclusion under contention: four tasks each increment
            a shared counter 5000 times through the same Mutex; without
            exclusion this loses updates (verified separately: the same loop
            over a bare, unprotected Array[Integer] undercounts)"
    (is (= ["20000"]
           (both "intern data/Mutex

let counter: Mutex[Array[Integer]] := create Mutex.make([0])

function bump_many(c: Mutex[Array[Integer]], n: Integer): Task do
  result := spawn do
    let i := 0
    from
    until
      i >= n
    do
      c.use(fn(a: Array[Integer]) do
        let cur := a.get(0)
        a.set(0, cur + 1)
      end)
      i := i + 1
    end
  end
end

let t1: Task := bump_many(counter, 5000)
let t2: Task := bump_many(counter, 5000)
let t3: Task := bump_many(counter, 5000)
let t4: Task := bump_many(counter, 5000)

t1.await
t2.await
t3.await
t4.await

counter.use(fn(a: Array[Integer]) do
  print(a.get(0))
end)")))))

(deftest wrapped-value-is-not-reachable-without-use
  (testing "value and lock are `private feature` fields — there is no
            lock()/unlock()/get() escape hatch"
    (is (raises-on-both?
         "intern data/Mutex

let m: Mutex[Integer] := create Mutex.make(0)
print(m.value)"
         "Undefined field"))))
