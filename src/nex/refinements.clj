(ns nex.refinements
  "Names shared by the walker and the type checker for the functions that
   check a value entering a slot whose type mentions a refinement somewhere
   the walker's own narrowing checks can't reach:

   - a refinement applied as a type argument: `Array[Quantity]`'s element,
     `Box[Pair[String]]`'s `T`;
   - a detachable one there (`Array[?Quantity]`, `Atomic_Reference[Quantity]`,
     whose features take `?T`): nil passes, anything else is checked;
   - a whole collection of them (`let qs: Array[Quantity] := raw`): every
     element is checked.

   The walker generates one function per such type written in the program
   (nex.walker/add-element-refinement-checkers); the type checker records each
   expression that flows into a slot of that type and names the function to
   wrap it in (nex.typechecker *refinement-sites*). Both derive the name from
   the slot's type with `checker-name`, so it has to render a type the same
   way whichever side's representation it is given: a bare \"String\" and
   {:base-type \"String\"} are one type, and so are :type-args and
   :type-params."
  (:require [clojure.string :as str]))

(defn- render-type
  "A canonical, identifier-safe rendering of TYPE-EXPR."
  [type-expr]
  (cond
    (string? type-expr) type-expr

    (and (map? type-expr) (:param-types type-expr))
    (str (when (:detachable type-expr) "Opt_")
         "Fn_" (str/join "_" (map (comp render-type :type) (:param-types type-expr)))
         (when-let [r (:return-type type-expr)] (str "_to_" (render-type r))))

    (map? type-expr)
    (let [args (or (:type-args type-expr) (:type-params type-expr))]
      (str (when (:detachable type-expr) "Opt_")
           (render-type (:base-type type-expr))
           (when (seq args)
             (str "_" (str/join "_" (map render-type args)) "_"))))

    :else (str type-expr)))

(defn checker-name
  "The name of the function checking a value entering a slot of TYPE-EXPR:
   `__refine_check_Quantity`, `__refine_check_Pair_String_`,
   `__refine_check_Opt_Quantity`, `__refine_check_Array_Quantity_`."
  [type-expr]
  (str "__refine_check_" (render-type type-expr)))

(defn convert-checker-name
  "The name of the function a `convert` to refinement TYPE-EXPR routes its
   value through (nex.walker/resolve-convert-aliases): `__refine_Quantity`,
   `__refine_Pair_String_`. It yields the value when it satisfies the
   refinement and nil otherwise, rather than raising as a `checker-name`
   function does."
  [type-expr]
  (str "__refine_" (render-type type-expr)))

(defn element-refinement-checker?
  "Whether NAME is one of the functions `checker-name` names."
  [name]
  (and (string? name) (str/starts-with? name "__refine_check_")))
