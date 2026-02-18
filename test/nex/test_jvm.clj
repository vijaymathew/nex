(require '[nex.parser :as parser])
(require '[nex.interpreter :as interp])

(def src "class Point
feature
  x: Integer
  y: Integer
create
  make(a: Integer, b: Integer)
    do
      let x := a
      let y := b
    end
end")

(def ast (parser/ast src))
(def ctx (interp/make-context))
(interp/eval-node ctx ast)
(println "✓ JVM version works!")
