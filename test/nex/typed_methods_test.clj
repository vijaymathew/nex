(ns nex.typed-methods-test
  "A routine compiles to a typed method and a boxed wrapper
   (nex.compiler.jvm.emit/emit-typed-routine-methods!). A value passed in or
   out of a typed method is the caller's value itself: a Map or Set must not
   be converted on the way, since a converted copy would lose the updates
   made through it. Every case must print the same on both backends."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- both
  [code]
  (let [f (java.io.File/createTempFile "typed_methods" ".nex")]
    (try
      (spit f code)
      (let [run #(str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) %))))
            compiled (run {})]
        (is (= (run {:interpret? true}) compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(deftest closure-updates-a-map-it-is-passed-test
  (is (= ["2"]
         (both "class Holder [T]
create
  make(initial: T) do value := initial end
feature
  value: T
  use(body: Function(T): Void): Void do body(value) end
end
let h: Holder[Map[String, Integer]] := create Holder.make({})
h.use(fn(m: Map[String, Integer]) do m.put(\"hits\", m.try_get(\"hits\", 0) + 1) end)
h.use(fn(m: Map[String, Integer]) do m.put(\"hits\", m.try_get(\"hits\", 0) + 1) end)
h.use(fn(m: Map[String, Integer]) do print(m.get(\"hits\")) end)"))))

(deftest routine-updates-a-map-or-set-it-is-passed-test
  (is (= ["2" "2"]
         (both "class Filler
feature
  fill(m: Map[String, Integer]) do m.put(\"a\", 1) end
  mark(s: Set[Integer]) do s.add(7) end
  both(m: Map[String, Integer], s: Set[Integer]) do
    fill(m)
    m.put(\"b\", 2)
    mark(s)
    s.add(8)
  end
end
let f: Filler := create Filler
let m: Map[String, Integer] := {}
let s: Set[Integer] := #{}
f.both(m, s)
print(m.size)
print(s.size)"))))

(deftest caller-updates-a-map-a-routine-returns-test
  (is (= ["1"]
         (both "class Store
create make do items := {} end
feature
  items: Map[String, Integer]
  table: Map[String, Integer] do result := items end
  count: Integer do result := items.size end
end
let st: Store := create Store.make
st.table.put(\"k\", 5)
print(st.count)"))))

(deftest inherited-routine-updates-a-map-it-is-passed-test
  (is (= ["1"]
         (both "class Base
feature
  fill(m: Map[String, Integer]) do m.put(\"a\", 1) end
end
class Child
inherit Base
end
let c: Child := create Child
let m: Map[String, Integer] := {}
c.fill(m)
print(m.size)"))))
