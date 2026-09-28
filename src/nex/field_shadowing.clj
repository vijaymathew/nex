(ns nex.field-shadowing
  "A `let` inside a class's routines may not reuse the name of a field (or
   constant) visible there. Such a `let` hides the field for the rest of the
   block; written in place of an assignment (`let value := v` in a
   constructor), it leaves the field unset — and the backends disagree on
   which one a later bare `value` means.

   Only user-written `let`s are checked. Parameters (`make(name: String) do
   this.name := name end`) and loop/pattern variables are deliberate bindings
   and stay allowed.")

(defn- feature-sections
  [class-def]
  (filter #(= :feature-section (:type %)) (:body class-def)))

(defn- field-names
  "Field and constant names CLASS-DEF declares; private ones only when
   INCLUDE-PRIVATE?."
  [class-def include-private?]
  (for [section (feature-sections class-def)
        :when (or include-private? (not= :private (get-in section [:visibility :type])))
        member (:members section)
        :when (= :field (:type member))]
    (:name member)))

(defn- visible-fields
  "{field-name declaring-class-name} for every field visible inside CLASS-DEF:
   its own, then each ancestor's non-private ones (nearest wins)."
  [lookup class-def]
  (letfn [(ancestors [class-name visited]
            (when (and (string? class-name) (not (contains? visited class-name)))
              (when-let [cd (lookup class-name)]
                (cons cd (mapcat #(ancestors (:parent %) (conj visited class-name))
                                 (:parents cd))))))]
    (reduce (fn [m cd]
              (reduce (fn [m n] (if (contains? m n) m (assoc m n (:name cd))))
                      m
                      (field-names cd false)))
            (zipmap (field-names class-def true) (repeat (:name class-def)))
            (mapcat #(ancestors (:parent %) #{(:name class-def)}) (:parents class-def)))))

(defn- routine-bodies
  [class-def]
  (concat (->> (feature-sections class-def)
               (mapcat :members)
               (filter #(= :method (:type %))))
          (->> (:body class-def)
               (filter #(= :constructors (:type %)))
               (mapcat :constructors))))

(defn- shadowing-let
  "The first user-written `let` in NODE whose name is a key of FIELDS, as
   [let-node line], or nil."
  [fields node line]
  (cond
    (map? node)
    (let [line (or (:dbg/line node) line)]
      (if (and (= :let (:type node))
               (not (:synthetic node))
               (not (:from-pattern (:value node)))
               (contains? fields (:name node)))
        [node line]
        (some #(shadowing-let fields % line) (vals node))))

    (sequential? node)
    (some #(shadowing-let fields % line) node)))

(defn check-classes!
  "Throw on the first `let` in CLASS-DEFS that reuses a visible field's name.
   LOOKUP maps a parent name to its class definition (nil when unknown, in
   which case that ancestor's fields are simply not considered)."
  [class-defs lookup]
  (doseq [class-def class-defs
          :when (and (map? class-def) (= :class (:type class-def)))
          :let [fields (visible-fields lookup class-def)]
          :when (seq fields)
          routine (routine-bodies class-def)]
    (when-let [[{:keys [name]} line] (shadowing-let fields [(:body routine) (:rescue routine)] (:dbg/line routine))]
      (let [owner (get fields name)
            msg (str (when line (str "Line " line ": "))
                     "Local variable '" name "' has the same name as the field '" name "'"
                     (if (= owner (:name class-def))
                       (str " of class '" owner "'")
                       (str " that class '" (:name class-def) "' inherits from '" owner "'"))
                     ", and would hide it. To set the field, assign it: `" name
                     " := ...`. Otherwise choose another name.")]
        (throw (ex-info msg {:error msg :line line}))))))
