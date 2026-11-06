(ns nex.open-features-test
  "Open features: `extend Target feature ... end` (docs/proposals/open-features.md).

   1. An extension adds routines to a class from outside its declaration; they
      are ordinary features on both backends: dynamic dispatch, defaults,
      redefinition in heirs, attributes answering an extended query, calls
      through a constrained generic.
   2. Contracts declared in an extension are inherited by every
      implementation, and the target's invariant is checked around extended
      routines.
   3. A deferred extended feature must be implemented by every concrete heir in
      the program; the error names the extension.
   4. Conflicts, restrictions and visibility: an extension cannot replace a
      class's own feature, add a feature another extension added, add state,
      or see the target's private features.
   5. Across modules: a program combining a module that adds an operation with
      one that adds a variant must supply the missing implementation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
            [nex.parser :as p]))

(defn- run-file [path opts]
  (str/split-lines (str/trim-newline (with-out-str (e/eval-file path opts)))))

(defn- run [code opts]
  (let [f (java.io.File/createTempFile "open_features" ".nex")]
    (try
      (spit f code)
      (run-file (.getPath f) opts)
      (finally (.delete f)))))

(defn- both
  "Printed output of CODE, asserted identical on both backends."
  [code]
  (let [compiled (run code {})]
    (is (= (run code {:interpret? true}) compiled) "compiled and interpreted output must agree")
    compiled))

(defn- error-message [code]
  (try (run code {}) nil
       (catch Exception ex (.getMessage ex))))

(def ^:private shapes
  "deferred class Shape
end

class Circle
  inherit Shape
  create make(r: Real) do radius := r end
  feature
    radius: Real
  private feature
    secret: Integer do result := 7 end
end

class Rect
  inherit Shape
  create make(w, h: Real) do width := w height := h end
  feature
    width: Real
    height: Real
end
")

(def ^:private area-extensions
  "extend Shape
feature
  area(): Real
    deferred
    ensure non_negative: result >= 0.0
    end

  describe: String do
    result := \"shape with area \" + area.to_string
  end
end

extend Circle
feature
  area(): Real do result := 3.0 * radius * radius end
end

extend Rect
feature
  area(): Real do result := width * height end
  describe: String do result := \"rectangle\" end
end
")

(deftest extended-features-are-ordinary-features-test
  (testing "dynamic dispatch, a default body, and a redefinition in an heir"
    (is (= ["3.0" "\"shape with area 3.0\"" "6.0" "\"rectangle\""]
           (both (str shapes area-extensions
                      "let xs: Array[Shape] := [create Circle.make(1.0), create Rect.make(2.0, 3.0)]
across xs as s do
  print(s.area)
  print(s.describe)
end
")))))
  (testing "an attribute answers an extended deferred query"
    (is (= ["4.0"]
           (both (str shapes area-extensions
                      "class Tile
  inherit Shape
  create make(a: Real) do area := a end
  feature area: Real
end

let s: Shape := create Tile.make(4.0)
print(s.area)
")))))
  (testing "a call through a constrained generic reaches the extended feature"
    (is (= ["9.0"]
           (both (str shapes area-extensions
                      "function total[T -> Shape](xs: Array[T]): Real do
  result := 0.0
  across xs as x do result := result + x.area end
end

let xs: Array[Shape] := [create Circle.make(1.0), create Rect.make(2.0, 3.0)]
print(total(xs))
")))))
  (testing "an extension of a generic class restates its parameters"
    (is (= ["true" "false"]
           (both "class Box [T]
  create make(v: T) do value := v end
  feature value: T
end

extend Box [T]
feature
  same(other: Box [T]): Boolean do result := value = other.value end
end

let a: Box[Integer] := create Box[Integer].make(1)
print(a.same(create Box[Integer].make(1)))
print(a.same(create Box[Integer].make(2)))
"))))
  (testing "`extend` is still an ordinary name"
    (is (= ["3"] (both "let extend := 3\nprint(extend)\n")))))

(deftest extended-contracts-and-invariants-test
  (testing "an implementation inherits the extension's postcondition"
    (let [out (both (str shapes area-extensions
                         "class Hole
  inherit Shape
  create make do end
end

extend Hole
feature
  area(): Real do result := -1.0 end
end

do
  print((create Hole.make).area)
rescue
  print(exception.to_string)
end
"))]
      (is (= 1 (count out)))
      (is (str/includes? (first out) "non_negative"))))
  (testing "the target's invariant is checked when an extended routine returns"
    (let [out (both "class Counter
  create make do count := 1 end
  feature
    count: Integer
    set_count(n: Integer) do count := n end
  invariant
    non_negative: count >= 0
end

extend Counter
feature
  overdraw do set_count(-1) end
end

do
  let c := create Counter.make
  c.overdraw
  print(\"not reached\")
rescue
  print(exception.to_string)
end
")]
      (is (= 1 (count out)))
      (is (str/includes? (first out) "non_negative")))))

(deftest coverage-test
  (testing "a concrete heir without the deferred feature is rejected, naming the extension"
    (let [msg (error-message (str shapes area-extensions
                                  "class Triangle
  inherit Shape
  create make do end
end
"))]
      (is (some? msg))
      (is (str/includes? msg "Class 'Triangle' does not implement deferred feature 'area'"))
      (is (str/includes? msg "declared by 'extend Shape'"))))
  (testing "a deferred heir is exempt"
    (is (= ["\"ok\""]
           (both (str shapes area-extensions
                      "deferred class Polygon
  inherit Shape
end
print(\"ok\")
")))))
  (testing "a class's own routine adopts an extension's deferred feature and its contract"
    (let [out (both "deferred class Shape
end

class Dot
  inherit Shape
  create make do end
  feature
    area(): Real do result := -2.0 end
end

extend Shape
feature
  area(): Real
    deferred
    ensure non_negative: result >= 0.0
    end
end

do
  let s: Shape := create Dot.make
  print(s.area)
rescue
  print(exception.to_string)
end
")]
      (is (str/includes? (first out) "non_negative")))))

(deftest conflicts-and-restrictions-test
  (testing "an extension cannot replace a class's own feature"
    (is (str/includes? (error-message (str shapes "extend Circle feature radius(): Real do result := 1.0 end end\n"))
                       "Class 'Circle' already declares feature 'radius'")))
  (testing "two extensions cannot add the same feature"
    (is (str/includes? (error-message (str shapes
                                           "extend Circle feature size(): Integer do result := 1 end end\n"
                                           "extend Circle feature size(): Integer do result := 2 end end\n"))
                       "already added by 'extend Circle'")))
  (testing "an unknown target"
    (is (str/includes? (error-message "extend Nope feature f(): Integer do result := 1 end end\n")
                       "Cannot extend unknown class 'Nope'")))
  (testing "built-in targets are not supported yet"
    (is (str/includes? (error-message "extend String feature f(): Integer do result := 1 end end\n")
                       "Extending built-in type 'String' is not supported yet")))
  (testing "a generic target needs its generic parameters"
    (is (str/includes? (error-message "class Box [T]
  create make(v: T) do value := v end
  feature value: T
end
extend Box feature f(): Integer do result := 1 end end
")
                       "Write its generic parameters")))
  (testing "fields, once features and private sections are rejected when parsed"
    (is (str/includes? (error-message (str shapes "extend Circle feature extra: Integer end\n"))
                       "Field 'extra' is not allowed in an 'extend Circle' block"))
    (is (str/includes? (error-message (str shapes "extend Circle private feature f(): Integer do result := 1 end end\n"))
                       "A private feature section is not allowed"))))

(deftest client-visibility-test
  (testing "an extension does not see its target's private features"
    (doseq [body ["result := secret" "result := this.secret"]]
      (is (str/includes? (error-message (str shapes "extend Circle feature f(): Integer do " body " end end\n"))
                         "`secret` is private to Circle"))))
  (testing "an extension cannot assign its target's fields"
    (is (str/includes? (error-message (str shapes "extend Circle feature grow do radius := radius + 1.0 end end\n"))
                       "Cannot assign to field radius in an 'extend Circle' block"))))

(defn- write-module! [dir file-name text]
  (spit (io/file dir file-name) text)
  (.getPath (io/file dir file-name)))

(deftest modules-test
  (let [dir (doto (.toFile (java.nio.file.Files/createTempDirectory
                            "open_features"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
              (.deleteOnExit))]
    (write-module! dir "Geometry.nex" shapes)
    (write-module! dir "Areas.nex" (str "intern Geometry\n\n" area-extensions))
    (write-module! dir "Triangles.nex" "intern Geometry

class Triangle
  inherit Shape
  create make(b, h: Real) do base := b height := h end
  feature
    base: Real
    height: Real
end
")
    (testing "combining a new operation with a new variant needs glue"
      (let [main (write-module! dir "main.nex" "intern Areas
intern Triangles

print((create Triangle.make(2.0, 3.0)).base)
")
            msg (try (run-file main {}) nil (catch Exception ex (.getMessage ex)))]
        (is (some? msg))
        (is (str/includes? msg "Class 'Triangle' does not implement deferred feature 'area'"))
        (is (str/includes? msg "Areas.nex"))))
    (testing "with the glue extension the program runs on both backends"
      (let [main (write-module! dir "main.nex" "intern Areas
intern Triangles

extend Triangle
feature
  area(): Real do result := base * height / 2.0 end
end

let xs: Array[Shape] := [create Circle.make(1.0), create Triangle.make(2.0, 3.0)]
across xs as s do print(s.area) end
")
            compiled (run-file main {})]
        (is (= ["3.0" "3.0"] compiled))
        (is (= compiled (run-file main {:interpret? true})))))
    (testing "an interned module may extend a class the interning file declares"
      (write-module! dir "Greeting.nex" "extend Person
feature
  greeting: String do result := \"hello, \" + name end
end
")
      (let [main (write-module! dir "people.nex" "intern Greeting

class Person
  create make(n: String) do name := n end
  feature name: String
end

print((create Person.make(\"Ada\")).greeting)
")
            compiled (run-file main {})]
        (is (= ["\"hello, Ada\""] compiled))
        (is (= compiled (run-file main {:interpret? true})))))))

(deftest parse-shape-test
  (let [ast (p/ast (str shapes area-extensions))]
    (is (= ["Shape" "Circle" "Rect"] (mapv :target (:extensions ast))))
    (is (empty? (filter #(= :extension (:type %)) (:statements ast))))))
