(ns nex.field-shadowing
  "A `let` inside a class's routines may not reuse the name of a field (or
   constant) visible there. Such a `let` hides the field for the rest of the
   block; written in place of an assignment (`let value := v` in a
   constructor), it leaves the field unset — and the backends disagree on
   which one a later bare `value` means.

   Nor may it reuse the name of a routine visible there. Inside the `let`'s
   scope the bare name means the local, so a call meant for the routine
   reaches the local instead: in `let rest := fn(t: T): Stream[T] do result
   := rest.map(f) end`, `rest` is the function being defined, not the
   stream's `rest`.

   Only user-written `let`s are checked. Parameters (`make(name: String) do
   this.name := name end`) and loop/pattern variables are deliberate bindings
   and stay allowed."
  (:require [clojure.string :as str]))

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

(defn- routine-names
  "Routine names CLASS-DEF declares; private ones only when
   INCLUDE-PRIVATE?."
  [class-def include-private?]
  (for [section (feature-sections class-def)
        :when (or include-private? (not= :private (get-in section [:visibility :type])))
        member (:members section)
        :when (= :method (:type member))]
    (:name member)))

(defn- visible-features
  "{name [declaring-class-name kind]} for every field (kind :field) or
   routine (kind :routine) visible inside CLASS-DEF: its own, then each
   ancestor's non-private ones (nearest wins; a field wins over a routine
   of the same name in the same class)."
  [lookup class-def]
  (letfn [(ancestors [class-name visited]
            (when (and (string? class-name) (not (contains? visited class-name)))
              (when-let [cd (lookup class-name)]
                (cons cd (mapcat #(ancestors (:parent %) (conj visited class-name))
                                 (:parents cd))))))
          (own [cd include-private?]
            (concat (map (fn [n] [n [(:name cd) :field]]) (field-names cd include-private?))
                    (map (fn [n] [n [(:name cd) :routine]]) (routine-names cd include-private?))))]
    (reduce (fn [m [n v]] (if (contains? m n) m (assoc m n v)))
            {}
            (concat (own class-def true)
                    (mapcat #(own % false)
                            (mapcat #(ancestors (:parent %) #{(:name class-def)})
                                    (:parents class-def)))))))

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
  "Throw if any routine in CLASS-DEFS has a `let` that reuses a visible
   field's name, reporting the first such `let` of every routine: the
   exception's message has one line per routine, and :messages lists them.
   LOOKUP maps a parent name to its class definition (nil when unknown, in
   which case that ancestor's fields are simply not considered)."
  [class-defs lookup]
  (let [messages
        (for [class-def class-defs
              :when (and (map? class-def) (= :class (:type class-def)))
              :let [features (visible-features lookup class-def)]
              :when (seq features)
              routine (routine-bodies class-def)
              :let [[{:keys [name]} line] (shadowing-let features [(:body routine) (:rescue routine)]
                                                         (:dbg/line routine))]
              :when name]
          (let [[owner kind] (get features name)
                whose (if (= owner (:name class-def))
                        (str " of class '" owner "'")
                        (str " that class '" (:name class-def) "' inherits from '" owner "'"))]
            (str (when line (str "Line " line ": "))
                 (if (= kind :field)
                   (str "Local variable '" name "' has the same name as the field '" name "'"
                        whose
                        ", and would hide it. To set the field, assign it: `" name
                        " := ...`. Otherwise choose another name.")
                   (str "Local variable '" name "' has the same name as the routine '" name "'"
                        whose
                        ", and would hide it: where the local is in scope, `" name
                        "` means the local, so a call meant for the routine reaches it"
                        " instead. Choose another name.")))))]
    (when (seq messages)
      (let [msg (str/join "\n" messages)]
        (throw (ex-info msg {:error msg :messages (vec messages)}))))))
