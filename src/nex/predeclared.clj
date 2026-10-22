(ns nex.predeclared
  "The names every program can use without declaring them:

     console   the program's Console: console.print_line(\"hello\")
     program   the running Program: program.arguments, program.env(\"HOME\")

   Each is an ordinary top-level `let`, declared before the program's first
   statement, so it is a readable global (Definition §7.4) like any other:
   visible at the top level and inside every function and class body, on
   every backend, with nothing special about it except two things.

   - It is read-only everywhere, even at the top level (the typechecker
     knows a predeclared binding by the :predeclared mark on its `let`).
   - It gives way. A program that declares the name itself (a top-level
     `let`, a function of that name, or its own class of the type's name)
     gets no predeclared binding at all, so older programs that already wrote
     `let console := create Console` keep working unchanged. Anything that
     declares the name in a nested scope (a local, a parameter, a field)
     simply shadows it there, as it would any global.")

(def names
  "[name type] for each predeclared name, in declaration order."
  [["console" "Console"]
   ["program" "Program"]])

(def name-set (set (map first names)))

(defn- declares?
  "Whether AST (a parsed program) declares NAME itself, at the level where
   the predeclared binding would live, or defines its own class TYPE."
  [ast name type]
  (or (some #(and (map? %) (= :let (:type %)) (= name (:name %))) (:statements ast))
      (some #(= name (:name %)) (:functions ast))
      (some #(contains? #{type name} (:name %)) (:classes ast))))

(defn statements
  "The `let` statements predeclaring every name AST does not declare itself."
  [ast]
  (vec (for [[name type] names
             :when (not (declares? ast name type))]
         {:type :let
          :name name
          :var-type type
          :value {:type :create :class-name type :generic-args nil
                  :constructor nil :args []}
          :predeclared true})))

(defn add
  "AST with the predeclared names declared before its first statement."
  [ast]
  (let [lets (statements ast)]
    (if (empty? lets)
      ast
      (update ast :statements #(into lets %)))))
