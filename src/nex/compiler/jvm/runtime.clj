(ns nex.compiler.jvm.runtime
  "Small runtime support for the future JVM bytecode compiler."
  (:require [clojure.string :as str]
            [nex.interpreter :as interp]
            [nex.types.runtime :as rt]
            [nex.types.value :as value]
            [nex.types.typeinfo :as typeinfo])
  (:import [clojure.lang DynamicClassLoader]
           [java.lang.reflect Field Method]
           [java.util HashMap]))

(defrecord NexReplState [^clojure.lang.Atom values
                         ^clojure.lang.Atom types
                         ^clojure.lang.Atom functions
                         ^clojure.lang.Atom output
                         ^clojure.lang.Atom classes
                         ^clojure.lang.Atom imports
                         ^clojure.lang.Atom counter
                         ^DynamicClassLoader loader])

(defn make-repl-state
  ([] (make-repl-state nil))
  ([loader]
   (->NexReplState (atom (HashMap.))
                   (atom (HashMap.))
                   (atom (HashMap.))
                   (atom [])
                   (atom (HashMap.))
                   (atom [])
                   (atom 0)
                   loader)))

(defn state-get-value
  [state name]
  (.get ^HashMap @(:values state) name))

(defn state-set-value!
  [state name value]
  (swap! (:values state)
         (fn [^HashMap m]
           (doto m
             (.put name value))))
  value)

(defn state-get-type
  [state name]
  (.get ^HashMap @(:types state) name))

(defn state-set-type!
  [state name nex-type]
  (swap! (:types state)
         (fn [^HashMap m]
           (doto m
             (.put name nex-type))))
  nex-type)

(defn state-get-fn
  [state name]
  (.get ^HashMap @(:functions state) name))

(defn state-set-fn!
  [state name fn-wrapper]
  (swap! (:functions state)
         (fn [^HashMap m]
           (doto m
             (.put name fn-wrapper))))
  fn-wrapper)

(defn register-repl-fn!
  [state name owner-binary-name method-name]
  (state-set-fn! state name {:owner owner-binary-name
                             :method method-name}))

(defn- resolve-owner-class
  [state owner-binary-name]
  (if-let [^DynamicClassLoader loader (:loader state)]
    (.loadClass loader owner-binary-name)
    (Class/forName owner-binary-name)))

(defn invoke-repl-fn
  [state name args]
  (let [{:keys [owner method]} (state-get-fn state name)]
    (when-not (and owner method)
      (throw (ex-info (str "Undefined compiled REPL function: " name)
                      {:name name})))
    (let [^Class owner-class (resolve-owner-class state owner)
          ^Method target-method (.getDeclaredMethod owner-class
                                                    method
                                                    (into-array Class [nex.compiler.jvm.runtime.NexReplState
                                                                       (class (object-array 0))]))]
      (.invoke target-method nil (object-array [state (object-array args)])))))

(defn next-class-name!
  ([state prefix]
   (next-class-name! state "nex/repl" prefix))
  ([state package prefix]
   (let [n (swap! (:counter state) inc)]
     (format "%s/%s_%04d" package prefix n))))

(defn clear-output!
  [state]
  (reset! (:output state) [])
  state)

(defn state-output
  [state]
  @(:output state))

(defn state-set-classes!
  [state class-map]
  (reset! (:classes state)
          (let [copy (HashMap.)]
            (doseq [[k v] class-map]
              (.put copy k v))
            copy))
  state)

(defn state-set-imports!
  [state imports]
  (reset! (:imports state) (vec imports))
  state)

(defn- rebuild-interpreter-ctx
  [state]
  (let [ctx (interp/make-context)]
    (reset! (:bindings (:globals ctx)) {})
    (reset! (:output ctx) @(:output state))
    (reset! (:imports ctx) (vec @(:imports state)))
    (reset! (:classes ctx)
            (let [copy (HashMap.)]
              (doseq [[k v] @(:classes state)]
                (.put copy k v))
              copy))
    (doseq [[k v] @(:values state)]
      (interp/env-define (:globals ctx) k v))
    ctx))

(defn- lowered-instance-method-name
  [method-name arity]
  (str "__method_" method-name "$arity" arity))

(defn- reflected-field
  [^Class cls field-name]
  (or (try
        (.getField cls field-name)
        (catch Exception _ nil))
      (some (fn [^Field f]
              (when (= (.getName f) field-name)
                (.setAccessible f true)
                f))
            (.getDeclaredFields cls))))

(defn- composition-fields
  [^Class cls]
  (->> (.getDeclaredFields cls)
       (filter (fn [^Field f] (str/starts-with? (.getName f) "_parent_")))
       (map (fn [^Field f]
              (.setAccessible f true)
              f))))

(defn- deep-reflected-field
  [value field-name]
  (or (when-let [^Field f (reflected-field (.getClass value) field-name)]
        [value f])
      (some (fn [^Field parent-field]
              (when-let [parent-value (.get parent-field value)]
                (deep-reflected-field parent-value field-name)))
            (composition-fields (.getClass value)))))

(defn- invoke-user-method
  [state target method-name args]
  (let [^Class cls (.getClass target)
        lowered-name (lowered-instance-method-name method-name (count args))
        ^Method method (.getDeclaredMethod cls
                                          lowered-name
                                          (into-array Class [nex.compiler.jvm.runtime.NexReplState
                                                             (class (object-array 0))]))]
    (.invoke method target (object-array [state (object-array args)]))))

(defn- get-user-field
  [target field-name]
  (let [[owner ^Field field] (or (deep-reflected-field target field-name)
                                 (throw (ex-info (str "Undefined compiled field: " field-name)
                                                 {:field field-name
                                                  :class (.getName (.getClass target))})))]
    (.get field owner)))

(defn- set-user-field!
  [target field-name value]
  (let [[owner ^Field field] (or (deep-reflected-field target field-name)
                                 (throw (ex-info (str "Undefined compiled field: " field-name)
                                                 {:field field-name
                                                  :class (.getName (.getClass target))})))]
    (.set field owner value)
    nil))

(defn make-raised-exception
  [value]
  (ex-info (str value) {:type :nex-exception :value value}))

(defn make-retry-signal
  []
  (ex-info "retry" {:type :nex-retry}))

(defn retry-signal?
  [throwable]
  (and (instance? clojure.lang.ExceptionInfo throwable)
       (= :nex-retry (:type (ex-data throwable)))))

(defn exception-value
  [throwable]
  (if (and (instance? clojure.lang.ExceptionInfo throwable)
           (= :nex-exception (:type (ex-data throwable))))
    (:value (ex-data throwable))
    (.getMessage ^Throwable throwable)))

(defn- compiled-runtime-class-name
  [state value]
  (when value
    (let [binary-name (.getName (.getClass value))
          simple-name (last (str/split binary-name #"\."))]
      (when-let [[_ candidate] (re-matches #"(.+)_\d{4}" simple-name)]
        (when (contains? @(:classes state) candidate)
          candidate)))))

(defn- runtime-type-name
  [state value]
  (or (compiled-runtime-class-name state value)
      (typeinfo/runtime-type-name interp/nex-object? typeinfo/get-type-name value)))

(defn convert-value
  [state value target-type-name]
  (let [ctx (rebuild-interpreter-ctx state)
        runtime-name (runtime-type-name state value)
        ok? (and (some? value)
                 (string? target-type-name)
                 (typeinfo/convert-compatible-runtime? interp/is-parent? ctx runtime-name target-type-name))]
    (object-array [(boolean ok?) (if ok? value nil)])))

(defn make-contract-violation
  [kind label]
  (ex-info (if (= kind "Loop variant")
             "Loop variant must decrease"
             (str kind " violation: " label))
           {:type :nex-contract-violation
            :kind kind
            :label label}))

(defn- class-def-by-name
  [state class-name]
  (.get ^HashMap @(:classes state) class-name))

(defn- collect-effective-field-names
  [state class-def]
  (let [class-map @(:classes state)]
    (letfn [(collect [cls seen]
              (let [class-name (:name cls)
                    already? (and class-name (contains? seen class-name))
                    seen' (if class-name (conj seen class-name) seen)]
                (if already?
                  [[] seen]
                  (let [[parent-fields seen'']
                        (if-let [parents (:parents cls)]
                          (reduce (fn [[acc seen-so-far] {:keys [parent]}]
                                    (if-let [parent-def (.get ^HashMap class-map parent)]
                                      (let [[fields seen-next] (collect parent-def seen-so-far)]
                                        [(into acc fields) seen-next])
                                      [acc seen-so-far]))
                                  [[] seen']
                                  parents)
                          [[] seen'])
                        local-fields (->> (:body cls)
                                          (filter #(= :feature-section (:type %)))
                                          (mapcat :members)
                                          (filter #(and (= :field (:type %))
                                                        (not (:constant? %))))
                                          (map :name)
                                          vec)]
                    [(vec (concat parent-fields local-fields)) seen'']))))]
      (->> (first (collect class-def #{}))
           distinct
           vec))))

(defn validate-object-state
  [state class-name value]
  (when-not (some? value)
    (throw (ex-info "Cannot validate nil object on compiled path"
                    {:class-name class-name})))
  (let [class-def (class-def-by-name state class-name)]
    (when-not class-def
      (throw (ex-info "Missing compiled class metadata for object validation"
                      {:class-name class-name})))
    (let [ctx (rebuild-interpreter-ctx state)
          runtime-name (runtime-type-name state value)
          compatible? (and (string? class-name)
                           (string? runtime-name)
                           (typeinfo/convert-compatible-runtime?
                            interp/is-parent?
                            ctx
                            runtime-name
                            class-name))]
      (when-not compatible?
        (throw (ex-info "Compiled object model mismatch"
                        {:expected class-name
                         :runtime runtime-name})))
      (let [inv-env (interp/make-env (:current-env ctx))]
        (doseq [field-name (collect-effective-field-names state class-def)]
          (interp/env-define inv-env field-name (get-user-field value field-name)))
        (interp/check-class-invariant (assoc ctx :current-env inv-env) class-def))
      value)))

(defn- concat-string-value
  [state value]
  (cond
    (string? value) value

    (nil? value) "nil"

    :else
    (let [string-value
          (try
            (let [result (invoke-user-method state value "to_string" [])]
              (if (string? result)
                result
                (interp/nex-format-value result)))
            (catch Exception _
              (interp/call-builtin-method nil nil value "to_string" [])))]
      (if (string? string-value)
        string-value
        (interp/nex-format-value string-value)))))

(defn format-value
  [value]
  (interp/nex-format-value value))

(defn deep-equals
  [a b]
  (value/nex-deep-equals? interp/nex-object? a b))

(defn clone-value
  [value]
  (value/nex-clone-value interp/nex-object? interp/make-object value))

(defn array-contains
  [values needle]
  (boolean (some #(deep-equals % needle) values)))

(defn array-index-of
  [values needle]
  (loop [idx 0
         remaining (seq values)]
    (cond
      (nil? remaining) -1
      (deep-equals (first remaining) needle) idx
      :else (recur (inc idx) (next remaining)))))

(defn map-contains-key
  [values needle]
  (boolean
   (some #(deep-equals (.getKey ^java.util.Map$Entry %) needle)
         (.entrySet ^java.util.Map values))))

(defn set-contains
  [values needle]
  (boolean (some #(deep-equals % needle) values)))

(defn map-get
  [state m key]
  (let [ctx (rebuild-interpreter-ctx state)]
    (interp/call-builtin-method ctx m m "get" [key])))

(defn map-try-get
  [state m key default]
  (let [ctx (rebuild-interpreter-ctx state)]
    (interp/call-builtin-method ctx m m "try_get" [key default])))

(defn array-sort
  [state values]
  (let [ctx (rebuild-interpreter-ctx state)
        result (interp/call-builtin-method ctx values values "sort" [])]
    (reset! (:output state) @(:output ctx))
    result))

(defn array-join
  [state values sep]
  (let [ctx (rebuild-interpreter-ctx state)
        result (interp/call-builtin-method ctx values values "join" [sep])]
    (reset! (:output state) @(:output ctx))
    result))

(defn collection-cursor
  [state kind value]
  (let [ctx (rebuild-interpreter-ctx state)
        result (interp/call-builtin-method ctx value value "cursor" [])]
    (reset! (:output state) @(:output ctx))
    result))

(defn array-to-string
  [values]
  (rt/nex-array-str format-value values))

(defn map-to-string
  [values]
  (rt/nex-map-str format-value values))

(defn set-to-string
  [values]
  (rt/nex-set-str format-value values))

(defn set-union
  [a b]
  (rt/nex-set-union a b))

(defn set-difference
  [a b]
  (rt/nex-set-difference a b))

(defn set-intersection
  [a b]
  (rt/nex-set-intersection a b))

(defn set-symmetric-difference
  [a b]
  (rt/nex-set-symmetric-difference a b))

(defn invoke-builtin
  [state name args]
  (cond
    (= name "validate-object-state")
    (validate-object-state state (first args) (second args))

    (= name "op:string-concat")
    (apply str (map #(concat-string-value state %) args))

    (= name "op:pow-int")
    (int (rt/nex-int-pow (int (first args)) (int (second args))))

    (= name "op:pow-long")
    (long (rt/nex-int-pow (long (first args)) (long (second args))))

    (= name "op:pow-double")
    (Math/pow (double (first args)) (double (second args)))

    :else
    (let [ctx (rebuild-interpreter-ctx state)]
      (if (str/starts-with? name "method:")
      (let [method-name (subs name (count "method:"))
            target (first args)
            method-args (rest args)
            result (interp/call-builtin-method ctx target target method-name method-args)]
        (reset! (:output state) @(:output ctx))
        result)
      (cond
        (str/starts-with? name "user-method:")
        (invoke-user-method state (first args) (subs name (count "user-method:")) (rest args))

        (str/starts-with? name "user-field-get:")
        (get-user-field (first args) (subs name (count "user-field-get:")))

        (str/starts-with? name "user-field-set:")
        (set-user-field! (first args) (subs name (count "user-field-set:")) (second args))

        :else
      (let [builtin-fn (get interp/builtins name)]
        (when-not builtin-fn
          (throw (ex-info (str "Undefined compiled builtin: " name) {:name name})))
        (let [result (apply builtin-fn ctx args)]
          (reset! (:output state) @(:output ctx))
          result)))))))

(defn invoke_builtin
  [state name args]
  (invoke-builtin state name args))
