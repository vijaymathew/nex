(ns nex.compiled-collection-sharing-test
  "Maps and sets are shared, not copied, when stored in a collection. The
   compiled backend runs every Map/Set it stores (and every `convert` result)
   through portable-value->compiled, which is meant to rebuild only the
   interpreter's portable tagged form. It also matched the compiled backend's
   own HashMap/LinkedHashSet and rebuilt those, so after `xs.add(m)` a later
   `m.set(...)` did not show in `xs`. A tree of Map[String, Any] nodes built
   with add lost the amount of every newly created node."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- run-file [code interpret?]
  (let [f (java.io.File/createTempFile "sharing" ".nex")]
    (try
      (spit f code)
      (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))
      (catch Throwable t
        (str "THROWN " (class t) " " (.getMessage t)))
      (finally (.delete f)))))

(def ^:private program
  (str "let xs: Array[Map[String, Integer]] := []\n"
       "let m: Map[String, Integer] := {\"n\": 0}\n"
       "xs.add(m)\n"
       "m.set(\"n\", 1)\n"
       "print(xs)\n"
       "let outer: Map[String, Map[String, Integer]] := {}\n"
       "let inner: Map[String, Integer] := {}\n"
       "outer.set(\"k\", inner)\n"
       "inner.set(\"x\", 2)\n"
       "print(outer)\n"
       "let sets: Array[Set[Integer]] := []\n"
       "let s: Set[Integer] := #{}\n"
       "sets.add(s)\n"
       "s.add(3)\n"
       "print(sets)\n"
       ;; through convert, as a tree of Map[String, Any] nodes is built
       "let node: Map[String, Any] := {\"kids\": []}\n"
       "if convert node.get(\"kids\") to kids: Array[Map[String, Any]] then\n"
       "  let kid: Map[String, Any] := {\"n\": 0}\n"
       "  kids.add(kid)\n"
       "  kid.set(\"n\", 4)\n"
       "end\n"
       "print(node)\n"
       ;; a converted map is the same map
       "let holder: Map[String, Any] := {\"m\": m}\n"
       "if convert holder.get(\"m\") to back: Map[String, Integer] then\n"
       "  back.set(\"n\", 5)\n"
       "end\n"
       "print(m)\n"
       ;; a portable map from json_parse is still converted and usable
       "if convert json_parse(\"{\\\"a\\\": {\\\"b\\\": 1}}\") to j: Map[String, Any] then\n"
       "  let ys: Array[Map[String, Any]] := []\n"
       "  ys.add(j)\n"
       "  j.set(\"c\", 2)\n"
       "  print(ys)\n"
       "end\n"))

(def ^:private expected
  ["[{\"n\": 1}]"
   "{\"k\": {\"x\": 2}}"
   "[#{3}]"
   "{\"kids\": [{\"n\": 4}]}"
   "{\"n\": 5}"
   "[{\"a\": {\"b\": 1}, \"c\": 2}]"])

(deftest stored-maps-and-sets-are-shared-on-both-backends
  (doseq [interpret? [false true]]
    (testing (if interpret? "interpreter" "compiled")
      (is (= expected (str/split-lines (run-file program interpret?)))))))
