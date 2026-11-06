(ns nex.extend
  "Open features: `extend Target feature ... end` blocks
   (docs/proposals/open-features.md).

   An extension adds routines to a class from outside its declaration,
   possibly from another module. A class with a Nex definition takes them in
   whole: `apply-extensions` appends each extension's feature sections to its
   target's class definition, so the type checker, the interpreter and the
   JVM backend see ordinary features, with nothing new downstream. Every
   member added is tagged `:extension {:target :source-file :line}`, which
   the type checker reads to check the body with a client's view of the
   target and to name the extension in diagnostics.

   The pass runs wherever a whole program is assembled (the interns merged in,
   see nex.compiler.jvm.file/augment-ast-with-interns) and again at the top of
   type checking. It is idempotent: applied extensions move from
   `:extensions` to `:applied-extensions`."
  (:require [clojure.string :as str]
            [nex.types.bootstrap :as bootstrap]
            [nex.types.builtins :as bi]))

(def ^:private builtin-target-names
  "Names of the built-in types, which have no class definition to merge into.
   Extending them is planned (the proposal's \"Built-in targets\") but not yet
   supported."
  (into #{"Integer" "Integer64" "Integer16" "Integer32" "Byte" "Real" "Decimal"
          "Char" "Boolean" "String" "Any" "Comparable" "Function" "Cursor"
          "Iterable" "Void" "Nil"}
        (concat (map name (keys bi/builtin-type-methods))
                (keep :name (bootstrap/base-class-defs)))))

(defn- extension-error
  "An ex-info for a rejected extension: :error holds the message, and the
   position (and file, when the extension is in an interned one) of the node
   it is about."
  [msg node ext]
  (ex-info msg (cond-> {:error msg :nex/extension-error true}
                 (:dbg/line node) (assoc :line (:dbg/line node))
                 (:dbg/col node) (assoc :column (:dbg/col node))
                 (:source-file ext) (assoc :source-file (:source-file ext)))))

(defn- feature-members
  [class-def]
  (->> (:body class-def)
       (filter #(= :feature-section (:type %)))
       (mapcat :members)))

(defn- member-key
  "[name arity] a member answers to. A field is a zero-argument query."
  [member]
  [(:name member) (if (= :method (:type member)) (count (:params member)) 0)])

(defn- describe-extension
  [{:keys [target source-file line]}]
  (str "'extend " target "'"
       (when line (str " at " (when source-file (str source-file ", ")) "line " line))))

(defn- check-no-conflict!
  "Reject MEMBER of EXT when CLASS-DEF already answers to its name and arity,
   in its own body or through an earlier extension. A class supplies each
   feature once: an extension cannot replace a class's own feature, and two
   extensions cannot both supply one (docs/proposals/open-features.md,
   \"Conflicts\"). Redefining an inherited feature is not a conflict: it is
   supplied by the heir, once."
  [class-def ext member]
  (let [k (member-key member)]
    (when-let [existing (some #(when (= k (member-key %)) %) (feature-members class-def))]
      (let [fname (:name member)
            cname (:name class-def)
            msg (if-let [other (:extension existing)]
                  (str "Feature '" fname "' of " cname " is already added by "
                       (describe-extension other) ". A class can be given a feature "
                       "by one extension only.")
                  (str "Class '" cname "' already declares feature '" fname
                       "'. An extension cannot replace a class's own feature; "
                       "change the class itself, or give the extension's "
                       "feature another name."))]
        (throw (extension-error msg member ext))))))

(defn- generic-param-name
  [gp]
  (let [n (:name gp)] (if (map? n) (:name n) n)))

(defn- check-generic-params!
  "An extension of a generic class restates its generic parameters, with the
   class's own names and constraints: `extend Box [T]` for `class Box [T]`."
  [class-def ext]
  (let [class-params (:generic-params class-def)
        ext-params (:generic-params ext)
        cname (:name class-def)
        spelled (fn [params]
                  (str cname " [" (str/join ", " (map generic-param-name params)) "]"))]
    (cond
      (and (seq class-params) (empty? ext-params))
      (throw (extension-error
              (str "Class '" cname "' is generic. Write its generic parameters in the "
                   "extension: 'extend " (spelled class-params) "'.")
              ext ext))

      (and (empty? class-params) (seq ext-params))
      (throw (extension-error
              (str "Class '" cname "' is not generic, so 'extend " cname "' takes no "
                   "generic parameters.")
              ext ext))

      (not= (map generic-param-name class-params) (map generic-param-name ext-params))
      (throw (extension-error
              (str "An extension of a generic class uses the class's own generic "
                   "parameter names: write 'extend " (spelled class-params) "'.")
              ext ext))

      (some (fn [[cp ep]] (and (:constraint ep) (not= (:constraint cp) (:constraint ep))))
            (map vector class-params ext-params))
      (throw (extension-error
              (str "An extension of '" cname "' cannot constrain its generic parameters "
                   "further than the class does. Features available only for some "
                   "type arguments are not supported.")
              ext ext)))))

(defn- extension-info
  "What an extension's sections and members are tagged with. :digest tells
   apart two extensions at the same place, as every REPL input's is (line 1
   of no file); the same extension applied twice (a module interned again)
   has the same info."
  [ext]
  (cond-> {:target (:target ext) :line (:dbg/line ext) :digest (hash (:body ext))}
    (:source-file ext) (assoc :source-file (:source-file ext))))

(defn applied?
  "True when CLASS-DEF already holds EXT's sections."
  [class-def ext]
  (let [info (extension-info ext)]
    (some #(= info (:extension %)) (:body class-def))))

(defn- merge-extension*
  [class-def ext entry-source-file]
  (check-generic-params! class-def ext)
  (let [info (extension-info ext)
        member-file (when (not= (:source-file ext) (:source-file class-def))
                      (or (:source-file ext) entry-source-file))
        tag (fn [member]
              (cond-> (assoc member :extension info)
                member-file (assoc :source-file member-file)))]
    (reduce (fn [cd section]
              (let [section' (-> section
                                 (assoc :extension info)
                                 (update :members (fn [ms] (mapv tag ms))))]
                ;; Each member is checked against what is already in the
                ;; class, sections added one at a time, so a duplicate within
                ;; one extension is caught too.
                (doseq [member (:members section')]
                  (check-no-conflict! cd ext member))
                (update cd :body (fnil conj []) section')))
            class-def
            (:body ext))))

(defn merge-extension
  "CLASS-DEF with EXT's feature sections appended, each section and member
   tagged with where it came from. A member also carries :source-file when
   the extension's file differs from the class's, so errors in its body name
   the right file; ENTRY-SOURCE-FILE stands in for the entry file, whose own
   declarations carry none. A no-op when CLASS-DEF already holds EXT (see
   applied?), as when a module is evaluated twice."
  ([class-def ext] (merge-extension class-def ext nil))
  ([class-def ext entry-source-file]
   (if (applied? class-def ext)
     class-def
     (merge-extension* class-def ext entry-source-file))))

(defn builtin-target?
  [target]
  (contains? builtin-target-names target))

(defn- target-indices
  "Indices in CLASSES of the class EXT extends, by bare or qualified name."
  [classes target]
  (keep-indexed (fn [i c] (when (or (= target (:name c)) (= target (:qualified-name c))) i))
                classes))

(defn unknown-target-error
  [ext]
  (let [target (:target ext)]
    (if (builtin-target? target)
      (extension-error (str "Extending built-in type '" target "' is not supported yet. "
                            "Only classes declared in Nex can be extended for now.")
                       ext ext)
      (extension-error (str "Cannot extend unknown class '" target "'.") ext ext))))

(defn apply-extensions
  "PROGRAM with every extension in :extensions merged into its target among
   :classes (see merge-extension), in order, and moved to
   :applied-extensions. Throws an ex-info carrying :error for an extension
   whose target is missing or ambiguous, or that conflicts with its target.

   OPTS, for the REPL, which assembles a program one input at a time:
   - :lookup, a function from a target name to the class definition of an
     earlier input, or nil. A target found only this way is merged and added
     to :classes, as a redefinition of that class; when it already holds the
     extension (a module interned again), it is left alone.
   - :leave-unresolved?, to keep an extension whose target is found nowhere
     in :extensions instead of throwing, for a later stage to apply."
  ([program] (apply-extensions program nil nil))
  ([program entry-source-file] (apply-extensions program entry-source-file nil))
  ([program entry-source-file {:keys [lookup leave-unresolved?]}]
   (let [exts (:extensions program)]
     (if (empty? exts)
       program
       (let [{:keys [classes left applied]}
             (reduce
              (fn [{:keys [classes] :as acc} ext]
                (let [target (:target ext)
                      idxs (target-indices classes target)
                      identities (distinct (map #(let [c (nth classes %)]
                                                   (or (:qualified-name c) (:name c)))
                                                idxs))
                      earlier (when (and (empty? idxs) lookup (not (builtin-target? target)))
                                (lookup target))]
                  (cond
                    (> (count identities) 1)
                    (throw (extension-error
                            (str "'" target "' names more than one class ("
                                 (str/join ", " (sort identities))
                                 "). Qualify the name in the extension, e.g. 'extend "
                                 (str/replace (first (sort identities)) "." "/")
                                 "'.")
                            ext ext))

                    (seq idxs)
                    (-> acc
                        (assoc :classes (reduce (fn [cs i]
                                                  (update cs i merge-extension ext entry-source-file))
                                                classes
                                                idxs))
                        (update :applied conj ext))

                    (and earlier (applied? earlier ext))
                    (update acc :applied conj ext)

                    earlier
                    (-> acc
                        (update :classes conj (merge-extension earlier ext entry-source-file))
                        (update :applied conj ext))

                    leave-unresolved?
                    (update acc :left conj ext)

                    :else
                    (throw (unknown-target-error ext)))))
              {:classes (vec (:classes program)) :left [] :applied []}
              exts)]
         (assoc program
                :classes classes
                :extensions left
                :applied-extensions (into (vec (:applied-extensions program)) applied)))))))
