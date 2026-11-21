(ns nex.refinements
  "Names shared by the walker and the type checker for the functions that
   check a refinement used as a type argument of a built-in generic class
   (`Array[Quantity]`, `Map[String, Pair[String]]`, `Task[Quantity]`).

   The walker generates one such function per refinement instantiation it
   finds written in the program (nex.walker/add-element-refinement-checkers);
   the type checker records each expression that flows into a slot of that
   type and names the function to wrap it in (nex.typechecker
   *refinement-sites*). Both sides derive the name from the refinement and its
   type arguments with `checker-name`, so it has to render a type the same way
   whichever side's representation it is given: a bare \"String\" and
   {:base-type \"String\"} are one type."
  (:require [clojure.string :as str]))

(defn- render-type
  "A canonical, identifier-safe rendering of TYPE-EXPR."
  [type-expr]
  (cond
    (string? type-expr) type-expr

    (and (map? type-expr) (:param-types type-expr))
    (str "Fn_" (str/join "_" (map (comp render-type :type) (:param-types type-expr)))
         (when-let [r (:return-type type-expr)] (str "_to_" (render-type r))))

    (map? type-expr)
    (let [args (or (:type-args type-expr) (:type-params type-expr))]
      (str (when (:detachable type-expr) "Opt_")
           (render-type (:base-type type-expr))
           (when (seq args)
             (str "_" (str/join "_" (map render-type args)) "_"))))

    :else (str type-expr)))

(defn checker-name
  "The name of the function checking refinement RNAME applied to ARGS (empty
   for a plain refinement): `__refine_check_Quantity`,
   `__refine_check_Pair__String`."
  [rname args]
  (str "__refine_check_" rname
       (when (seq args)
         (str "__" (str/join "__" (map render-type args))))))

(defn element-refinement-checker?
  "Whether NAME is one of the functions `checker-name` names."
  [name]
  (and (string? name) (str/starts-with? name "__refine_check_")))
