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
            [clojure.walk :as walk]
            [nex.ast-walk :as aw]
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
                member-file (-> (assoc :source-file member-file)
                                ;; Its closures are classes of their own.
                                (aw/stamp-closure-source-file member-file))))]
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

;; ---------------------------------------------------------------------------
;; Built-in targets
;;
;; A built-in value is a plain JVM value whose features come from a fixed table
;; (nex.types.builtins/builtin-type-methods): there is no class definition to
;; merge into. Each feature of an extension of one becomes a generated free
;; function, `__ext_<Type>__<feature>__<arity>`, whose first parameter `__this`
;; is the receiver; `this`, and the type's features named without a receiver,
;; are rewritten to it. The function node carries :builtin-extension, which
;; the type checker reads to register the feature on the type (and to record
;; the calls that reach it, which the compiled backend rewrites into calls of
;; the function), and the interpreter reads to dispatch to it.

(def ^:private extendable-builtins
  "The built-in types an extension may target."
  #{"Integer" "Byte" "Integer16" "Integer32" "Real" "Char" "Boolean" "String"
    "Array" "Map" "Set" "Map_Entry" "Min_Heap" "Task" "Channel"
    "Atomic_Integer" "Atomic_Integer64" "Atomic_Boolean" "Atomic_Reference"
    "Any" "Comparable" "Console" "Process" "Program"})

(def root-targets
  "The built-in types user classes inherit too: an extension of one reaches
   user objects as well as built-in values."
  #{"Any" "Comparable"})

(def ^:private builtin-generic-params
  "The generic parameter names of the generic built-ins, as their method
   signatures use them."
  {"Array" ["T"] "Set" ["T"] "Min_Heap" ["T"] "Task" ["T"] "Channel" ["T"]
   "Atomic_Reference" ["T"] "Map" ["K" "V"] "Map_Entry" ["K" "V"]})

(def this-param
  "The name the receiver of a built-in extension's feature is bound to."
  "__this")

(defn- builtin-method-names
  [type-name]
  (cond-> (into #{} (map key) (get bi/builtin-type-methods (keyword type-name)))
    (= "Comparable" type-name) (conj "compare")))

(defn extension-function-name
  "The name of the function generated for FEATURE of ARITY of the built-in
   TYPE-NAME. `__` separates the parts, since a type's name may hold `_`
   (Map_Entry); see extension-function-feature."
  [type-name feature arity]
  (str "__ext_" type-name "__" feature "__" arity))

(defn extension-function-feature
  "\"Type.feature\" for FN-NAME, a name extension-function-name made, or nil."
  [fn-name]
  (when-let [[_ t f] (re-matches #"__ext_(.+?)__(.+)__\d+" (str fn-name))]
    (str t "." f)))

(defn- member-arity [member] (count (:params member)))

(defn- check-builtin-extension!
  [ext]
  (let [target (:target ext)
        expected (get builtin-generic-params target)
        given (map generic-param-name (:generic-params ext))]
    (when-not (contains? extendable-builtins target)
      (throw (extension-error (str "Extending built-in type '" target "' is not supported.")
                              ext ext)))
    (cond
      (and (seq expected) (empty? given))
      (throw (extension-error
              (str "'" target "' is generic. Write its generic parameters in the extension: 'extend "
                   target " [" (str/join ", " expected) "]'.")
              ext ext))

      (and (empty? expected) (seq given))
      (throw (extension-error
              (str "'" target "' is not generic, so 'extend " target "' takes no generic parameters.")
              ext ext))

      (not= (vec expected) (vec given))
      (throw (extension-error
              (str "An extension of '" target "' uses its own generic parameter names: write 'extend "
                   target " [" (str/join ", " expected) "]'.")
              ext ext)))
    (doseq [section (:body ext)
            member (:members section)]
      (when (:declaration-only? member)
        (throw (extension-error
                (str "Feature '" (:name member) "' of 'extend " target "' cannot be deferred: no "
                     "class inherits from a built-in type, so nothing could implement it.")
                member ext)))
      (when (or (contains? (builtin-method-names target) (:name member))
                (contains? (builtin-method-names "Any") (:name member)))
        (throw (extension-error
                (str "'" target "' already has feature '" (:name member)
                     "'. An extension cannot replace a built-in feature; give the extension's "
                     "feature another name.")
                member ext))))))

(defn- this-identifier
  [node]
  (merge {:type :identifier :name this-param} (select-keys node [:dbg/line :dbg/col])))

(defn- bound-names
  "Names a routine body binds: its parameters, its locals (an `across`
   loop's variable is one), and the parameters of the functions it makes."
  [member]
  (let [names (volatile! (into #{} (map :name) (:params member)))]
    (walk/postwalk (fn [n]
                     (when (map? n)
                       (case (:type n)
                         :let (vswap! names conj (:name n))
                         :anonymous-function (vswap! names into (map :name) (:params n))
                         nil))
                     n)
                   [(:body member) (:require member) (:ensure member) (:rescue member)])
    @names))

(defn- receiver-rewrite
  "FORM with `this`, and every feature of FEATURES used without a receiver,
   given the receiver `__this`."
  [features form]
  (walk/prewalk
   (fn [n]
     (if-not (map? n)
       n
       (case (:type n)
         :this (this-identifier n)
         :identifier (if (contains? features (:name n))
                       (merge {:type :call :target (this-identifier n) :method (:name n)
                               :args [] :has-parens false}
                              (select-keys n [:dbg/line :dbg/col]))
                       n)
         :call (let [t (:target n)]
                 (cond
                   (and (nil? t) (contains? features (:method n)))
                   (assoc n :target (this-identifier n))

                   ;; `to_upper.trim`: a receiver named by a bare name is
                   ;; held as a string. A feature's name cannot be a local's
                   ;; (see bound-names), so it is the feature.
                   (and (string? t) (contains? features t))
                   (assoc n :target (merge {:type :call :target (this-identifier n) :method t
                                            :args [] :has-parens false}
                                           (select-keys n [:dbg/line :dbg/col])))

                   :else n))
         n)))
   form))

(declare generate-extension-functions)

(defn builtin-extension-functions
  "The generated function nodes for EXT, an extension of a built-in type.
   EXTRA-FEATURES are the names of the type's other extension features in
   the program, which a body may use without a receiver too."
  ([ext] (builtin-extension-functions ext #{}))
  ([ext extra-features]
   (check-builtin-extension! ext)
   (let [target (:target ext)]
     (generate-extension-functions
      ext target
      (into (into (builtin-method-names target) (builtin-method-names "Any"))
            (concat extra-features (map :name (mapcat :members (:body ext)))))))))

(defn- generate-extension-functions
  "The function nodes for EXT's features on TARGET, whose features FEATURES
   a body may use without a receiver."
  [ext target features]
  (let [gparams (:generic-params ext)
         this-type (if (seq gparams)
                     {:base-type target :type-args (mapv generic-param-name gparams)}
                     target)
         members (mapcat :members (:body ext))
         info (extension-info ext)]
     (vec
      (for [member members]
        (let [_ (when-let [hidden (first (filter features (bound-names member)))]
                  (throw (extension-error
                          (str "'" hidden "' in feature '" (:name member) "' of 'extend " target
                               "' would hide feature '" hidden "' of " target
                               ". Give the local or parameter another name.")
                          member ext)))
              arity (member-arity member)
              fn-name (extension-function-name target (:name member) arity)
              rw (partial receiver-rewrite features)
              params (into [{:name this-param :type this-type}] (:params member))
              body (rw (:body member))
              pos (select-keys member [:dbg/line :dbg/col])
              method-def (merge {:type :method
                                 :name (str "call" (count params))
                                 ;; Messages name the feature, not the function.
                                 :function-name (str target "." (:name member))
                                 :params params
                                 :return-type (:return-type member)
                                 :declaration-only? false
                                 :note (:note member)
                                 :require (rw (:require member))
                                 :body body
                                 :ensure (rw (:ensure member))
                                 :rescue (rw (:rescue member))
                                 :extension info}
                                pos)
              class-def {:type :class
                         :name (str fn-name "_Function")
                         :generic-params gparams
                         :note nil
                         :parents [{:parent "Function"}]
                         :body [{:type :feature-section
                                 :visibility {:type :public}
                                 :members [method-def]}]
                         :invariant nil}]
          (cond-> {:type :function
                   :name fn-name
                   :class-name (:name class-def)
                   :generic-params gparams
                   :declaration-only? false
                   :params params
                   :return-type (:return-type member)
                   :body body
                   :class-def class-def
                   :builtin-extension {:type target
                                       :feature (:name member)
                                       :arity arity
                                       :info info}}
            (:source-file ext) (-> (assoc :source-file (:source-file ext))
                                   (aw/stamp-closure-source-file (:source-file ext)))))))))

(defn- check-no-root-feature-clash!
  "Reject a class declaring a feature an extension of `Any` or `Comparable`
   adds. A call of such a feature is resolved to the extension's function
   statically on the compiled backend, so a class's own version could not
   take over from it as a redefinition does."
  [classes root-fns]
  (let [added (into {} (map (fn [f] (let [{:keys [type feature arity]} (:builtin-extension f)]
                                      [[feature arity] type])))
                    root-fns)]
    (when (seq added)
      (doseq [c classes
              :when (not-any? #(= "Function" (:parent %)) (:parents c))
              member (feature-members c)
              :when (not (:extension member))
              :let [root (get added (member-key member))]
              :when root]
        (throw (extension-error
                (str "Class '" (:name c) "' declares feature '" (:name member) "', which "
                     "'extend " root "' also adds to every " (if (= "Any" root) "value" "comparable value")
                     ". Rename one of them: an extension of " root " cannot be redefined by a class.")
                member nil))))))

;; ---------------------------------------------------------------------------
;; Java targets (phase 2)
;;
;; An imported Java class is extended the way a built-in type is: each
;; feature becomes a generated function taking the receiver first, which
;; calls whose receiver's static type is the class (or a Java subclass of it)
;; reach. A feature may be added to one class of a Java hierarchy only, so the
;; static choice the compiled backend makes and the runtime one the
;; interpreter makes are always the same class's.

(def ^:private java-representation-classes
  "Java classes that are the runtime form of a Nex built-in value, with the
   Nex type to extend instead."
  {"java.util.ArrayList" "Array" "java.util.HashMap" "Map" "java.util.LinkedHashSet" "Set"
   "java.lang.String" "String" "java.lang.Long" "Integer" "java.lang.Integer" "Integer32"
   "java.lang.Short" "Integer16" "java.lang.Double" "Real" "java.lang.Character" "Char"
   "java.lang.Boolean" "Boolean"})

(defn java-target-class-name
  "The qualified name of the Java class TARGET names through IMPORTS (import
   nodes), or nil."
  [imports target]
  (some (fn [{:keys [qualified-name source]}]
          (when (and qualified-name (nil? source)
                     (= target (last (str/split qualified-name #"\."))))
            qualified-name))
        imports))

(defn- java-class
  ^Class [qualified-name]
  (try (Class/forName qualified-name false (.getContextClassLoader (Thread/currentThread)))
       (catch Throwable _ nil)))

(defn- java-public-methods
  "#{[name arity]} of CLS's public methods, inherited ones included."
  [^Class cls]
  (into #{} (map (fn [^java.lang.reflect.Method m] [(.getName m) (.getParameterCount m)]))
        (.getMethods cls)))

(defn java-supertypes
  "CLS and every class and interface it extends or implements, nearest
   first: its superclass chain, then the interfaces of each, breadth first."
  [^Class cls]
  (loop [queue (conj clojure.lang.PersistentQueue/EMPTY cls) seen #{} out []]
    (if-let [^Class c (peek queue)]
      (if (contains? seen c)
        (recur (pop queue) seen out)
        (recur (into (pop queue) (cond-> (vec (.getInterfaces c))
                                   (.getSuperclass c) (->> (cons (.getSuperclass c)) vec)))
               (conj seen c)
               (conj out c)))
      out)))

(defn- check-java-extension!
  [ext qualified-name ^Class cls]
  (let [target (:target ext)]
    (when (= "java.lang.Object" qualified-name)
      (throw (extension-error
              "Every value is a java.lang.Object, Nex's own included. Extend Any instead."
              ext ext)))
    (when-let [nex-type (get java-representation-classes qualified-name)]
      (throw (extension-error
              (str "'" qualified-name "' is how Nex represents " nex-type " values, so extending it "
                   "would change " nex-type " too. Extend " nex-type " instead.")
              ext ext)))
    (when-not cls
      (throw (extension-error (str "Cannot find Java class '" qualified-name "' to extend.") ext ext)))
    (when (seq (:generic-params ext))
      (throw (extension-error
              (str "An extension of the Java class '" target "' takes no generic parameters.")
              ext ext)))
    (let [own (java-public-methods cls)]
      (doseq [section (:body ext)
              member (:members section)]
        (when (:declaration-only? member)
          (throw (extension-error
                  (str "Feature '" (:name member) "' of 'extend " target "' cannot be deferred: "
                       "Java classes cannot be made to implement it.")
                  member ext)))
        (when (contains? own [(:name member) (member-arity member)])
          (throw (extension-error
                  (str "Java class '" qualified-name "' already has a public method '" (:name member)
                       "' taking " (member-arity member) " argument(s). An extension cannot replace it; "
                       "give the extension's feature another name.")
                  member ext)))))))

(defn java-extension-functions
  "The generated function nodes for EXT, an extension of the imported Java
   class QUALIFIED-NAME (see builtin-extension-functions). EXTRA-FEATURES as
   there."
  [ext qualified-name extra-features]
  (let [cls (java-class qualified-name)
        _ (check-java-extension! ext qualified-name cls)
        features (into (into #{} (map first) (java-public-methods cls))
                       (concat extra-features (map :name (mapcat :members (:body ext)))))]
    (mapv #(assoc-in % [:builtin-extension :java-class] qualified-name)
          (generate-extension-functions ext (:target ext) features))))

(defn- check-one-class-per-java-hierarchy!
  "Reject a feature added to two classes of one Java hierarchy: the compiled
   backend would choose between them by a value's static type and the
   interpreter by its runtime class, which can differ."
  [functions]
  (let [java-fns (filter #(get-in % [:builtin-extension :java-class]) functions)]
    (doseq [[_ fs] (group-by (juxt #(get-in % [:builtin-extension :feature])
                                   #(get-in % [:builtin-extension :arity]))
                             java-fns)
            [a b] (for [a fs b fs :when (not= a b)] [a b])
            :let [ca (java-class (get-in a [:builtin-extension :java-class]))
                  cb (java-class (get-in b [:builtin-extension :java-class]))]
            :when (and ca cb (.isAssignableFrom ca cb))]
      (let [{:keys [feature]} (:builtin-extension a)]
        (throw (extension-error
                (str "Feature '" feature "' is added to both '" (.getName ca) "' and its subtype '"
                     (.getName cb) "'. A feature may be added to one class of a Java hierarchy "
                     "only; subtypes of that class have it too.")
                {:dbg/line (get-in b [:builtin-extension :info :line])}
                {:source-file (get-in b [:builtin-extension :info :source-file])}))))))

(defn rewrite-extension-calls
  "AST with each call node in CALLS (an IdentityHashMap from the call node to
   the name of the generated function it reaches; see the type checker's
   :extension-calls) made a call of that function, its receiver first."
  [ast ^java.util.IdentityHashMap calls]
  (if (or (nil? calls) (.isEmpty calls))
    ast
    (walk/prewalk
     (fn [n]
       (if-let [fn-name (and (map? n) (.get calls n))]
         (let [target (:target n)
               receiver (if (string? target) {:type :identifier :name target} target)]
           (merge (dissoc n :target)
                  {:type :call :target nil :method fn-name
                   :args (into [receiver] (:args n)) :has-parens true}))
         n))
     ast)))

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
      (extension-error (str "Cannot extend unknown class '" target "'."
                            (when (re-matches #"[A-Z]\w*" target)
                              (str " If it is a Java class, import it first: 'import <package>."
                                   target "'.")))
                       ext ext))))

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
     in :extensions instead of throwing, for a later stage to apply.
   - :builtin-features, a function from a built-in type's name to the names
     of the features earlier inputs' extensions gave it, which a body may use
     without a receiver.
   - :imports, import nodes of earlier inputs, through which a target may
     name a Java class."
  ([program] (apply-extensions program nil nil))
  ([program entry-source-file] (apply-extensions program entry-source-file nil))
  ([program entry-source-file {:keys [lookup leave-unresolved?] :as opts}]
   (let [exts (:extensions program)]
     (if (empty? exts)
       program
       (let [imports (concat (:imports program) (:imports opts))
             ;; Every built-in type's extension features, by type: a body may
             ;; use any of them without a receiver.
             builtin-features (reduce (fn [m ext]
                                        (update m (:target ext) (fnil into #{})
                                                (map :name (mapcat :members (:body ext)))))
                                      {}
                                      exts)
             {:keys [classes functions left applied]}
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

                    (or (and (empty? idxs) (builtin-target? target))
                        (and (empty? idxs) (java-target-class-name imports target)))
                    (let [extra (into (get builtin-features target #{})
                                      (when-let [earlier (:builtin-features opts)]
                                        (earlier target)))
                          fns (if (builtin-target? target)
                                (builtin-extension-functions ext extra)
                                (java-extension-functions
                                 ext (java-target-class-name imports target) extra))]
                      (doseq [f fns]
                        (when (some #(= (:name f) (:name %)) (:functions acc))
                          (let [{:keys [type feature]} (:builtin-extension f)]
                            (throw (extension-error
                                    (str "Feature '" feature "' of " type " is already added by "
                                         "another extension. A type can be given a feature by "
                                         "one extension only.")
                                    ext ext)))))
                      (-> acc
                          (update :functions into fns)
                          (update :classes into (map :class-def fns))
                          (update :applied conj ext)))

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
              {:classes (vec (:classes program)) :functions [] :left [] :applied []}
              exts)]
         (check-one-class-per-java-hierarchy! (concat (:functions program) functions))
         (check-no-root-feature-clash!
          classes
          (filter #(contains? root-targets (get-in % [:builtin-extension :type])) functions))
         (assoc program
                :classes classes
                :functions (into (vec (:functions program)) functions)
                :extensions left
                ;; Where each was, not its body: later passes walk the whole
                ;; program, and must find each routine once, in its class.
                :applied-extensions (into (vec (:applied-extensions program))
                                          (map #(select-keys % [:target :source-file :dbg/line :dbg/col]))
                                          applied)))))))
