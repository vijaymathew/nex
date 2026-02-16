(ns examples.typed-let-demo
  "Demonstration of typed and untyped let syntax"
  (:require [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.generator.java :as java]))

(defn demo-typed-let
  "Demonstrate typed let: let x: Integer := 10"
  []
  (println "╔════════════════════════════════════════════════════════════╗")
  (println "║              TYPED LET SYNTAX DEMONSTRATION                ║")
  (println "╚════════════════════════════════════════════════════════════╝")
  (println)

  (let [nex-code "class Demo
  feature
    calculate() do
      -- Typed let declarations
      let x: Integer := 10
      let y: Integer := 20
      let result: Integer := x + y

      -- Untyped let declarations (backward compatible)
      let a := 100
      let b := 200
      let sum := a + b

      -- Mixed usage
      let count: Integer := 5
      let temp := count * 2

      print(\"Typed result:\", result)
      print(\"Untyped sum:\", sum)
      print(\"Mixed temp:\", temp)
    end
end"]

    (println "NEX CODE:")
    (println "═════════")
    (println nex-code)
    (println)

    ;; Run the interpreter
    (println "INTERPRETER OUTPUT:")
    (println "═══════════════════")
    (let [ast (p/ast nex-code)
          ctx (interp/make-context)
          _ (interp/register-class ctx (first (:classes ast)))
          method-def (-> ast :classes first :body first :members first)
          method-env (interp/make-env (:globals ctx))
          ctx-with-env (assoc ctx :current-env method-env)]
      (doseq [stmt (:body method-def)]
        (interp/eval-node ctx-with-env stmt))
      (doseq [line @(:output ctx-with-env)]
        (println line)))
    (println)

    ;; Generate Java code
    (println "GENERATED JAVA CODE:")
    (println "════════════════════")
    (println (java/translate nex-code))
    (println)))

(defn demo-benefits
  "Show the benefits of typed let"
  []
  (println "╔════════════════════════════════════════════════════════════╗")
  (println "║              WHY USE TYPED LET?                            ║")
  (println "╚════════════════════════════════════════════════════════════╝")
  (println)

  (println "1. BETTER JAVA CODE GENERATION")
  (println "   Without type: result = 10         (assignment)")
  (println "   With type:    int result = 10     (declaration)")
  (println)

  (println "2. SELF-DOCUMENTING CODE")
  (println "   let x := 10                       (type unclear)")
  (println "   let x: Integer := 10              (type explicit)")
  (println)

  (println "3. BACKWARD COMPATIBLE")
  (println "   Both syntaxes work!")
  (println "   - Use typed let when you want explicit types")
  (println "   - Use untyped let for quick prototyping")
  (println)

  (let [code1 "class Example
  feature
    demo() do
      let x := 10
      let y := 20
      print(x + y)
    end
end"
        code2 "class Example
  feature
    demo() do
      let x: Integer := 10
      let y: Integer := 20
      print(x + y)
    end
end"]

    (println "COMPARISON:")
    (println "═══════════")
    (println)
    (println "Untyped Let → Java:")
    (println (java/translate code1))
    (println)
    (println "Typed Let → Java:")
    (println (java/translate code2))))

(defn -main
  "Run the demonstration"
  [& args]
  (demo-typed-let)
  (println)
  (demo-benefits))

;; Run when loaded
(when (= *file* (System/getProperty "babashka.file"))
  (-main))
