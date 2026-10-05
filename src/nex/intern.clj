(ns nex.intern
  "Static resolution of `intern` declarations: locating an interned module's
   file, parsing it, and collecting the classes, free functions, imports and
   `declare type` aliases it brings into scope (recursively), stamped with
   their namespace-qualified names. Pure AST work shared by the typechecker
   entry points, the JVM compiler (whole-file and REPL) and the interpreter's
   own runtime `intern` — none of it evaluates anything, so it lives apart
   from nex.interpreter and the compiled path need not load the tree-walker
   to use it."
  (:require [clojure.java.io]
            [clojure.string :as str]
            [nex.parser :as parser])
  (:import [clj_antlr ParseError]))

(defn- lowercase-filename
  [class-name]
  (-> class-name str/lower-case))

(defn- intern-filenames
  [class-name]
  (distinct [(str class-name ".nex")
             (str (lowercase-filename class-name) ".nex")]))

(defn- intern-search-roots
  "Return directories to search for project-local interned classes.
      Prefer the currently loaded source file's directory when available, then
      fall back to the user's original working directory.

      That fallback matters most for a NESTED intern: when a just-interned
      lib file (say lib/transactions/account.nex) itself interns a
      path-qualified module (`intern units/Money`), :debug-source has moved
      to account.nex's own path, so source-dir becomes lib/transactions —
      not the project root units/Money.nex is actually relative to. Without
      user-dir, the only other root searched is `pwd`, which for a plain
      `nex script.nex` invocation is NEX_HOME (the CLI `cd`s there before
      starting the JVM), not the user's project directory. This only
      appeared to work when an interned lib happened to sit in the very
      same directory as the module it, in turn, interned.

      The `nex.user.dir` system property is set by the REPL; a plain
      `nex script.nex` run instead exports the project root via the
      NEX_USER_DIR env var (see bin/nex) and never sets the property, so
      both are checked here."
  [ctx]
  (let [source-dir (when-let [source (:debug-source ctx)]
                     (let [f (clojure.java.io/file source)]
                       (when (.isAbsolute f)
                         (.getParentFile f))))
        user-dir (when-let [udir (or (System/getProperty "nex.user.dir")
                                     (System/getenv "NEX_USER_DIR"))]
                   (clojure.java.io/file udir))
        pwd (clojure.java.io/file ".")]
    (->> [source-dir user-dir pwd]
         (remove nil?)
         distinct)))

(defn find-intern-file
  "Search for an intern file in the specified locations.
      Returns the absolute path if found, otherwise throws an exception."
  [ctx path class-name]
  (let [filenames (intern-filenames class-name)
        current-root (System/getenv "NEX_USER_DIR")
        current-dir (map #(str (clojure.java.io/file current-root %)) filenames)
        local-roots (intern-search-roots ctx)
        local-direct (mapcat (fn [root]
                               (map #(str (clojure.java.io/file root %)) filenames))
                             local-roots)
        local-lib (when (seq path)
                    (mapcat (fn [root]
                              (concat
                               (map #(str (clojure.java.io/file root "lib" path %)) filenames)
                               (map #(str (clojure.java.io/file root "lib" path "src" %)) filenames)))
                            local-roots))
        home-deps (if (seq path)
                    (concat
                     (map #(str (System/getProperty "user.home") "/.nex/deps/" path "/" %) filenames)
                     (map #(str (System/getProperty "user.home") "/.nex/deps/" path "/src/" %) filenames))
                    (concat
                     (map #(str (System/getProperty "user.home") "/.nex/deps/" %) filenames)
                     (map #(str (System/getProperty "user.home") "/.nex/deps/src/" %) filenames)))
           ;; A path-qualified intern (e.g. `intern net/Http_Server`) names a
           ;; module under that path, so the path-qualified locations (./lib/<path>
           ;; and the dependency cache) must be searched BEFORE the unqualified
           ;; same-directory locations. Otherwise a source file that merely shares
           ;; the module's bare filename (e.g. examples/http_server.nex) would
           ;; shadow the real library module.
        locations (vec (if (seq path)
                         (concat local-lib home-deps current-dir local-direct)
                         (concat current-dir local-direct home-deps)))
        found (first (filter #(-> % clojure.java.io/file .exists) locations))]
    (if found
      found
      (throw (ex-info (str "Cannot find intern file for "
                           (if (seq path)
                             (str path "/" class-name)
                             class-name))
                      {:path path
                       :class-name class-name
                       :searched-locations locations})))))

(defn parse-interned-file
  "Parse an interned file's own source, wrapping a ParseError with the
   file's own path and source text before it escapes this function.

   Without this, a syntax error in an INTERNED file — not the one the user
   actually ran — propagates as a bare ParseError with no indication of
   which file it came from. The top-level ParseError handler
   (`nex.eval/-main`, and `nex.repl`'s equivalent) always re-slurps and
   formats against the file the user directly ran, on the reasonable
   assumption that a ParseError could only ever come from parsing THAT
   file — true before `intern` could pull in a second file to parse, no
   longer true once it can. The result was a caret pointing at essentially a
   random position in the *entry* file's text, with the actually-broken
   file and line never named at all — `nex some_file.nex`, when the syntax
   error is 60 lines into a library it interns, points at whatever line in
   `some_file.nex` happens to share a line number with the real one, or past
   the end of it entirely."
  [file-path source]
  (try
    (parser/ast source)
    (catch ParseError e
      (throw (ex-info (str "Syntax error in " file-path)
                      {:nex/intern-parse-error true
                       :file-path file-path
                       :source source
                       :parse-error e})))))

(defn qualify-name
  "Combine an intern path (`finance`, or a multi-segment `net/http`) with a
   class or function's own bare name to form its qualified name — the
   identity a later ambiguity-detection pass will use to tell two
   same-named-but-unrelated interned classes/functions apart (see
   docs/proposals/namespaces.md). An unpathed intern (`path` nil or empty,
   e.g. `intern Account`) yields the bare name unchanged."
  [path own-name]
  (if (seq path)
    (str (str/replace path #"/" ".") "." own-name)
    own-name))

(defn- qualify-sibling-create-refs
  "Rewrite `create Sibling.…` nodes anywhere inside a value-expression tree to
   the namespace-qualified class name, for every Sibling that is one of this
   file's own classes (SIBLING-NAMES). Only `:create` targets are touched, and
   only when they name a sibling — a bare `:identifier` that happens to share a
   sibling's name (e.g. an `enum union` parent's `values` array, whose elements
   reference the member *constants*, not the variant classes) is left alone."
  [value path sibling-names]
  (cond
    (not (map? value))
    value

    (and (= (:type value) :create)
         (contains? sibling-names (:class-name value)))
    (-> value
        (assoc :class-name (qualify-name path (:class-name value)))
        (cond-> (:args value)
          (update :args (fn [args]
                          (mapv #(qualify-sibling-create-refs % path sibling-names) args)))))

    :else
    (reduce-kv (fn [m k v]
                 (assoc m k
                        (cond
                          (map? v)    (qualify-sibling-create-refs v path sibling-names)
                          (vector? v) (mapv #(qualify-sibling-create-refs % path sibling-names) v)
                          :else       v)))
               {}
               value)))

(defn- qualify-constant-sibling-refs
  "check-program type-checks a class constant's initializer eagerly, under the
   class's qualified identity, BEFORE any interned class is registered under its
   bare name (nex.typechecker/check-program, Phase 3). So a constant that names
   a sibling class by its bare name — most sharply, the `P.Variant = create
   Variant.make()` members an `enum union` desugars to — cannot resolve that
   sibling unless the reference is rewritten to the key it IS registered under
   during that pass: the qualified one. Only constants are affected (nothing
   else is checked eagerly); ordinary method bodies run after bare registration."
  [class-def path sibling-names]
  (if (or (empty? path) (not= (:type class-def) :class))
    class-def
    (update class-def :body
            (fn [body]
              (mapv (fn [section]
                      (if (= (:type section) :feature-section)
                        (update section :members
                                (fn [members]
                                  (mapv (fn [m]
                                          (if (and (= (:type m) :field)
                                                   (:constant? m)
                                                   (:value m))
                                            (update m :value
                                                    qualify-sibling-create-refs path sibling-names)
                                            m))
                                        members)))
                        section))
                    body)))))

(defn- stamp-qualified-names
  "Attach :qualified-name to each class/fn-def declared directly in a
   just-interned file, using the path that file was interned under. Every
   class/function in one module shares that module's namespace — not just
   the one an `intern path/Class` statement happened to name for file
   lookup — the same way a Java file's package covers every class it
   declares, not only the one a caller imports by name.

   Also stamps :source-file with the file's own canonical path. Once
   merged into one program (nex.eval/augment-ast-with-interns and its
   compiled/REPL equivalents), a class or function from an interned file
   carries no other record of which file it actually came from — every
   :dbg/line on it is only meaningful relative to THAT file's own text, not
   the entry file's. Without :source-file, a type error inside an interned
   file's own body reported a line number with no file to go with it — e.g.
   \"Type error at line 65, column 39: Undefined variable: xs\", where the
   entry file the user ran is 7 lines long: correct information (line 65 IS
   where the mistake is), pointing nowhere without knowing which file it's
   line 65 OF. check-program reads this back to annotate exactly such an
   error with the file it actually happened in (see with-source-file)."
  [path source-file defs]
  (let [sibling-names (into #{} (keep :name) defs)]
    (mapv #(-> %
               (assoc :qualified-name (qualify-name path (:name %)) :source-file source-file)
               (qualify-constant-sibling-refs path sibling-names))
          defs)))

(defn- resolve-interned*
  "Traverse intern declarations recursively and collect the class
      definitions, import declarations, free functions, and `declare type`
      aliases they bring into scope for static analysis. Returns
      {:classes [...] :imports [...] :functions [...] :type-aliases [...]
      :seen #{...}}. An aliased intern (`intern X as Y`) adds a `:type-aliases`
      entry mapping Y to X's real class name, rather than a second, renamed
      copy of the class-def: `Y` must be the *same* class as `X`, not a
      nominally distinct duplicate with an identical body. A duplicate broke
      as soon as the same underlying class was also reachable elsewhere in the
      program under its real name (e.g. transitively, via a second module that
      interns it unaliased) — the typechecker and JVM backend then saw two
      unrelated classes and rejected a value of one where the other was
      expected, even though the program never declared two different types.
      Static class-name resolution (`nex.typechecker/env-lookup-class` and
      `env-lookup-method`, `nex.lower`'s `visible-class-map`, and
      `nex.compiler.jvm.file/file-class-metadata`) all fall back through
      `:type-aliases` when a literal name misses, so `Y` still resolves to
      X's real, singular class-def and compiled `.class` everywhere. Imports
      are carried through so that an interned module's host-class imports
      (e.g. `import java.net.ServerSocket`) are visible to the typechecker
      that elaborates the merged program — a `declare type` refinement alias
      needs exactly the same treatment: check-program only ever reads
      :type-aliases off the *root* program's own parse, so without collecting
      it here too, a refinement type declared in an interned file (rather
      than the root script) type-checked as an outright \"Undefined type\"
      everywhere it was used, even inside that same interned file's own
      classes. Every class-def and fn-def returned here also carries a
      :qualified-name (see qualify-name/stamp-qualified-names above),
      derived from the path it was interned under. Nothing reads this yet —
      it exists so a later ambiguity-detection pass has a stable identity to
      key on, without changing any behavior today (see
      docs/proposals/namespaces.md, Phase 1)."
  [source-id program seen-files]
  (letfn [(resolve* [current-source current-program seen]
            (let [ctx {:debug-source current-source}]
              (reduce
               (fn [{:keys [classes imports functions type-aliases seen]} {:keys [path class-name alias]}]
                 (let [file-path (find-intern-file ctx path class-name)
                       canonical (.getCanonicalPath (clojure.java.io/file file-path))]
                   (if (contains? seen canonical)
                     {:classes classes :imports imports :functions functions
                      :type-aliases type-aliases :seen seen}
                     (let [file-ast (parse-interned-file file-path (slurp file-path))
                           nested (resolve* canonical file-ast (conj seen canonical))
                              ;; Classes/functions declared directly in this file are
                              ;; qualified by the path *this* intern statement used to
                              ;; reach it; classes/functions coming from `nested` were
                              ;; already qualified, by their own path, when the inner
                              ;; recursive call resolved them.
                           direct-classes (stamp-qualified-names path canonical (:classes file-ast))
                           all-file-classes (concat direct-classes (:classes nested))
                           all-file-imports (concat (:imports file-ast) (:imports nested))
                              ;; Free functions defined in an interned module are
                              ;; brought into scope too, so a library can export
                              ;; helper/combinator functions, not just classes.
                           direct-functions (stamp-qualified-names path canonical (:functions file-ast))
                           all-file-functions (concat direct-functions (:functions nested))
                           all-file-type-aliases (concat (:type-aliases file-ast) (:type-aliases nested))
                              ;; Points at the *qualified* name (qualify-name path
                              ;; class-name), not the bare class-name — an unpathed
                              ;; intern makes this a no-op (qualify-name returns the
                              ;; bare name unchanged when path is empty), but for a
                              ;; pathed intern (`intern x/A as x_a`) it is the only
                              ;; way the alias is actually useful when the bare name
                              ;; collides: a bare :type-expr sends env-lookup-class's
                              ;; alias fallback through the same (possibly ambiguous)
                              ;; bare-name resolution a direct `A` reference would hit
                              ;; — see docs/proposals/namespaces.md, Phase 3 — so
                              ;; `intern x/A as x_a` alongside `intern y/A as y_a`
                              ;; left BOTH aliases broken instead of disambiguating
                              ;; anything. The qualified key is always registered
                              ;; (Phase 3's qualified-class-defs / stamp-qualified-names
                              ;; above), ambiguous bare name or not, so resolving
                              ;; through it instead is strictly safer, not just a fix
                              ;; for the colliding case.
                           alias-type-alias (when (and alias
                                                       (some #(= (:name %) class-name) all-file-classes))
                                              [{:name alias :type-expr (qualify-name path class-name)}])]
                       {:classes (into classes all-file-classes)
                        :imports (into imports all-file-imports)
                        :functions (into functions all-file-functions)
                        :type-aliases (into type-aliases (concat all-file-type-aliases alias-type-alias))
                        :seen (:seen nested)}))))
               {:classes [] :imports [] :functions [] :type-aliases [] :seen seen}
               (:interns current-program))))]
    (resolve* source-id program seen-files)))

(defn resolve-interned-classes
  "Resolve intern declarations to the class ASTs they bring into scope for static analysis.
      Returns a flat sequence of class definitions, including recursively interned classes.
      Aliased interns are represented as an additional class entry with the alias name."
  ([source-id program]
   (resolve-interned-classes source-id program #{}))
  ([source-id program seen-files]
   (:classes (resolve-interned* source-id program seen-files))))

(defn resolve-interned-imports
  "Resolve intern declarations to the import declarations they bring into scope
      for static analysis (recursively, deduplicated). These let the typechecker
      see the host-class imports declared inside interned modules."
  ([source-id program]
   (resolve-interned-imports source-id program #{}))
  ([source-id program seen-files]
   (distinct (:imports (resolve-interned* source-id program seen-files)))))

(defn resolve-interned-functions
  "Resolve intern declarations to the free-function definitions they bring into
      scope (recursively), so the typechecker and compiled backend can see a
      library's exported functions. The runtime interpreter registers them when it
      evaluates the interned module, so this is only needed for static analysis
      and compilation."
  ([source-id program]
   (resolve-interned-functions source-id program #{}))
  ([source-id program seen-files]
   (:functions (resolve-interned* source-id program seen-files))))

(defn resolve-interned-type-aliases
  "Resolve intern declarations to the `declare type` aliases (including
      refinement types) they bring into scope for static analysis
      (recursively). check-program only ever reads :type-aliases off the root
      program's own parse, so a refinement type declared in an interned file
      needs to be merged in here the same way resolve-interned-classes/
      -imports/-functions already are. The runtime interpreter registers the
      alias itself when it evaluates the interned module (an ordinary
      top-level statement), so this is only needed for static analysis and
      compilation, exactly like resolve-interned-functions."
  ([source-id program]
   (resolve-interned-type-aliases source-id program #{}))
  ([source-id program seen-files]
   (:type-aliases (resolve-interned* source-id program seen-files))))
