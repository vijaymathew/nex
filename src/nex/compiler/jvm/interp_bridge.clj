(ns nex.compiler.jvm.interp-bridge
  "The REPL's bridge from compiled code to the tree-walking interpreter.

   The REPL can hand compiled code a value the interpreter created — a cell it
   evaluated on the tree-walker (the debugger, `:debug on`, runs there), or a
   cell the compiled path declined. Running Nex code on such an object (a
   method call, its `to_string`, its `equals`) needs the interpreter, which
   nex.compiler.jvm.runtime deliberately does not load: this namespace
   installs those operations as the runtime's interpreter adapter when it is
   loaded (by nex.compiler.jvm.repl). Whole-file programs never load it."
  (:require [nex.compiler.jvm.runtime :as compiled]
            [nex.interpreter :as interp]
            [nex.types.builtins :as bi]))

(defn rebuild-interpreter-ctx
  "Build a fresh interpreter context from this compiled REPL session's own
   state — used whenever a call has to be dispatched back through the
   interpreter (the adapter operations below). Compiled code never needs it for its own closures (they are
   compiled classes); it serves values the interpreter itself created, in a
   REPL cell evaluated interpretively (e.g. under `:debug on`).

   KNOWN LIMITATION (for those interpreter-created closures only; accepted —
   see docs/md/SYNTAX.md's mutual-recursion section and definition-of-nex
   &sect;4.5): the `(:classes ctx)` merge below intentionally includes
   `@(:classes state)` so an interpreted closure can reach a function/class
   defined in a LATER REPL input (that IS this merge's whole point) — but
   `@(:classes state)` also holds nex.lower/prepare-program-for-closures'
   own REWRITTEN synthetic closure classes (its Closure_Mut_Box handling for
   mutually recursive `let`-bound closures — see box-forward-referenced-
   closures there), registered under the SAME synthetic name
   (\"AnonymousFunction_N\") the ORIGINAL, correctly-working interpreter
   object was itself built from when its OWN defining input ran. If that
   object's value later has to be re-dispatched through THIS rebuilt ctx —
   which happens for any interpreter-native value, exactly the case here —
   it runs under the REWRITTEN class-def instead of the one it was actually
   built with, expecting a captured field to be boxed when the real captured
   value never was: \"Method not found: value\"/\"call1\", even though the
   interpreter handles the very same mutual recursion correctly natively,
   with no box involved at all, when it isn't relayed through this bridge.
   A single self-recursive closure, or a whole mutually-recursive group
   invoked from within the SAME input that defined it, never hits this path
   this way and is unaffected — only a later, separate input calling into a
   mutually-recursive PAIR defined earlier does. Fixing it for real means
   keeping the rewritten, box-aware class-defs available for compiling NEW
   code that references such a closure, while never substituting them in
   when re-executing an EXISTING interpreter-native object's own method —
   which is a change to this shared bridge, not to the closure code alone,
   so it's deliberately left as documented behavior rather than patched
   here."
  [state]
  (let [ctx (interp/make-context)]
    (reset! (:bindings (:globals ctx)) {})
    (reset! (:output ctx) @(:output state))
    (reset! (:imports ctx) (vec @(:imports state)))
    (when-let [compiled-state-slot (:compiled-state ctx)]
      (reset! compiled-state-slot state))
    ;; A plain Clojure map, not a java.util.HashMap: register-class (called
    ;; whenever this rebuilt ctx's own interpreted code registers a further
    ;; nested closure's class-def, e.g. a closure inside a closure) does
    ;; `(swap! (:classes ctx) assoc ...)`, which needs a real Associative —
    ;; the mutable HashMap this used to build let reads through `get` work
    ;; (Clojure's `get` accepts any java.util.Map) but broke that swap! with
    ;; a ClassCastException the first time it actually fired.
    (swap! (:classes ctx)
           (fn [builtins]
             (into builtins @(:classes state))))
    (doseq [[k v] @(:values state)]
      (interp/env-define (:globals ctx) k v))
    ;; Make top-level functions resolvable by name from interpreted (deoptimized)
    ;; closures, e.g. a helper called inside an `fn` stored in a collection.
    (doseq [[k v] @(:functions state)]
      (when-let [callable (compiled/registered-fn-callable state v)]
        (interp/env-define (:globals ctx) k callable)))
    ctx))

(defn- call-method
  [state target method-name args has-parens?]
  (interp/eval-node (rebuild-interpreter-ctx state)
                    (cond-> {:type :call
                             :target {:type :literal :value target}
                             :method method-name
                             :args (mapv (fn [v] {:type :literal :value v}) args)}
                      has-parens? (assoc :has-parens true))))

(compiled/install-interpreter-adapter!
 {:call-method call-method
  :concat-string (fn [state value]
                   (bi/concat-string-value (rebuild-interpreter-ctx state) value))
  :equals-override (fn [state a b]
                     (interp/object-equals-override (rebuild-interpreter-ctx state) a b))})
