(ns nex.interpreter
  (:require [clojure.string :as str]
            #?(:clj [nex.parser :as parser])
            [nex.types.runtime :as rt]
            [nex.types.json :as json-types]
            [nex.types.http :as http]
            [nex.types.datetime :as dt]
            [nex.types.regex :as regex-types]
            [nex.types.value :as value]
            [nex.types.typeinfo :as typeinfo]
            [nex.types.bootstrap :as bootstrap])
  #?(:clj (:import [java.lang.reflect Field]
                   [java.nio.charset StandardCharsets]
                   [java.util.concurrent CompletableFuture ExecutionException Executors TimeUnit TimeoutException CancellationException]
                   [java.util.concurrent.atomic AtomicBoolean AtomicInteger AtomicLong AtomicReference])))

(declare nex-format-value)
(declare eval-node)
(declare runtime-resolve-call-user-method)
(declare eval-node-async)
(declare lookup-method-with-inheritance)
(declare lookup-class)
(declare lookup-class-if-exists)
(declare call-builtin-method)
(declare eval-node)
(declare eval-node)
(declare nex-ordering-compare)
(declare make-object)
(declare invoke-http-server-handler)
(declare nex-object?)

(defn- lowercase-filename
  [class-name]
  (-> class-name str/lower-case))

(defn- intern-filenames
  [class-name]
  (distinct [(str class-name ".nex")
             (str (lowercase-filename class-name) ".nex")]))

;;
;; Runtime type helpers imported from nex.types.*
;;

(def nex-array rt/nex-array)
(def nex-array-from rt/nex-array-from)
(def nex-array? rt/nex-array?)
(def nex-array-get rt/nex-array-get)
(def nex-array-add rt/nex-array-add)
(def nex-array-add-at rt/nex-array-add-at)
(def nex-array-set rt/nex-array-set)
(def nex-array-size rt/nex-array-size)
(def nex-array-empty? rt/nex-array-empty?)
(def nex-array-contains rt/nex-array-contains)
(def nex-array-index-of rt/nex-array-index-of)
(def nex-array-remove rt/nex-array-remove)
(def nex-array-reverse rt/nex-array-reverse)
(def nex-array-sort rt/nex-array-sort)
(def nex-array-slice rt/nex-array-slice)
(defn nex-array-str [arr] (rt/nex-array-str nex-format-value arr))

(def nex-map rt/nex-map)
(def nex-map-from rt/nex-map-from)
(def nex-map? rt/nex-map?)
(def nex-map-get rt/nex-map-get)
(def nex-map-put rt/nex-map-put)
(def nex-map-size rt/nex-map-size)
(def nex-map-empty? rt/nex-map-empty?)
(def nex-map-contains-key rt/nex-map-contains-key)
(def nex-map-keys rt/nex-map-keys)
(def nex-map-values rt/nex-map-values)
(def nex-map-remove rt/nex-map-remove)
(defn nex-map-str [m] (rt/nex-map-str nex-format-value m))

(def nex-set rt/nex-set)
(def nex-set-from rt/nex-set-from)
(def nex-set? rt/nex-set?)
(def nex-set-contains rt/nex-set-contains)
(def nex-set-size rt/nex-set-size)
(def nex-set-empty? rt/nex-set-empty?)
(def nex-set-union rt/nex-set-union)
(def nex-set-difference rt/nex-set-difference)
(def nex-set-intersection rt/nex-set-intersection)
(def nex-set-symmetric-difference rt/nex-set-symmetric-difference)
(defn nex-set-str [s] (rt/nex-set-str nex-format-value s))

(def nex-bitwise-left-shift rt/nex-bitwise-left-shift)
(def nex-bitwise-right-shift rt/nex-bitwise-right-shift)
(def nex-bitwise-logical-right-shift rt/nex-bitwise-logical-right-shift)
(def nex-bitwise-rotate-left rt/nex-bitwise-rotate-left)
(def nex-bitwise-rotate-right rt/nex-bitwise-rotate-right)
(def nex-bitwise-and rt/nex-bitwise-and)
(def nex-bitwise-or rt/nex-bitwise-or)
(def nex-bitwise-xor rt/nex-bitwise-xor)
(def nex-bitwise-not rt/nex-bitwise-not)
(def nex-bitwise-is-set rt/nex-bitwise-is-set)
(def nex-bitwise-set rt/nex-bitwise-set)
(def nex-bitwise-unset rt/nex-bitwise-unset)
(def nex-abs rt/nex-abs)
(def nex-round rt/nex-round)
(def nex-int-pow rt/nex-int-pow)

(def nex-console-print rt/nex-console-print)
(def nex-console-println rt/nex-console-println)
(def nex-console-error rt/nex-console-error)
(def nex-console-newline rt/nex-console-newline)
(def nex-console-flush rt/nex-console-flush)
(def nex-console-read-line rt/nex-console-read-line)
(def nex-parse-integer64-string rt/nex-parse-integer64-string)
(def nex-parse-integer rt/nex-parse-integer)
(def nex-parse-real rt/nex-parse-real)

(def nex-process-getenv rt/nex-process-getenv)
(def nex-process-setenv rt/nex-process-setenv)
(def nex-process-command-line rt/nex-process-command-line)

#?(:clj
   (defn- http-response-headers->nex-map
     [headers]
     (http/http-response-headers->nex-map headers)))

#?(:clj
   (defn- make-http-response-object
     [status body headers]
     (http/make-http-response-object make-object status body headers)))

#?(:clj
   (defn- make-http-server-request-object
     [method-name path-value body-text header-map route-params query-map]
     (http/make-http-server-request-object make-object method-name path-value body-text header-map route-params query-map)))

#?(:clj
   (defn- make-http-server-default-response-object
     []
     (http/make-http-server-default-response-object make-object)))

#?(:clj
   (defn- http-exchange-headers->nex-map
     [headers]
     (http/http-exchange-headers->nex-map headers)))

#?(:clj
   (defn- java-http-request
     [method url body timeout-ms]
     (http/java-http-request make-object method url body timeout-ms)))

#?(:clj
   (defn- make-http-server-handle
     [port]
     (http/make-http-server-handle port)))

#?(:clj (def url-decode http/url-decode))
#?(:clj (def path-segments http/path-segments))
#?(:clj (def parse-query-map http/parse-query-map))
#?(:clj (def route-match http/route-match))
#?(:clj (def find-route http/find-route))
#?(:clj (def http-server-response-status http/http-server-response-status))
#?(:clj (def http-server-response-body http/http-server-response-body))
#?(:clj (def http-server-response-headers http/http-server-response-headers))

#?(:clj
   (defn- start-http-server!
     [ctx handle]
     (http/start-http-server!
      make-object
      (fn [inner-ctx handler request-obj]
        (invoke-http-server-handler inner-ctx handler request-obj))
      ctx
      handle)))

#?(:clj
   (defn- invoke-http-server-handler
     [ctx handler request-obj]
     (eval-node ctx {:type :call
                     :target {:type :literal :value handler}
                     :method "call1"
                     :args [{:type :literal :value request-obj}]})))


;; Built-in IO / cursor / primitive predicates imported from nex.types.runtime
(def nex-console? rt/nex-console?)
(def nex-process? rt/nex-process?)
(def nex-task? rt/nex-task?)
(def nex-channel? rt/nex-channel?)
(def nex-array-cursor? rt/nex-array-cursor?)
(def nex-string-cursor? rt/nex-string-cursor?)
(def nex-map-cursor? rt/nex-map-cursor?)
(def nex-set-cursor? rt/nex-set-cursor?)
(def nex-coll-get rt/nex-coll-get)
(def nex-char? rt/nex-char?)

;;
;; Runtime Environment
;;

(defrecord Environment [bindings parent])

(defn make-env
  "Create a new environment, optionally with a parent scope."
  ([]
   (make-env nil))
  ([parent]
   (->Environment (atom {}) parent)))

(defn env-lookup
  "Look up a variable in the environment, searching parent scopes if needed."
  [env var-name]
  (let [bindings @(:bindings env)]
    (if (contains? bindings var-name)
      (get bindings var-name)
      (if-let [parent (:parent env)]
        (env-lookup parent var-name)
        (throw (ex-info (str "Undefined variable: " var-name)
                        {:var-name var-name}))))))

(defn env-define
  "Define a variable in the current environment."
  [env var-name value]
  (swap! (:bindings env) assoc var-name value)
  value)

(defn env-set!
  "Set a variable in the environment where it's defined."
  [env var-name value]
  (if (contains? @(:bindings env) var-name)
    (env-define env var-name value)
    (if-let [parent (:parent env)]
      (env-set! parent var-name value)
      (throw (ex-info (str "Cannot assign to undefined variable: " var-name)
                      {:var-name var-name})))))

(defn- env-replace-object-aliases!
  "Replace all bindings in the environment chain that still point at old-obj."
  [env old-obj new-obj]
  (when (and env (some? old-obj) (not (identical? old-obj new-obj)))
    (swap! (:bindings env)
           (fn [bindings]
             (reduce-kv (fn [m k v]
                          (assoc m k (if (identical? v old-obj) new-obj v)))
                        {}
                        bindings)))
    (when-let [parent (:parent env)]
      (env-replace-object-aliases! parent old-obj new-obj))))

(def ^:private write-back-target-key ::write-back-target)
(def ^:private write-back-source-key ::write-back-source)

(defn- make-literal-node
  [value]
  {:type :literal :value value})

(defn- infer-reference-target-expr
  "Infer an assignable target expression for an object result returned from a query.
   This supports query chains such as obj.child().grandchild().mutate()."
  [base-target current-obj result]
  (when (and base-target (nex-object? current-obj) (some? result))
    (some (fn [[field-key field-val]]
            (let [field-name (name field-key)
                  field-expr {:type :call
                              :target base-target
                              :method field-name
                              :args []
                              :has-parens false}]
              (cond
                (identical? field-val result)
                field-expr

                (nex-array? field-val)
                (when-let [idx (first (keep-indexed (fn [i item]
                                                      (when (identical? item result) i))
                                                    field-val))]
                  {:type :call
                   :target field-expr
                   :method "get"
                   :args [(make-literal-node idx)]
                   :has-parens true})

                (nex-map? field-val)
                (when-let [entry (some (fn [[k v]]
                                         (when (identical? v result) [k v]))
                                       field-val)]
                  {:type :call
                   :target field-expr
                   :method "get"
                   :args [(make-literal-node (first entry))]
                   :has-parens true})

                :else
                nil)))
          (:fields current-obj))))

(defn- annotate-reference-result
  [target-expr current-obj result]
  (if-let [origin (and (nex-object? result)
                       (infer-reference-target-expr target-expr current-obj result))]
    (try
      (with-meta result (assoc (meta result)
                               write-back-target-key origin
                               write-back-source-key result))
      (catch #?(:clj Exception :cljs :default) _
        result))
    result))

;;
;; Runtime Context (holds classes, globals, current environment)
;;

(defrecord Context [classes globals current-env output imports specialized-classes compiled-state])

(declare register-class)

(def build-function-base-class bootstrap/build-function-base-class)
(def build-cursor-base-class bootstrap/build-cursor-base-class)
(def build-comparable-base-class bootstrap/build-comparable-base-class)
(def build-any-base-class bootstrap/build-any-base-class)
(def build-hashable-base-class bootstrap/build-hashable-base-class)
(def build-builtin-scalar-class bootstrap/build-builtin-scalar-class)

(defn make-context
  "Create a new runtime context."
  []
  (let [globals (make-env)]
    (let [ctx (->Context
               (atom {})           ; classes registry
               globals             ; global environment
               globals             ; current environment starts as global
               (atom [])           ; output accumulator
               (atom [])           ; imports registry
               (atom {})           ; specialized classes cache
               (atom nil))]        ; compiled runtime state for fallback dispatch
      ;; Register built-in base classes
      (register-class ctx (build-any-base-class))
      (register-class ctx (build-function-base-class))
      (register-class ctx (build-cursor-base-class))
      (register-class ctx (build-comparable-base-class))
      (register-class ctx (build-hashable-base-class))
      (doseq [scalar ["String" "Integer" "Integer64" "Real" "Decimal" "Boolean" "Char"]]
        (register-class ctx (build-builtin-scalar-class scalar)))
      ctx)))

(defn register-class
  "Register a class definition in the context."
  [ctx class-def]
  (letfn [(cycle-path [class-name start-parent]
            (letfn [(visit [current path seen]
                      (cond
                        (= current class-name)
                        (conj path current)

                        (contains? seen current)
                        nil

                        :else
                        (when-let [current-def (lookup-class-if-exists ctx current)]
                          (let [seen' (conj seen current)
                                path' (conj path current)]
                            (some #(visit (:parent %) path' seen')
                                  (:parents current-def))))))]
              (visit start-parent [class-name] #{class-name})))
          (validate-class! [registered-class]
            (let [class-name (:name registered-class)]
              (doseq [{:keys [parent]} (:parents registered-class)]
                (when (= parent class-name)
                  (throw (ex-info (str "Class " class-name " cannot inherit from itself")
                                  {:class-name class-name
                                   :parent parent})))
                (when-let [path (cycle-path class-name parent)]
                  (throw (ex-info (str "Cyclic inheritance detected: "
                                       (str/join " -> " path))
                                  {:class-name class-name
                                   :cycle path}))))))]
    (let [class-name (:name class-def)
          previous (get @(:classes ctx) class-name)]
      (swap! (:classes ctx) assoc class-name class-def)
      (try
        (validate-class! class-def)
        (catch Exception e
          (if previous
            (swap! (:classes ctx) assoc class-name previous)
            (swap! (:classes ctx) dissoc class-name))
          (throw e))))))

(defn lookup-class
  "Look up a class definition by name."
  [ctx class-name]
  (or (get @(:classes ctx) class-name)
      (get @(:specialized-classes ctx) class-name)
      (throw (ex-info (str "Undefined class: " class-name)
                      {:class-name class-name}))))

(defn lookup-class-if-exists
  "Look up a class definition by name, or nil if not found."
  [ctx class-name]
  (or (get @(:classes ctx) class-name)
      (get @(:specialized-classes ctx) class-name)))

#?(:clj
   (defn resolve-imported-java-class
     "Resolve a Java class name using imports in the context."
     [ctx class-name]
     (let [imports @(:imports ctx)
           match (some (fn [{:keys [qualified-name source]}]
                         (when (and (nil? source)
                                    qualified-name
                                    (= class-name (last (str/split qualified-name #"\."))))
                           qualified-name))
                       imports)
           qualified (or match class-name)]
       (try
         (Class/forName qualified)
         (catch Exception _ nil)))))

#?(:clj
   (defn java-create-object
     "Create a Java object via reflection."
     [ctx class-name arg-values]
     (let [klass (resolve-imported-java-class ctx class-name)]
       (when-not klass
         (throw (ex-info (str "Undefined class: " class-name)
                         {:class-name class-name})))
       (clojure.lang.Reflector/invokeConstructor klass (to-array arg-values)))))

#?(:clj
   (defn java-call-method
     "Call a Java method via reflection."
     [target method-name arg-values]
     (clojure.lang.Reflector/invokeInstanceMethod target method-name (to-array arg-values))))

#?(:clj
   (defn runtime-resolve-call-user-method
     [ctx target method-name arg-values]
     (let [resolver (requiring-resolve 'nex.compiler.jvm.runtime/call-compiled-user-method)
           compiled-state-slot (:compiled-state ctx)
           state (cond
                   (instance? clojure.lang.IDeref compiled-state-slot) @compiled-state-slot
                   (some? compiled-state-slot) compiled-state-slot
                   :else nil)]
       (resolver state target method-name arg-values))))

#?(:clj
   (defn- reflected-field
     [^Class cls field-name]
     (or (try
           (.getField cls field-name)
           (catch Exception _ nil))
         (some (fn [^Field field]
                 (when (= (.getName field) field-name)
                   (.setAccessible field true)
                   field))
               (.getDeclaredFields cls)))))

#?(:clj
   (defn- composition-fields
     [^Class cls]
     (->> (.getDeclaredFields cls)
          (filter (fn [^Field field] (str/starts-with? (.getName field) "_parent_")))
          (map (fn [^Field field]
                 (.setAccessible field true)
                 field)))))

#?(:clj
   (defn- deep-reflected-field
     [value field-name]
     (or (when-let [^Field field (reflected-field (.getClass value) field-name)]
           [value field])
         (some (fn [^Field parent-field]
                 (when-let [parent-value (.get parent-field value)]
                   (deep-reflected-field parent-value field-name)))
               (composition-fields (.getClass value))))))

#?(:clj
   (defn- compiled-object-field
     [value field-name]
     (when-let [[owner ^Field field] (deep-reflected-field value field-name)]
       [true (.get field owner)])))

#?(:clj
   (defn- compiled-runtime-class-name
     [ctx value]
     (when value
       (let [binary-name (.getName (.getClass value))
             simple-name (last (str/split binary-name #"\."))
             known-classes @(:classes ctx)]
         (cond
           (contains? known-classes simple-name)
           simple-name

           :else
           (when-let [[_ candidate] (re-matches #"(.+)_\d{4}" simple-name)]
             (when (contains? known-classes candidate)
               candidate)))))))

(defn register-specialized-class
  "Register a specialized (type-realized) class in the context."
  [ctx class-def]
  (swap! (:specialized-classes ctx) assoc (:name class-def) class-def))

(defn lookup-specialized-class
  "Look up a specialized class definition by name."
  [ctx class-name]
  (get @(:specialized-classes ctx) class-name))

;;
;; Generic Class Specialization
;;

(defn specialized-class-name
  "Build a specialized class name like Box[Integer]."
  [base-name type-args]
  (str base-name "[" (clojure.string/join "," type-args) "]"))

(defn substitute-type
  "Replace type parameter strings with concrete types using type-map."
  [type-expr type-map]
  (cond
    (string? type-expr)
    (get type-map type-expr type-expr)

    (map? type-expr)
    (-> type-expr
        (update :base-type #(get type-map % %))
        (update :type-args (fn [args]
                             (when args
                               (mapv #(substitute-type % type-map) args)))))

    :else type-expr))

(defn substitute-in-body
  "Walk class body sections, substituting types using type-map."
  [body type-map]
  (mapv (fn [section]
          (cond
            (= (:type section) :feature-section)
            (update section :members
                    (fn [members]
                      (mapv (fn [member]
                              (case (:type member)
                                :field
                                (update member :field-type #(substitute-type % type-map))

                                :method
                                (-> member
                                    (update :params
                                            (fn [params]
                                              (when params
                                                (mapv #(update % :type (fn [t] (substitute-type t type-map)))
                                                      params))))
                                    (update :return-type
                                            (fn [rt] (when rt (substitute-type rt type-map)))))

                                member))
                            members)))

            (= (:type section) :constructors)
            (update section :constructors
                    (fn [ctors]
                      (mapv (fn [ctor]
                              (update ctor :params
                                      (fn [params]
                                        (when params
                                          (mapv #(update % :type (fn [t] (substitute-type t type-map)))
                                                params)))))
                            ctors)))

            :else section))
        body))

(defn specialize-class
  "Create a specialized version of a generic class with concrete type args."
  [generic-class-def type-args]
  (let [generic-params (:generic-params generic-class-def)
        type-map (into {} (map (fn [param arg]
                                 [(:name param) arg])
                               generic-params type-args))
        spec-name (specialized-class-name (:name generic-class-def) type-args)]
    (-> generic-class-def
        (assoc :name spec-name)
        (assoc :template-name (:name generic-class-def))
        (assoc :generic-params nil)
        (update :body #(substitute-in-body % type-map)))))

(defn add-output
  "Add output to the context (for print statements)."
  [ctx value]
  (swap! (:output ctx) conj value))

;;
;; Object Representation
;;

(defrecord NexObject [class-name fields closure-env])

(defn make-object
  "Create a new object instance."
  ([class-name field-values]
   (make-object class-name field-values nil))
  ([class-name field-values closure-env]
   (->NexObject class-name field-values closure-env)))

#?(:clj
   (defonce ^:private concurrent-executor
     (Executors/newCachedThreadPool)))

#?(:clj
   (defn shutdown-runtime!
     "Release shared JVM runtime resources so short-lived tools and test runners can exit cleanly."
     []
     (.shutdown ^java.util.concurrent.ExecutorService concurrent-executor)
     (when-not (.awaitTermination ^java.util.concurrent.ExecutorService concurrent-executor 100 TimeUnit/MILLISECONDS)
       (.shutdownNow ^java.util.concurrent.ExecutorService concurrent-executor))))

#?(:cljs
   (defn shutdown-runtime!
     []
     nil))

(defn nex-object?
  "Check if a value is a Nex object instance."
  [v]
  (or (instance? NexObject v)
      (and (map? v) (contains? v :class-name) (contains? v :fields))))

#?(:clj
   (def ^:private channel-closed-signal ::channel-closed))

#?(:cljs
   (def ^:private channel-closed-signal ::channel-closed))

(def ^:private channel-timeout-signal ::channel-timeout)

(def ^:private task-timeout-signal ::task-timeout)

(defn- current-time-ms []
  #?(:clj (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn- timeout-ms
  [v]
  (let [n (cond
            (integer? v) v
            (number? v) (long v)
            :else nil)]
    (when (or (nil? n) (neg? n))
      (throw (ex-info "Timeout must be a non-negative Integer" {:timeout v})))
    n))

#?(:clj
   (defn- queue-empty [] clojure.lang.PersistentQueue/EMPTY))

#?(:clj
   (defn- queue-conj [q x]
     (conj (or q (queue-empty)) x)))

#?(:clj
   (defn- queue-pop [q]
     [(peek q) (pop q)]))

#?(:clj
   (defn- make-task [future]
     {:nex-builtin-type :Task
      :future future}))

#?(:cljs
   (defn- promise? [v]
     (instance? js/Promise v)))

#?(:cljs
   (defn- ->promise [v]
     (if (promise? v) v (js/Promise.resolve v))))

#?(:cljs
   (defn- promise-all [values]
     (.then (js/Promise.all (to-array (map ->promise values)))
            (fn [arr] (vec (array-seq arr))))))

#?(:cljs
   (defn- promise-reduce
     [items init f]
     (let [yield-step 25]
       (reduce (fn [acc [idx item]]
                 (.then (->promise acc)
                        (fn [state]
                          (.then (if (and (pos? idx) (zero? (mod idx yield-step)))
                                   (js/Promise. (fn [resolve _reject]
                                                  (js/setTimeout #(resolve nil) 0)))
                                   (js/Promise.resolve nil))
                                 (fn [_]
                                   (->promise (f state item)))))))
               (->promise init)
               (map-indexed vector items)))))

#?(:cljs
   (defn- yield-browser-async []
     (js/Promise. (fn [resolve _reject]
                    (js/setTimeout #(resolve nil) 0)))))

#?(:cljs
   (defn- make-task [promise]
     (let [done? (atom false)
           cancelled? (atom false)
           cancel-reject (atom nil)
           cancel-promise (js/Promise.
                           (fn [_resolve reject]
                             (reset! cancel-reject reject)))
           wrapped (.then (.race js/Promise (to-array [(->promise promise) cancel-promise]))
                          (fn [value]
                            (reset! done? true)
                            value)
                          (fn [err]
                            (reset! done? true)
                            (js/Promise.reject err)))]
       {:nex-builtin-type :Task
        :promise wrapped
        :done? done?
        :cancelled? cancelled?
        :cancel! (fn []
                   (if @done?
                     false
                     (do
                       (reset! cancelled? true)
                       (reset! done? true)
                       (when-let [reject @cancel-reject]
                         (reject (ex-info "Task cancelled" {:task :cancelled})))
                       true)))})))

#?(:clj
   (defn- make-channel
     ([] (make-channel 0))
     ([capacity]
     {:nex-builtin-type :Channel
      :lock (Object.)
      :state (atom {:closed? false
                    :capacity capacity
                    :buffer (queue-empty)
                    :senders (queue-empty)
                    :receivers (queue-empty)})})))

#?(:cljs
   (defn- make-channel
     ([] (make-channel 0))
     ([capacity]
     {:nex-builtin-type :Channel
      :state (atom {:closed? false
                    :capacity capacity
                    :buffer []
                    :senders []
                    :receivers []})})))

#?(:clj
   (defn- task-await
     ([task]
      (task-await task nil))
     ([task timeout]
      (try
        (if (nil? timeout)
          (.get ^CompletableFuture (:future task))
          (.get ^CompletableFuture (:future task) (timeout-ms timeout) TimeUnit/MILLISECONDS))
        (catch TimeoutException _ task-timeout-signal)
        (catch CancellationException _
          (throw (ex-info "Task cancelled" {:task task})))
        (catch ExecutionException e
          (throw (or (.getCause e) e)))
        (catch InterruptedException e
          (.interrupt (Thread/currentThread))
          (throw e))))))

#?(:cljs
   (defn- task-await
     ([task]
      (:promise task))
     ([task timeout]
      (.then (.race js/Promise
                    (to-array [(:promise task)
                               (js/Promise.
                                (fn [resolve _reject]
                                  (js/setTimeout #(resolve task-timeout-signal) (timeout-ms timeout))))]))
             identity))))

#?(:clj
   (defn- task-done? [task]
     (.isDone ^CompletableFuture (:future task))))

#?(:cljs
   (defn- task-done? [task]
     @(:done? task)))

#?(:clj
   (defn- await-all-tasks
     [tasks]
     (nex-array-from (map task-await tasks))))

#?(:cljs
   (defn- await-all-tasks
     [tasks]
     (.then (promise-all (map task-await tasks))
            (fn [results]
              (nex-array-from results)))))

#?(:clj
   (defn- await-any-task
     [tasks]
     (when (empty? tasks)
       (throw (ex-info "await_any requires at least one task" {})))
     (loop []
       (if-let [ready-task (some #(when (task-done? %) %) tasks)]
         (task-await ready-task)
         (do
           (Thread/sleep 1)
           (recur))))))

#?(:cljs
   (defn- await-any-task
     [tasks]
     (when (empty? tasks)
       (throw (ex-info "await_any requires at least one task" {})))
     (.race js/Promise (to-array (map task-await tasks)))))

#?(:clj
   (defn- task-cancel [task]
     (.cancel ^CompletableFuture (:future task) true)))

#?(:cljs
   (defn- task-cancel [task]
     ((:cancel! task))))

#?(:clj
   (defn- task-cancelled? [task]
     (.isCancelled ^CompletableFuture (:future task))))

#?(:cljs
   (defn- task-cancelled? [task]
     @(:cancelled? task)))

#?(:clj
   (defn- queue-remove-first
     [q pred]
     (reduce (fn [acc item]
               (let [{:keys [removed out]} acc]
                 (if (and (not removed) (pred item))
                   {:removed true :out out}
                   {:removed removed :out (queue-conj out item)})))
             {:removed false :out (queue-empty)}
             q)))

#?(:clj
   (defn- channel-send
     ([ch value]
      (channel-send ch value nil))
     ([ch value timeout]
      (let [ack (promise)
            timed? (some? timeout)
            deliver-now
            (locking (:lock ch)
              (let [{:keys [closed? receivers capacity buffer]} @(:state ch)]
                (when closed?
                  (throw (ex-info "Cannot send on a closed channel" {:channel ch})))
                (cond
                  (seq receivers)
                  (let [[receiver rest-receivers] (queue-pop receivers)]
                    (swap! (:state ch) assoc :receivers rest-receivers)
                    [:receiver receiver])
                  (< (count buffer) capacity)
                  (do
                    (swap! (:state ch) update :buffer queue-conj value)
                    [:buffered])
                  :else
                  (do
                    (swap! (:state ch) update :senders queue-conj {:value value :ack ack})
                    [:wait ack]))))]
        (case (first deliver-now)
          :buffered (if timed? true nil)
          :receiver (do (deliver (second deliver-now) value) (if timed? true nil))
          :wait (let [result (if timed?
                               (deref (second deliver-now) (timeout-ms timeout) channel-timeout-signal)
                               @(second deliver-now))]
                  (cond
                    (= result channel-closed-signal)
                    (throw (ex-info "Cannot send on a closed channel" {:channel ch}))

                    (= result channel-timeout-signal)
                    (do
                      (locking (:lock ch)
                        (swap! (:state ch) update :senders
                               (fn [q] (:out (queue-remove-first q #(identical? (:ack %) ack))))))
                      false)

                    :else (if timed? true nil))))))))

#?(:clj
   (defn- channel-try-send [ch value]
     (locking (:lock ch)
       (let [{:keys [closed? receivers capacity buffer]} @(:state ch)]
         (when closed?
           (throw (ex-info "Cannot send on a closed channel" {:channel ch})))
         (cond
           (seq receivers)
           (let [[receiver rest-receivers] (queue-pop receivers)]
             (swap! (:state ch) assoc :receivers rest-receivers)
             (deliver receiver value)
             true)

           (< (count buffer) capacity)
           (do
             (swap! (:state ch) update :buffer queue-conj value)
             true)

           :else false)))))

#?(:clj
   (defn- channel-receive
     ([ch]
      (channel-receive ch nil))
     ([ch timeout]
      (let [out (promise)
            timed? (some? timeout)
            ready
            (locking (:lock ch)
              (let [{:keys [closed? senders buffer capacity]} @(:state ch)]
                (cond
                  (seq buffer)
                  (let [[value rest-buffer] (queue-pop buffer)
                        promote (when (and (pos? capacity) (seq senders))
                                  (let [[sender rest-senders] (queue-pop senders)]
                                    (swap! (:state ch) assoc
                                           :senders rest-senders
                                           :buffer (queue-conj rest-buffer (:value sender)))
                                    sender))]
                    (when-not promote
                      (swap! (:state ch) assoc :buffer rest-buffer))
                    [:buffer value promote])
                  (seq senders)
                  (let [[sender rest-senders] (queue-pop senders)]
                    (swap! (:state ch) assoc :senders rest-senders)
                    [:sender sender])
                  closed?
                  [:closed]
                  :else
                  (do
                    (swap! (:state ch) update :receivers queue-conj out)
                    [:wait out]))))]
        (case (first ready)
          :buffer (let [[_ value promoted] ready]
                    (when promoted
                      (deliver (:ack promoted) true))
                    value)
          :sender (let [{:keys [value ack]} (second ready)]
                    (deliver ack true)
                    value)
          :closed (throw (ex-info "Cannot receive from a closed channel" {:channel ch}))
          :wait (let [result (if timed?
                               (deref (second ready) (timeout-ms timeout) channel-timeout-signal)
                               @(second ready))]
                  (cond
                    (= result channel-closed-signal)
                    (throw (ex-info "Cannot receive from a closed channel" {:channel ch}))

                    (= result channel-timeout-signal)
                    (do
                      (locking (:lock ch)
                        (swap! (:state ch) update :receivers
                               (fn [q] (:out (queue-remove-first q #(identical? % out))))))
                      nil)

                    :else result)))))))

#?(:clj
   (defn- channel-try-receive [ch]
     (locking (:lock ch)
       (let [{:keys [senders buffer capacity]} @(:state ch)]
         (cond
           (seq buffer)
           (let [[value rest-buffer] (queue-pop buffer)
                 promoted (when (and (pos? capacity) (seq senders))
                            (let [[sender rest-senders] (queue-pop senders)]
                              (swap! (:state ch) assoc
                                     :senders rest-senders
                                     :buffer (queue-conj rest-buffer (:value sender)))
                              sender))]
             (when-not promoted
               (swap! (:state ch) assoc :buffer rest-buffer))
             (when promoted
               (deliver (:ack promoted) true))
             value)

           (seq senders)
           (let [[sender rest-senders] (queue-pop senders)]
             (swap! (:state ch) assoc :senders rest-senders)
             (deliver (:ack sender) true)
             (:value sender))

           :else nil)))))

#?(:clj
   (defn- channel-close [ch]
     (locking (:lock ch)
       (let [{:keys [closed? senders receivers buffer]} @(:state ch)]
         (when-not closed?
           (swap! (:state ch) assoc :closed? true :senders (queue-empty)
                  :receivers (if (seq buffer) receivers (queue-empty)))
           (doseq [{:keys [ack]} senders]
             (deliver ack channel-closed-signal))
           (when-not (seq buffer)
             (doseq [receiver receivers]
               (deliver receiver channel-closed-signal)))))
       nil)))

#?(:cljs
   (defn- channel-send
     ([ch value]
      (channel-send ch value nil))
     ([ch value timeout]
      (js/Promise.
       (fn [resolve reject]
         (let [timed? (some? timeout)
               finish (fn [v] (resolve v))
               {:keys [closed? receivers capacity buffer]} @(:state ch)]
           (cond
             closed?
             (reject (ex-info "Cannot send on a closed channel" {:channel ch}))

             (seq receivers)
             (let [receiver (first receivers)]
               (swap! (:state ch) update :receivers #(vec (rest %)))
               ((:resolve receiver) value)
               (finish (when timed? true)))

             (< (count buffer) capacity)
             (do
               (swap! (:state ch) update :buffer conj value)
               (finish (when timed? true)))

             :else
             (let [id (str (gensym "__send"))
                   timer-id (atom nil)
                   entry {:id id
                          :value value
                          :resolve (fn [_]
                                     (when-let [timer @timer-id] (js/clearTimeout timer))
                                     (finish (when timed? true)))
                          :reject (fn [err]
                                    (when-let [timer @timer-id] (js/clearTimeout timer))
                                    (reject err))}]
               (swap! (:state ch) update :senders conj entry)
               (when timed?
                 (reset! timer-id
                         (js/setTimeout
                          (fn []
                            (swap! (:state ch) update :senders
                                   (fn [senders]
                                     (vec (remove #(= (:id %) id) senders))))
                            (finish false))
                          (timeout-ms timeout))))))))))))

#?(:cljs
   (defn- channel-try-send [ch value]
     (let [{:keys [closed? receivers capacity buffer]} @(:state ch)]
       (cond
         closed?
         (throw (ex-info "Cannot send on a closed channel" {:channel ch}))

         (seq receivers)
         (let [receiver (first receivers)]
           (swap! (:state ch) update :receivers #(vec (rest %)))
           ((:resolve receiver) value)
           true)

         (< (count buffer) capacity)
         (do
           (swap! (:state ch) update :buffer conj value)
           true)

         :else false))))

#?(:cljs
   (defn- channel-receive
     ([ch]
      (channel-receive ch nil))
     ([ch timeout]
      (js/Promise.
       (fn [resolve reject]
         (let [timed? (some? timeout)
               {:keys [closed? senders buffer capacity]} @(:state ch)]
           (cond
             (seq buffer)
             (let [buffered-value (first buffer)]
               (swap! (:state ch) update :buffer #(vec (rest %)))
               (when (and (pos? capacity) (seq (:senders @(:state ch))))
                 (let [{sender-value :value sender-resolve :resolve} (first (:senders @(:state ch)))]
                   (swap! (:state ch)
                          (fn [state]
                            (-> state
                                (update :senders #(vec (rest %)))
                                (update :buffer conj sender-value))))
                   (sender-resolve nil)))
               (resolve buffered-value))

             (seq senders)
             (let [{:keys [value] :as sender} (first senders)
                   ack-resolve (:resolve sender)]
               (swap! (:state ch) update :senders #(vec (rest %)))
               (ack-resolve nil)
               (resolve value))

             closed?
             (reject (ex-info "Cannot receive from a closed channel" {:channel ch}))

             :else
             (let [id (str (gensym "__recv"))
                   timer-id (atom nil)
                   entry {:id id
                          :resolve (fn [value]
                                     (when-let [timer @timer-id] (js/clearTimeout timer))
                                     (resolve value))
                          :reject (fn [err]
                                    (when-let [timer @timer-id] (js/clearTimeout timer))
                                    (reject err))}]
               (swap! (:state ch) update :receivers conj entry)
               (when timed?
                 (reset! timer-id
                         (js/setTimeout
                          (fn []
                            (swap! (:state ch) update :receivers
                                   (fn [receivers]
                                     (vec (remove #(= (:id %) id) receivers))))
                            (resolve nil))
                          (timeout-ms timeout))))))))))))

#?(:cljs
   (defn- channel-try-receive [ch]
     (let [{:keys [senders buffer capacity]} @(:state ch)]
       (cond
         (seq buffer)
         (let [buffered-value (first buffer)]
           (swap! (:state ch) update :buffer #(vec (rest %)))
           (when (and (pos? capacity) (seq (:senders @(:state ch))))
             (let [{sender-value :value sender-resolve :resolve} (first (:senders @(:state ch)))]
               (swap! (:state ch)
                      (fn [state]
                        (-> state
                            (update :senders #(vec (rest %)))
                            (update :buffer conj sender-value))))
               (sender-resolve nil)))
           buffered-value)

         (seq senders)
         (let [{:keys [value] :as sender} (first senders)
               ack-resolve (:resolve sender)]
           (swap! (:state ch) update :senders #(vec (rest %)))
           (ack-resolve nil)
           value)

         :else nil))))

#?(:cljs
   (defn- channel-close [ch]
     (let [{:keys [closed? senders receivers buffer]} @(:state ch)]
       (when-not closed?
         (swap! (:state ch) assoc :closed? true :senders [] :receivers (if (seq buffer) receivers []))
         (doseq [{:keys [reject]} senders]
           (reject (ex-info "Cannot send on a closed channel" {:channel ch})))
         (when-not (seq buffer)
           (doseq [{:keys [reject]} receivers]
             (reject (ex-info "Cannot receive from a closed channel" {:channel ch})))))
       nil)))

;;
;; Forward declarations
;;

(declare eval-node)
(declare eval-node-async)
(declare get-all-fields)
(declare get-all-constants)
(declare eval-body-with-rescue)
(declare lookup-constructor)
(declare get-parent-classes)
(declare combine-assertions)
(declare combine-preconditions)
(declare get-type-name)
(declare eval-body-async)

(defn- select-op-call
  [expr]
  (when (and (map? expr) (= :call (:type expr)))
    expr))

(defn- eval-select-target
  [ctx target]
  (let [target-name (when (string? target) target)
        class-target (when target-name (lookup-class-if-exists ctx target-name))]
    (if class-target
      nil
      (if target-name
        (env-lookup (:current-env ctx) target-name)
        (eval-node ctx target)))))

#?(:cljs
   (defn- eval-select-target-async
     [ctx target]
     (let [target-name (when (string? target) target)
           class-target (when target-name (lookup-class-if-exists ctx target-name))]
       (if class-target
         (js/Promise.resolve nil)
         (if target-name
           (js/Promise.resolve (env-lookup (:current-env ctx) target-name))
           (eval-node-async ctx target))))))

(defn- prepare-select-clause
  [ctx {:keys [expr alias body] :as clause}]
  (let [{:keys [target method args] :as call} (select-op-call expr)]
    {:method method
     :alias alias
     :body body
     :target (eval-select-target ctx target)
     :args (mapv #(eval-node ctx %) args)}))

#?(:cljs
   (defn- prepare-select-clause-async
     [ctx {:keys [expr alias body] :as clause}]
     (let [{:keys [target method args]} (select-op-call expr)]
       (.then (eval-select-target-async ctx target)
              (fn [target-val]
                (.then (promise-all (map #(eval-node-async ctx %) args))
                       (fn [arg-vals]
                         {:method method
                          :alias alias
                          :body body
                          :target target-val
                          :args arg-vals})))))))

(defn- execute-select-body
  [ctx body alias value]
  (let [body-ctx (if alias
                   (assoc ctx :current-env (doto (make-env (:current-env ctx))
                                            (env-define alias value)))
                   ctx)]
    (last (map #(eval-node body-ctx %) body))))

#?(:cljs
   (defn- execute-select-body-async
     [ctx body alias value]
     (let [body-ctx (if alias
                      (assoc ctx :current-env (doto (make-env (:current-env ctx))
                                               (env-define alias value)))
                      ctx)]
       (eval-body-async body-ctx body))))

(defn- attempt-select-clause
  [{:keys [method target args] :as prepared}]
  (cond
    (and (= (:nex-builtin-type target) :Task)
         (= method "await"))
    (when (task-done? target)
      {:selected? true
       :value (task-await target)})

    (#{"receive" "try_receive"} method)
    (let [value (channel-try-receive target)]
      (when (some? value)
        {:selected? true :value value}))

    (#{"send" "try_send"} method)
    (when (channel-try-send target (first args))
      {:selected? true})

    :else nil))

#?(:cljs
   (defn- attempt-select-clause-async
     [prepared]
     (let [{:keys [method target]} prepared]
       (cond
         (and (= (:nex-builtin-type target) :Task)
              (= method "await"))
         (if (task-done? target)
           (.then (task-await target)
                  (fn [value]
                    {:selected? true :value value}))
           (js/Promise.resolve nil))

         :else
         (js/Promise.resolve (attempt-select-clause prepared))))))

(defn- sleep-select-step! []
  #?(:clj (Thread/sleep 1)
     :cljs nil))

#?(:cljs
   (defn- sleep-select-step-async []
     (js/Promise.
      (fn [resolve _reject]
        (js/setTimeout resolve 0)))))

;;
;; Debugger Hooks
;;

(def ^:private debuggable-node-types
  #{:call :member-assign :assign :let :if :case :loop :raise :retry :scoped-block})

(defn debuggable-node?
  "Whether this node should trigger debugger pause checks."
  [node]
  (and (map? node)
       (contains? debuggable-node-types (:type node))))

(defn maybe-debug-pause
  "Invoke optional debugger hook before executing a debuggable node."
  [ctx node]
  (when (and (debuggable-node? node) (:debug-hook ctx))
    ((:debug-hook ctx) ctx node)))

;;
;; Contract Checking
;;

;; Contract types
(def Precondition "Precondition")
(def Postcondition "Postcondition")
(def Loop-invariant "Loop invariant")
(def Class-invariant "Class invariant")

(defn report-contract-violation
  [contract-type label condition]
  (throw (ex-info (str contract-type " violation: " label)
                  {:contract-type contract-type
                   :label label
                   :condition condition})))

(defn check-assertions
  "Check a list of assertions. Throws exception if any fail."
  [ctx assertions contract-type]
  (doseq [{:keys [label condition]} assertions]
    (let [result (eval-node ctx condition)]
      (when-not result
        (report-contract-violation contract-type label condition)))))

(defn check-class-invariant
  "Check the class invariant for an object or class context."
  [ctx class-def]
  (letfn [(collect-invariants [class-def seen]
            (let [class-name (:name class-def)
                  already-seen? (and class-name (contains? seen class-name))
                  seen' (if class-name (conj seen class-name) seen)]
              (if already-seen?
                [[] seen]
                (let [[parent-invariants seen'']
                      (if-let [parents (get-parent-classes ctx class-def)]
                        (reduce (fn [[acc seen-so-far] {parent-class-def :class-def}]
                                  (let [[inv seen-next] (collect-invariants parent-class-def seen-so-far)]
                                    [(into acc inv) seen-next]))
                                [[] seen']
                                parents)
                        [[] seen'])
                      local-invariants (or (:invariant class-def) [])]
                  [(vec (concat parent-invariants local-invariants)) seen'']))))]
    (let [[invariant-assertions _] (collect-invariants class-def #{})]
      (when (seq invariant-assertions)
        (check-assertions ctx invariant-assertions Class-invariant)))))

;;
;; Inheritance Support
;;

(defn get-parent-classes
  "Get the list of parent class definitions for a class."
  [ctx class-def]
  (when-let [parents (:parents class-def)]
    (mapv (fn [parent-info]
            (let [parent-class (lookup-class ctx (:parent parent-info))]
              (assoc parent-info :class-def parent-class)))
          parents)))

(defn feature-members
  "Return feature members with section visibility copied onto each member."
  [class-def]
  (mapcat (fn [section]
            (when (= (:type section) :feature-section)
              (map #(if (:visibility %)
                      %
                      (assoc % :visibility (:visibility section)))
                   (:members section))))
          (:body class-def)))

(defn public-member?
  [member]
  (not= :private (-> member :visibility :type)))

(defn- member-visible?
  [member declaring-class-name caller-class-name]
  (or (= caller-class-name declaring-class-name)
      (public-member? member)))

(defn get-all-constants
  "Collect accessible constants for a class:
   inherited public constants first, then local constants."
  [ctx class-def]
  (let [parent-constants (when-let [parents (get-parent-classes ctx class-def)]
                           (mapcat (fn [{:keys [class-def]}]
                                     (filter public-member?
                                             (get-all-constants ctx class-def)))
                                   parents))
        local-constants (->> (feature-members class-def)
                             (filter #(and (= (:type %) :field)
                                           (:constant? %)))
                             (map #(assoc % :declaring-class class-def)))
        merged (reduce (fn [m constant]
                         (assoc m (:name constant) constant))
                       {}
                       (concat parent-constants local-constants))]
    (vals merged)))

(defn lookup-class-constant
  "Look up a constant on a class and its parent chain.
   Local constants always apply; inherited constants must be public."
  [ctx class-def constant-name]
  (let [local-constant (some (fn [member]
                               (when (and (= (:type member) :field)
                                          (:constant? member)
                                          (= (:name member) constant-name))
                                 (assoc member :declaring-class class-def)))
                             (feature-members class-def))]
    (or local-constant
        (when-let [parents (get-parent-classes ctx class-def)]
          (some (fn [{:keys [class-def]}]
                  (some (fn [member]
                          (when (and (public-member? member)
                                     (= (:name member) constant-name))
                            member))
                        (get-all-constants ctx class-def)))
                parents)))))

(defn lookup-method-in-class
  "Look up a method in a specific class (without searching parents)."
  [class-def method-name arg-count]
  (->> (feature-members class-def)
       (filter #(= (:type %) :method))
       (filter #(= (:name %) method-name))
       (filter #(or (nil? arg-count)
                    (= (count (or (:params %) [])) arg-count)))
       first))

(defn lookup-method-with-inheritance
  "Look up a method in a class, searching parent classes if needed."
  ([ctx class-def method-name arg-count]
   (lookup-method-with-inheritance ctx class-def method-name arg-count nil))
  ([ctx class-def method-name arg-count caller-class-name]
   (let [method (lookup-method-in-class class-def method-name arg-count)
         accessible? (and method
                          (member-visible? method (:name class-def) caller-class-name))]
     (if accessible?
       (let [base-lookup (when-let [parents (get-parent-classes ctx class-def)]
                           (some (fn [parent-info]
                                   (lookup-method-with-inheritance ctx
                                                                   (:class-def parent-info)
                                                                   method-name
                                                                   arg-count
                                                                   caller-class-name))
                                 parents))
             effective-require (combine-preconditions (:effective-require base-lookup)
                                                      (:require method))
             effective-ensure (combine-assertions (:effective-ensure base-lookup)
                                                  (:ensure method))]
         {:method method
          :source-class class-def
          :effective-require effective-require
          :effective-ensure effective-ensure})
       (when-let [parents (get-parent-classes ctx class-def)]
         (some (fn [parent-info]
                 (lookup-method-with-inheritance ctx
                                                 (:class-def parent-info)
                                                 method-name
                                                 arg-count
                                                 caller-class-name))
               parents))))))

(defn- lookup-field-with-inheritance
  [ctx class-def field-name caller-class-name]
  (let [local-field (some (fn [member]
                            (when (and (= (:type member) :field)
                                       (not (:constant? member))
                                       (= (:name member) field-name)
                                       (member-visible? member (:name class-def) caller-class-name))
                              (assoc member :declaring-class (:name class-def))))
                          (feature-members class-def))]
    (or local-field
        (when-let [parents (get-parent-classes ctx class-def)]
          (some (fn [{:keys [class-def]}]
                  (lookup-field-with-inheritance ctx class-def field-name caller-class-name))
                parents)))))

(defn- lookup-field-with-inheritance-any-visibility
  [ctx class-def field-name]
  (let [local-field (some (fn [member]
                            (when (and (= (:type member) :field)
                                       (not (:constant? member))
                                       (= (:name member) field-name))
                              (assoc member :declaring-class (:name class-def))))
                          (feature-members class-def))]
    (or local-field
        (when-let [parents (get-parent-classes ctx class-def)]
          (some (fn [{:keys [class-def]}]
                  (lookup-field-with-inheritance-any-visibility ctx class-def field-name))
                parents)))))

(defn- field-write-error-message
  [field-name declaring-class]
  (str "Cannot assign to field " field-name
       " outside of class " declaring-class))

(defn- ensure-callable-defined!
  [callable]
  (when (:declaration-only? callable)
    (throw (ex-info (str "Function or method declared but not defined: " (:name callable))
                    {:name (:name callable)
                     :declaration-only? true}))))

(defn is-parent?
  "Check if parent-name appears in the parent chain of class-name."
  [ctx class-name parent-name]
  (letfn [(parent? [current seen]
            (when (and current (not (contains? seen current)))
              (when-let [class-def (lookup-class-if-exists ctx current)]
                (when-let [parents (:parents class-def)]
                  (let [seen' (conj seen current)]
                    (or (some #(= (:parent %) parent-name) parents)
                        (some #(parent? (:parent %) seen') parents)))))))]
    (parent? class-name #{})))

(defn- runtime-type-name [value]
  (typeinfo/runtime-type-name nex-object? get-type-name value))

(def numeric-subtype-runtime? typeinfo/numeric-subtype-runtime?)
(def cursor-subtype-runtime? typeinfo/cursor-subtype-runtime?)

(defn- runtime-type-is? [ctx target-type value]
  (typeinfo/runtime-type-is? runtime-type-name is-parent? ctx target-type value))

(defn- convert-compatible-runtime? [ctx runtime-type target-type]
  (typeinfo/convert-compatible-runtime? is-parent? ctx runtime-type target-type))

(defn eval-class-constant
  "Evaluate a class constant value with inherited constant bindings available."
  ([ctx class-def constant-name]
   (eval-class-constant ctx class-def constant-name (or (:constant-visiting ctx) #{})))
  ([ctx class-def constant-name visiting]
   (let [visit-key [(:name class-def) constant-name]]
     (when (contains? visiting visit-key)
       (throw (ex-info (str "Cyclic constant definition: " (:name class-def) "." constant-name)
                       {:class-name (:name class-def)
                        :constant constant-name})))
     (let [constant (lookup-class-constant ctx class-def constant-name)]
       (when-not constant
         (throw (ex-info (str "Undefined constant: " (:name class-def) "." constant-name)
                         {:class-name (:name class-def)
                          :constant constant-name})))
        (let [source-class (:declaring-class constant class-def)
              const-env (make-env (:globals ctx))
              next-visiting (conj visiting visit-key)
              eval-ctx (assoc ctx
                              :current-env const-env
                              :current-class-name (:name source-class)
                              :constant-visiting next-visiting)]
         (eval-node eval-ctx (:value constant)))))))

(defn bind-class-constants!
  "Bind all constants visible from class-def into env."
  [ctx env class-def]
  (doseq [constant (get-all-constants ctx class-def)]
    (env-define env
                (:name constant)
                (eval-class-constant ctx
                                     (:declaring-class constant class-def)
                                     (:name constant)))))

(defn combine-assertions
  "Combine assertions from parent and child methods (for contracts)."
  [parent-assertions child-assertions]
  (vec (concat (or parent-assertions []) (or child-assertions []))))

(defn assertions->condition
  "Collapse a list of assertions into a single condition using logical AND."
  [assertions]
  (when (seq assertions)
    (reduce (fn [acc {:keys [condition]}]
              (if acc
                {:type :binary
                 :operator "and"
                 :left acc
                 :right condition}
                condition))
            nil
            assertions)))

(defn combine-preconditions
  "Combine parent and child preconditions as:
   (parent-require) OR (child-require)."
  [parent-assertions child-assertions]
  (let [parent-assertions (seq parent-assertions)
        child-assertions (seq child-assertions)]
    (cond
      (and parent-assertions child-assertions)
      [{:label "inherited_or_local_require"
        :condition {:type :binary
                    :operator "or"
                    :left (assertions->condition parent-assertions)
                    :right (assertions->condition child-assertions)}}]

      parent-assertions
      (vec parent-assertions)

      child-assertions
      (vec child-assertions)

      :else
      nil)))

(defn nex-format-value [value]
  (value/nex-format-value nex-object? nex-map-str nex-array-str nex-set-str value))

(defn- nex-clone-value [value]
  (value/nex-clone-value nex-object? make-object value))

(defn- nex-map-entry-match? [m2 k1 v1]
  (value/nex-map-entry-match? nex-object? k1 v1 m2))

(defn- nex-deep-equals? [a b]
  (value/nex-deep-equals? nex-object? a b))

(defn- builtin-scalar-value?
  [v]
  (or (nil? v)
      (string? v)
      (number? v)
      (boolean? v)
      (char? v)))

(defn- membership-equals?
  [a b]
  (if (and (builtin-scalar-value? a) (builtin-scalar-value? b))
    (= a b)
    (nex-deep-equals? a b)))

(defn- nex-array-contains-value?
  [arr elem]
  (boolean
   (some #(membership-equals? % elem)
         #?(:clj (seq arr) :cljs (array-seq arr)))))

(defn- nex-array-index-of-value
  [arr elem]
  (loop [idx 0
         values #?(:clj (seq arr) :cljs (seq (array-seq arr)))]
    (cond
      (nil? values) -1
      (membership-equals? (first values) elem) idx
      :else (recur (inc idx) (next values)))))

(defn- nex-map-contains-key-value?
  [m key]
  (boolean
   (some #(membership-equals? #?(:clj (.getKey %) :cljs (first %)) key)
         #?(:clj (.entrySet m) :cljs (es6-iterator-seq (.entries m))))))

(defn- nex-set-contains-value?
  [s value]
  (boolean
   (some #(membership-equals? % value)
         #?(:clj (seq s) :cljs (es6-iterator-seq (.values s))))))

(defn- sortable-builtin-scalar-value?
  [v]
  (or (string? v)
      (number? v)
      (boolean? v)
      (char? v)))

(defn- nex-value-compare
  [ctx a b]
  (cond
    (and (sortable-builtin-scalar-value? a)
         (sortable-builtin-scalar-value? b))
    (nex-ordering-compare a b)

    (nex-object? a)
    (let [result (eval-node ctx {:type :call
                                 :target {:type :literal :value a}
                                 :method "compare"
                                 :args [{:type :literal :value b}]})]
      (if (number? result)
        result
        (throw (ex-info "Comparable.compare must return Integer"
                        {:left a :right b :result result}))))

    :else
    (throw (ex-info "Array.sort requires Comparable elements"
                    {:left a :right b}))))

(defn- nex-array-sort-with-ctx
  ([ctx arr]
   #?(:clj (let [out (java.util.ArrayList. arr)]
             (.sort out (reify java.util.Comparator
                          (compare [_ a b]
                            (int (nex-value-compare ctx a b)))))
             out)
      :cljs (let [out (.slice arr)]
              (.sort out (fn [a b] (nex-value-compare ctx a b)))
              out)))
  ([ctx arr comparator]
   (let [compare-fn (fn [a b]
                      (let [result (if (fn? comparator)
                                     (comparator a b)
                                     (eval-node ctx {:type :call
                                                     :target {:type :literal :value comparator}
                                                     :method "call2"
                                                     :args [{:type :literal :value a}
                                                            {:type :literal :value b}]}))]
                        (if (integer? result)
                          result
                          (throw (ex-info "Array.sort comparator must return Integer"
                                          {:left a :right b :result result})))))]
     #?(:clj (let [out (java.util.ArrayList. arr)]
               (.sort out (reify java.util.Comparator
                            (compare [_ a b]
                              (compare-fn a b))))
               out)
        :cljs (let [out (.slice arr)]
                (.sort out compare-fn)
                out)))))

(defn- make-min-heap
  [comparator]
  {:nex-builtin-type :MinHeap
   :data (atom [])
   :comparator comparator})

(defn- make-atomic-integer
  [initial]
  {:nex-builtin-type :AtomicInteger
   :state #?(:clj (AtomicInteger. (int initial))
             :cljs (atom initial))})

(defn- make-atomic-integer64
  [initial]
  {:nex-builtin-type :AtomicInteger64
   :state #?(:clj (AtomicLong. (long initial))
             :cljs (atom initial))})

(defn- make-atomic-boolean
  [initial]
  {:nex-builtin-type :AtomicBoolean
   :state #?(:clj (AtomicBoolean. (boolean initial))
             :cljs (atom initial))})

(defn- make-atomic-reference
  [initial]
  {:nex-builtin-type :AtomicReference
   :state #?(:clj (AtomicReference. initial)
             :cljs (atom initial))})

(defn- deep-equals-runtime?
  [a b]
  (value/nex-deep-equals? nex-object? a b))

(defn- atomic-reference-cas!
  [atomic expected update]
  #?(:clj (loop []
            (let [^AtomicReference state (:state atomic)
                  current (.get state)]
              (if (deep-equals-runtime? current expected)
                (if (.compareAndSet state current update)
                  true
                  (recur))
                false)))
     :cljs (let [state (:state atomic)]
             (loop []
               (let [current @state]
                 (if (deep-equals-runtime? current expected)
                   (do
                     (reset! state update)
                     true)
                   false))))))

(defn- heap-compare
  [ctx heap left right]
  (let [comparator (:comparator heap)]
    (if comparator
      (let [result (if (fn? comparator)
                     (comparator left right)
                     (eval-node ctx {:type :call
                                     :target {:type :literal :value comparator}
                                     :method "call2"
                                     :args [{:type :literal :value left}
                                            {:type :literal :value right}]}))]
        (if (integer? result)
          result
          (throw (ex-info "Min_Heap comparator must return Integer"
                          {:left left :right right :result result}))))
      (nex-value-compare ctx left right))))

(defn- heap-sift-up
  [ctx heap values idx]
  (loop [items values
         child idx]
    (if (zero? child)
      items
      (let [parent (quot (dec child) 2)
            child-value (nth items child)
            parent-value (nth items parent)]
        (if (neg? (heap-compare ctx heap child-value parent-value))
          (recur (-> items
                     (assoc child parent-value)
                     (assoc parent child-value))
                 parent)
          items)))))

(defn- heap-sift-down
  [ctx heap values idx]
  (let [n (count values)]
    (loop [items values
           parent idx]
      (let [left (+ (* 2 parent) 1)
            right (+ left 1)]
        (if (>= left n)
          items
          (let [smallest-child (if (and (< right n)
                                        (neg? (heap-compare ctx
                                                            heap
                                                            (nth items right)
                                                            (nth items left))))
                                 right
                                 left)
                parent-value (nth items parent)
                child-value (nth items smallest-child)]
            (if (neg? (heap-compare ctx heap child-value parent-value))
              (recur (-> items
                         (assoc parent child-value)
                         (assoc smallest-child parent-value))
                     smallest-child)
              items)))))))

(defn- heap-insert!
  [ctx heap value]
  (swap! (:data heap)
         (fn [items]
           (let [expanded (conj items value)]
             (heap-sift-up ctx heap expanded (dec (count expanded))))))
  nil)

(defn- heap-peek
  [heap]
  (let [items @(:data heap)]
    (when (seq items)
      (first items))))

(defn- heap-extract-min!
  [ctx heap]
  (let [items @(:data heap)]
    (when (seq items)
      (let [minimum (first items)
            last-value (peek items)
            remaining-count (dec (count items))
            replacement (if (zero? remaining-count)
                          []
                          (heap-sift-down ctx heap (assoc (pop items) 0 last-value) 0))]
        (reset! (:data heap) replacement)
        minimum))))

(defn nex-display-value [value]
  (value/nex-display-value nex-object? nex-format-value value))

(declare print-output-value)

;;
;; Built-in Functions
;;

(def builtins
  {"print"
   (fn [ctx & args]
     (let [output (str/join " " (map #(print-output-value ctx %) args))]
       (add-output ctx output)
       nil))

   "println"
   (fn [ctx & args]
     (let [output (str/join " " (map #(print-output-value ctx %) args))]
       (add-output ctx output)
       nil))

   "type_of"
   (fn [ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "type_of expects exactly 1 argument"
                       {:function "type_of" :expected 1 :actual (count args)})))
     (runtime-type-name (first args)))

   "type_is"
   (fn [ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "type_is expects exactly 2 arguments"
                       {:function "type_is" :expected 2 :actual (count args)})))
     (let [[target-type value] args]
       (runtime-type-is? ctx target-type value)))

   "await_all"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "await_all expects exactly 1 argument"
                       {:function "await_all" :expected 1 :actual (count args)})))
     (let [tasks (first args)]
       (when-not (nex-array? tasks)
         (throw (ex-info "await_all requires an array of tasks"
                         {:function "await_all" :actual-type (runtime-type-name tasks)})))
       (doseq [task tasks]
         (when-not (= (:nex-builtin-type task) :Task)
           (throw (ex-info "await_all requires an array of tasks"
                           {:function "await_all" :actual-type (runtime-type-name task)}))))
       (await-all-tasks tasks)))

   "await_any"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "await_any expects exactly 1 argument"
                       {:function "await_any" :expected 1 :actual (count args)})))
     (let [tasks (first args)]
       (when-not (nex-array? tasks)
         (throw (ex-info "await_any requires an array of tasks"
                         {:function "await_any" :actual-type (runtime-type-name tasks)})))
       (doseq [task tasks]
         (when-not (= (:nex-builtin-type task) :Task)
           (throw (ex-info "await_any requires an array of tasks"
                           {:function "await_any" :actual-type (runtime-type-name task)}))))
       (await-any-task tasks)))

   "sleep"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "sleep expects exactly 1 argument"
                       {:function "sleep" :expected 1 :actual (count args)})))
     #?(:clj (do
               (Thread/sleep (long (first args)))
               nil)
        :cljs (js/Promise. (fn [resolve _reject]
                             (js/setTimeout #(resolve nil) (long (first args)))))))

   "hint_spin"
   (fn [_ctx & args]
     (when (not= (count args) 0)
       (throw (ex-info "hint_spin expects exactly 0 arguments"
                       {:function "hint_spin" :expected 0 :actual (count args)})))
     #?(:clj (Thread/onSpinWait)
        :cljs nil)
     nil)

   "random_real"
   (fn [_ctx & args]
     (when (not= (count args) 0)
       (throw (ex-info "random_real expects exactly 0 arguments"
                       {:function "random_real" :expected 0 :actual (count args)})))
     (rand))

   "http_get"
   (fn [_ctx & args]
     (when-not (or (= (count args) 1) (= (count args) 2))
       (throw (ex-info "http_get expects 1 or 2 arguments"
                       {:function "http_get" :expected "1 or 2" :actual (count args)})))
     (let [[url timeout-ms] args]
       #?(:clj (java-http-request "GET" (str url) nil timeout-ms)
          :cljs (throw (ex-info "http_get is not supported in the ClojureScript interpreter"
                                {:function "http_get"})))))

   "http_post"
   (fn [_ctx & args]
     (when-not (or (= (count args) 2) (= (count args) 3))
       (throw (ex-info "http_post expects 2 or 3 arguments"
                       {:function "http_post" :expected "2 or 3" :actual (count args)})))
     (let [[url body timeout-ms] args]
       #?(:clj (java-http-request "POST" (str url) (str body) timeout-ms)
          :cljs (throw (ex-info "http_post is not supported in the ClojureScript interpreter"
                                {:function "http_post"})))))

   "json_parse"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "json_parse expects exactly 1 argument"
                       {:function "json_parse" :expected 1 :actual (count args)})))
     #?(:clj (json-types/nex-json-parse (first args))
        :cljs (throw (ex-info "json_parse is not supported in the ClojureScript interpreter"
                              {:function "json_parse"}))))

   "json_stringify"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "json_stringify expects exactly 1 argument"
                       {:function "json_stringify" :expected 1 :actual (count args)})))
     #?(:clj (json-types/nex-json-stringify (first args))
        :cljs (throw (ex-info "json_stringify is not supported in the ClojureScript interpreter"
                              {:function "json_stringify"}))))

   "regex_validate"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "regex_validate expects exactly 2 arguments" {:function "regex_validate"})))
     #?(:clj (regex-types/regex-validate (first args) (second args))
        :cljs (throw (ex-info "regex_validate is not supported in the ClojureScript interpreter"
                              {:function "regex_validate"}))))

   "regex_matches"
   (fn [_ctx & args]
     (when (not= (count args) 3)
       (throw (ex-info "regex_matches expects exactly 3 arguments" {:function "regex_matches"})))
     #?(:clj (apply regex-types/regex-matches? args)
        :cljs (throw (ex-info "regex_matches is not supported in the ClojureScript interpreter"
                              {:function "regex_matches"}))))

   "regex_find"
   (fn [_ctx & args]
     (when (not= (count args) 3)
       (throw (ex-info "regex_find expects exactly 3 arguments" {:function "regex_find"})))
     #?(:clj (apply regex-types/regex-find args)
        :cljs (throw (ex-info "regex_find is not supported in the ClojureScript interpreter"
                              {:function "regex_find"}))))

   "regex_find_all"
   (fn [_ctx & args]
     (when (not= (count args) 3)
       (throw (ex-info "regex_find_all expects exactly 3 arguments" {:function "regex_find_all"})))
     #?(:clj (apply regex-types/regex-find-all args)
        :cljs (throw (ex-info "regex_find_all is not supported in the ClojureScript interpreter"
                              {:function "regex_find_all"}))))

   "regex_replace"
   (fn [_ctx & args]
     (when (not= (count args) 4)
       (throw (ex-info "regex_replace expects exactly 4 arguments" {:function "regex_replace"})))
     #?(:clj (apply regex-types/regex-replace args)
        :cljs (throw (ex-info "regex_replace is not supported in the ClojureScript interpreter"
                              {:function "regex_replace"}))))

   "regex_split"
   (fn [_ctx & args]
     (when (not= (count args) 3)
       (throw (ex-info "regex_split expects exactly 3 arguments" {:function "regex_split"})))
     #?(:clj (apply regex-types/regex-split args)
        :cljs (throw (ex-info "regex_split is not supported in the ClojureScript interpreter"
                              {:function "regex_split"}))))

   "datetime_now"
   (fn [_ctx & args]
     (when (not= (count args) 0)
       (throw (ex-info "datetime_now expects exactly 0 arguments" {:function "datetime_now"})))
     #?(:clj (dt/datetime-now)
        :cljs (throw (ex-info "datetime_now is not supported in the ClojureScript interpreter"
                              {:function "datetime_now"}))))

   "datetime_from_epoch_millis"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_from_epoch_millis expects exactly 1 argument" {:function "datetime_from_epoch_millis"})))
     #?(:clj (dt/datetime-from-epoch-millis (first args))
        :cljs (throw (ex-info "datetime_from_epoch_millis is not supported in the ClojureScript interpreter"
                              {:function "datetime_from_epoch_millis"}))))

   "datetime_parse_iso"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_parse_iso expects exactly 1 argument" {:function "datetime_parse_iso"})))
     #?(:clj (dt/datetime-parse-iso (first args))
        :cljs (throw (ex-info "datetime_parse_iso is not supported in the ClojureScript interpreter"
                              {:function "datetime_parse_iso"}))))

   "datetime_make"
   (fn [_ctx & args]
     (when (not= (count args) 6)
       (throw (ex-info "datetime_make expects exactly 6 arguments" {:function "datetime_make"})))
     #?(:clj (apply dt/datetime-make args)
        :cljs (throw (ex-info "datetime_make is not supported in the ClojureScript interpreter"
                              {:function "datetime_make"}))))

   "datetime_year"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_year expects exactly 1 argument" {:function "datetime_year"})))
     #?(:clj (dt/datetime-year (first args))
        :cljs (throw (ex-info "datetime_year is not supported in the ClojureScript interpreter"
                              {:function "datetime_year"}))))

   "datetime_month"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_month expects exactly 1 argument" {:function "datetime_month"})))
     #?(:clj (dt/datetime-month (first args))
        :cljs (throw (ex-info "datetime_month is not supported in the ClojureScript interpreter"
                              {:function "datetime_month"}))))

   "datetime_day"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_day expects exactly 1 argument" {:function "datetime_day"})))
     #?(:clj (dt/datetime-day (first args))
        :cljs (throw (ex-info "datetime_day is not supported in the ClojureScript interpreter"
                              {:function "datetime_day"}))))

   "datetime_weekday"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_weekday expects exactly 1 argument" {:function "datetime_weekday"})))
     #?(:clj (dt/datetime-weekday (first args))
        :cljs (throw (ex-info "datetime_weekday is not supported in the ClojureScript interpreter"
                              {:function "datetime_weekday"}))))

   "datetime_day_of_year"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_day_of_year expects exactly 1 argument" {:function "datetime_day_of_year"})))
     #?(:clj (dt/datetime-day-of-year (first args))
        :cljs (throw (ex-info "datetime_day_of_year is not supported in the ClojureScript interpreter"
                              {:function "datetime_day_of_year"}))))

   "datetime_hour"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_hour expects exactly 1 argument" {:function "datetime_hour"})))
     #?(:clj (dt/datetime-hour (first args))
        :cljs (throw (ex-info "datetime_hour is not supported in the ClojureScript interpreter"
                              {:function "datetime_hour"}))))

   "datetime_minute"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_minute expects exactly 1 argument" {:function "datetime_minute"})))
     #?(:clj (dt/datetime-minute (first args))
        :cljs (throw (ex-info "datetime_minute is not supported in the ClojureScript interpreter"
                              {:function "datetime_minute"}))))

   "datetime_second"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_second expects exactly 1 argument" {:function "datetime_second"})))
     #?(:clj (dt/datetime-second (first args))
        :cljs (throw (ex-info "datetime_second is not supported in the ClojureScript interpreter"
                              {:function "datetime_second"}))))

   "datetime_epoch_millis"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_epoch_millis expects exactly 1 argument" {:function "datetime_epoch_millis"})))
     #?(:clj (dt/datetime-epoch-millis (first args))
        :cljs (throw (ex-info "datetime_epoch_millis is not supported in the ClojureScript interpreter"
                              {:function "datetime_epoch_millis"}))))

   "datetime_add_millis"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "datetime_add_millis expects exactly 2 arguments" {:function "datetime_add_millis"})))
     #?(:clj (apply dt/datetime-add-millis args)
        :cljs (throw (ex-info "datetime_add_millis is not supported in the ClojureScript interpreter"
                              {:function "datetime_add_millis"}))))

   "datetime_diff_millis"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "datetime_diff_millis expects exactly 2 arguments" {:function "datetime_diff_millis"})))
     #?(:clj (apply dt/datetime-diff-millis args)
        :cljs (throw (ex-info "datetime_diff_millis is not supported in the ClojureScript interpreter"
                              {:function "datetime_diff_millis"}))))

   "datetime_truncate_to_day"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_truncate_to_day expects exactly 1 argument" {:function "datetime_truncate_to_day"})))
     #?(:clj (dt/datetime-truncate-to-day (first args))
        :cljs (throw (ex-info "datetime_truncate_to_day is not supported in the ClojureScript interpreter"
                              {:function "datetime_truncate_to_day"}))))

   "datetime_truncate_to_hour"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_truncate_to_hour expects exactly 1 argument" {:function "datetime_truncate_to_hour"})))
     #?(:clj (dt/datetime-truncate-to-hour (first args))
        :cljs (throw (ex-info "datetime_truncate_to_hour is not supported in the ClojureScript interpreter"
                              {:function "datetime_truncate_to_hour"}))))

   "datetime_format_iso"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_format_iso expects exactly 1 argument" {:function "datetime_format_iso"})))
     #?(:clj (dt/datetime-format-iso (first args))
        :cljs (throw (ex-info "datetime_format_iso is not supported in the ClojureScript interpreter"
                              {:function "datetime_format_iso"}))))

   "path_exists"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_exists expects exactly 1 argument" {:function "path_exists"})))
     (rt/path-exists? (str (first args))))

   "path_is_file"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_is_file expects exactly 1 argument" {:function "path_is_file"})))
     (rt/path-is-file? (str (first args))))

   "path_is_directory"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_is_directory expects exactly 1 argument" {:function "path_is_directory"})))
     (rt/path-is-directory? (str (first args))))

   "path_name"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_name expects exactly 1 argument" {:function "path_name"})))
     (rt/path-name (str (first args))))

   "path_extension"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_extension expects exactly 1 argument" {:function "path_extension"})))
     (rt/path-extension (str (first args))))

   "path_name_without_extension"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_name_without_extension expects exactly 1 argument" {:function "path_name_without_extension"})))
     (rt/path-name-without-extension (str (first args))))

   "path_absolute"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_absolute expects exactly 1 argument" {:function "path_absolute"})))
     (str (rt/path-absolute (str (first args)))))

   "path_normalize"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_normalize expects exactly 1 argument" {:function "path_normalize"})))
     (str (rt/path-normalize (str (first args)))))

   "path_size"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_size expects exactly 1 argument" {:function "path_size"})))
     (rt/path-size (str (first args))))

   "path_modified_time"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_modified_time expects exactly 1 argument" {:function "path_modified_time"})))
     (rt/path-modified-time (str (first args))))

   "path_parent"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_parent expects exactly 1 argument" {:function "path_parent"})))
     (rt/path-parent (str (first args))))

   "path_child"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "path_child expects exactly 2 arguments" {:function "path_child"})))
     (rt/path-child (str (first args)) (str (second args))))

   "path_create_file"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_create_file expects exactly 1 argument" {:function "path_create_file"})))
     (rt/path-create-file (str (first args))))

   "path_create_directory"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_create_directory expects exactly 1 argument" {:function "path_create_directory"})))
     (rt/path-create-directory (str (first args))))

   "path_create_directories"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_create_directories expects exactly 1 argument" {:function "path_create_directories"})))
     (rt/path-create-directories (str (first args))))

   "path_delete"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_delete expects exactly 1 argument" {:function "path_delete"})))
     (rt/path-delete (str (first args))))

   "path_delete_tree"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_delete_tree expects exactly 1 argument" {:function "path_delete_tree"})))
     (rt/path-delete-tree (str (first args))))

   "path_copy"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "path_copy expects exactly 2 arguments" {:function "path_copy"})))
     (rt/path-copy (str (first args)) (str (second args))))

   "path_move"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "path_move expects exactly 2 arguments" {:function "path_move"})))
     (rt/path-move (str (first args)) (str (second args))))

   "path_read_text"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_read_text expects exactly 1 argument" {:function "path_read_text"})))
     (rt/path-read-text (str (first args))))

   "path_write_text"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "path_write_text expects exactly 2 arguments" {:function "path_write_text"})))
     (rt/path-write-text (str (first args)) (str (second args))))

   "path_append_text"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "path_append_text expects exactly 2 arguments" {:function "path_append_text"})))
     (rt/path-append-text (str (first args)) (str (second args))))

   "path_list"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_list expects exactly 1 argument" {:function "path_list"})))
     (rt/path-list (str (first args))))

   "text_file_open_read"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "text_file_open_read expects exactly 1 argument" {:function "text_file_open_read"})))
     (rt/text-file-open-read (str (first args))))

   "text_file_open_write"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "text_file_open_write expects exactly 1 argument" {:function "text_file_open_write"})))
     (rt/text-file-open-write (str (first args))))

   "text_file_open_append"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "text_file_open_append expects exactly 1 argument" {:function "text_file_open_append"})))
     (rt/text-file-open-append (str (first args))))

   "text_file_read_line"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "text_file_read_line expects exactly 1 argument" {:function "text_file_read_line"})))
     (rt/text-file-read-line (first args)))

   "text_file_write"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "text_file_write expects exactly 2 arguments" {:function "text_file_write"})))
     (rt/text-file-write (first args) (str (second args))))

   "text_file_close"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "text_file_close expects exactly 1 argument" {:function "text_file_close"})))
     (rt/text-file-close (first args)))

   "binary_file_open_read"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "binary_file_open_read expects exactly 1 argument" {:function "binary_file_open_read"})))
     (rt/binary-file-open-read (str (first args))))

   "binary_file_open_write"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "binary_file_open_write expects exactly 1 argument" {:function "binary_file_open_write"})))
     (rt/binary-file-open-write (str (first args))))

   "binary_file_open_append"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "binary_file_open_append expects exactly 1 argument" {:function "binary_file_open_append"})))
     (rt/binary-file-open-append (str (first args))))

   "binary_file_read_all"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "binary_file_read_all expects exactly 1 argument" {:function "binary_file_read_all"})))
     (rt/binary-file-read-all (first args)))

   "binary_file_read"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "binary_file_read expects exactly 2 arguments" {:function "binary_file_read"})))
     (rt/binary-file-read (first args) (second args)))

   "binary_file_write"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "binary_file_write expects exactly 2 arguments" {:function "binary_file_write"})))
     (rt/binary-file-write (first args) (second args)))

   "binary_file_position"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "binary_file_position expects exactly 1 argument" {:function "binary_file_position"})))
     (rt/binary-file-position (first args)))

   "binary_file_seek"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "binary_file_seek expects exactly 2 arguments" {:function "binary_file_seek"})))
     (rt/binary-file-seek (first args) (second args)))

   "binary_file_close"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "binary_file_close expects exactly 1 argument" {:function "binary_file_close"})))
     (rt/binary-file-close (first args)))

   "http_server_create"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "http_server_create expects exactly 1 argument"
                       {:function "http_server_create" :expected 1 :actual (count args)})))
     #?(:clj (make-http-server-handle (int (first args)))
        :cljs (throw (ex-info "http_server_create is not supported in the ClojureScript interpreter"
                              {:function "http_server_create"}))))

   "http_server_get"
   (fn [_ctx & args]
     (when (not= (count args) 3)
       (throw (ex-info "http_server_get expects exactly 3 arguments"
                       {:function "http_server_get" :expected 3 :actual (count args)})))
     (let [[handle path handler] args]
       #?(:clj (do
                 (swap! (get-in handle [:routes "GET"]) conj {:path-pattern (str path)
                                                              :handler handler})
                 nil)
          :cljs (throw (ex-info "http_server_get is not supported in the ClojureScript interpreter"
                                {:function "http_server_get"})))))

   "http_server_post"
   (fn [_ctx & args]
     (when (not= (count args) 3)
       (throw (ex-info "http_server_post expects exactly 3 arguments"
                       {:function "http_server_post" :expected 3 :actual (count args)})))
     (let [[handle path handler] args]
       #?(:clj (do
                 (swap! (get-in handle [:routes "POST"]) conj {:path-pattern (str path)
                                                               :handler handler})
                 nil)
          :cljs (throw (ex-info "http_server_post is not supported in the ClojureScript interpreter"
                                {:function "http_server_post"})))))

   "http_server_put"
   (fn [_ctx & args]
     (when (not= (count args) 3)
       (throw (ex-info "http_server_put expects exactly 3 arguments"
                       {:function "http_server_put" :expected 3 :actual (count args)})))
     (let [[handle path handler] args]
       #?(:clj (do
                 (swap! (get-in handle [:routes "PUT"]) conj {:path-pattern (str path)
                                                              :handler handler})
                 nil)
          :cljs (throw (ex-info "http_server_put is not supported in the ClojureScript interpreter"
                                {:function "http_server_put"})))))

   "http_server_delete"
   (fn [_ctx & args]
     (when (not= (count args) 3)
       (throw (ex-info "http_server_delete expects exactly 3 arguments"
                       {:function "http_server_delete" :expected 3 :actual (count args)})))
     (let [[handle path handler] args]
       #?(:clj (do
                 (swap! (get-in handle [:routes "DELETE"]) conj {:path-pattern (str path)
                                                                 :handler handler})
                 nil)
          :cljs (throw (ex-info "http_server_delete is not supported in the ClojureScript interpreter"
                                {:function "http_server_delete"})))))

   "http_server_start"
   (fn [ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "http_server_start expects exactly 1 argument"
                       {:function "http_server_start" :expected 1 :actual (count args)})))
     #?(:clj (start-http-server! ctx (first args))
        :cljs (throw (ex-info "http_server_start is not supported in the ClojureScript interpreter"
                              {:function "http_server_start"}))))

   "http_server_stop"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "http_server_stop expects exactly 1 argument"
                       {:function "http_server_stop" :expected 1 :actual (count args)})))
     #?(:clj (let [handle (first args)
                   server @(:server handle)]
               (when server
                 (.stop ^com.sun.net.httpserver.HttpServer server 0)
                 (reset! (:server handle) nil))
               nil)
        :cljs (throw (ex-info "http_server_stop is not supported in the ClojureScript interpreter"
                              {:function "http_server_stop"}))))

   "http_server_is_running"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "http_server_is_running expects exactly 1 argument"
                       {:function "http_server_is_running" :expected 1 :actual (count args)})))
     #?(:clj (some? @(:server (first args)))
        :cljs (throw (ex-info "http_server_is_running is not supported in the ClojureScript interpreter"
                              {:function "http_server_is_running"}))))

   })

;;
;; Operator Implementations
;;

(defn- nex-ordering-compare [x y]
  (cond
    (= x y) 0
    :else
    (try
      (let [c (compare x y)]
        (cond
          (neg? c) -1
          (pos? c) 1
          :else 0))
      (catch #?(:clj Exception :cljs :default) _
        (let [sx (str x)
              sy (str y)]
          (cond
            (= sx sy) 0
            (neg? (compare sx sy)) -1
            :else 1))))))

(defn- scalar-identity-value?
  [v]
  (or (nil? v)
      (string? v)
      (number? v)
      (boolean? v)
      (char? v)))

(defn- nex-identity-equals?
  [a b]
  (cond
    (and (scalar-identity-value? a)
         (scalar-identity-value? b))
    (= a b)

    :else
    (identical? a b)))

(defn apply-binary-op
  "Apply a binary operator to two values."
  [op left right]
  (case op
    "+" (if (and (not (string? left)) (not (string? right)))
          (+ left right)
          (throw (ex-info "String concatenation requires evaluation context"
                          {:operator op :left left :right right})))
    "-" (- left right)
    "*" (* left right)
    "/" (if (zero? right)
          (throw (ex-info "Division by zero" {:left left :right right}))
          (if (and (integer? left) (integer? right))
            #?(:clj (quot left right)
               :cljs (js/Math.trunc (/ left right)))
            (/ left right)))
    "^" (if (and (integer? left) (integer? right))
          (nex-int-pow left right)
          (Math/pow left right))
    "%" (if (zero? right)
          (throw (ex-info "Division by zero" {:left left :right right}))
          (mod left right))
    "=" (= left right)
    "/=" (not= left right)
    "==" (nex-identity-equals? left right)
    "!=" (not (nex-identity-equals? left right))
    "<" (neg? (nex-ordering-compare left right))
    "<=" (not (pos? (nex-ordering-compare left right)))
    ">" (pos? (nex-ordering-compare left right))
    ">=" (not (neg? (nex-ordering-compare left right)))
    "and" (and left right)
    "or" (or left right)
    (throw (ex-info (str "Unknown binary operator: " op)
                    {:operator op}))))

(defn- concat-string-value
  "Convert a runtime value to the string form used by String concatenation.
   If a Nex object implements to_string, invoke it; otherwise use the built-in
   Any/to_string formatting path."
  [ctx value]
  (cond
    (string? value) value

    (nex-object? value)
    (let [class-def (lookup-class ctx (:class-name value))
          method-lookup (lookup-method-with-inheritance ctx class-def "to_string" 0)]
      (if method-lookup
        (let [result (eval-node ctx {:type :call
                                     :target {:type :literal :value value}
                                     :method "to_string"
                                     :args []})]
          (if (string? result)
            result
            (nex-format-value result)))
        (call-builtin-method nil nil value "to_string" [])))

    :else
    (call-builtin-method nil nil value "to_string" [])))

(defn- concat-string-value-async
  "Async variant of concat-string-value for the browser interpreter."
  [ctx value]
  #?(:clj
     (concat-string-value ctx value)
     :cljs
     (cond
       (string? value) (js/Promise.resolve value)

       (nex-object? value)
       (let [class-def (lookup-class ctx (:class-name value))
             method-lookup (lookup-method-with-inheritance ctx class-def "to_string" 0)]
         (if method-lookup
           (.then (->promise (eval-node-async ctx {:type :call
                                                   :target {:type :literal :value value}
                                                   :method "to_string"
                                                   :args []}))
                  (fn [result]
                    (if (string? result)
                      result
                      (nex-format-value result))))
           (js/Promise.resolve (call-builtin-method nil nil value "to_string" []))))

       :else
       (js/Promise.resolve (call-builtin-method nil nil value "to_string" [])))))

(defn- print-output-value
  "Convert a value for built-in print/println output.
   Preserve existing formatting for non-objects, but respect user-defined
   to_string implementations on Nex objects."
  [ctx value]
  (if (nex-object? value)
    (concat-string-value ctx value)
    (nex-format-value value)))

(defn apply-unary-op
  "Apply a unary operator to a value."
  [op value]
  (case op
    "-" (- value)
    "not" (not value)
    (throw (ex-info (str "Unknown unary operator: " op)
                    {:operator op}))))

(defn get-default-field-value
  "Get default value for a field type"
  [field-type]
  (cond
    ;; Handle parameterized types
    (map? field-type)
    (case (:base-type field-type)
      "Array" (nex-array)
      "Map" (nex-map)
      "Set" (nex-set)
      "Min_Heap" (make-min-heap nil)
      nil)

    ;; Handle simple types
    (string? field-type)
    (case field-type
      "Integer" 0
      "Integer64" 0
      "Real" 0.0
      "Decimal" 0.0
      "Char" \0
      "Boolean" false
      "String" ""
      "Min_Heap" (make-min-heap nil)
      "Console" {:nex-builtin-type :Console}
      "Process" {:nex-builtin-type :Process}
      "Task" nil
      "Channel" nil
      nil)

    :else nil))

;;
;; Built-in Type Methods
;;

(def builtin-type-methods
  "Methods available on built-in types"
  (letfn [(nex-compare [x y]
            (nex-ordering-compare x y))]
    {:Any
   {"to_string"   (fn [v & _] (nex-format-value v))
    "equals"      (fn [v other & _]
                    #?(:clj (identical? v other)
                       :cljs (js/Object.is v other)))
    "clone"       (fn [v & _] (nex-clone-value v))}

   :String
   {"length"      (fn [s & _] (count s))
    "index_of"    (fn [s ch & _]
                    (let [idx (str/index-of s (str ch))]
                      (if idx idx -1)))
    "substring"   (fn [s start end & _] (subs s start end))
    "to_upper"    (fn [s & _] (str/upper-case s))
    "to_lower"    (fn [s & _] (str/lower-case s))
    "to_integer"  (fn [s & _] (nex-parse-integer s))
    "to_integer64" (fn [s & _] (nex-parse-integer64-string s))
    "to_real"     (fn [s & _] #?(:clj (Double/parseDouble (str/trim s))
                                 :cljs (js/parseFloat (str/trim s))))
    "to_decimal"  (fn [s & _] #?(:clj (bigdec (str/trim s))
                                 :cljs (js/parseFloat (str/trim s))))
    "contains"    (fn [s substr & _] (str/includes? s substr))
    "starts_with" (fn [s prefix & _] (str/starts-with? s prefix))
    "ends_with"   (fn [s suffix & _] (str/ends-with? s suffix))
    "trim"        (fn [s & _] (str/trim s))
    "replace"     (fn [s old new & _] (str/replace s old new))
    "char_at"     (fn [s idx & _] (get s idx))
    "chars"       (fn [s & _]
                    (nex-array-from
                     (mapv #(get s %) (range (count s)))))
    "to_bytes"    (fn [s & _]
                    #?(:clj (nex-array-from
                             (mapv #(bit-and (int %) 0xFF)
                                   (.getBytes ^String s StandardCharsets/UTF_8)))
                       :cljs (nex-array-from
                              (mapv identity
                                    (js->clj (.encode (js/TextEncoder.) s))))))
    "split"       (fn [s delim & _] (vec (str/split s (re-pattern delim))))
    ;; String operator methods
    "plus"        (fn [s other & [ctx]]
                    (str s (if ctx
                             (concat-string-value ctx other)
                             (nex-format-value other))))
    "equals"      (fn [s other & _] (= s other))
    "not_equals"  (fn [s other & _] (not= s other))
    "less_than"   (fn [s other & _] (neg? (compare s other)))
    "less_than_or_equal" (fn [s other & _] (<= (compare s other) 0))
    "greater_than" (fn [s other & _] (pos? (compare s other)))
    "greater_than_or_equal" (fn [s other & _] (>= (compare s other) 0))
    "compare"     (fn [s other & _] (nex-compare s other))
    "hash"        (fn [s & _] (hash s))
    "cursor"      (fn [s & _]
                    {:nex-builtin-type :StringCursor
                     :source s
                     :index (atom 0)})}

   :Integer
   {"to_string"         (fn [n & _] (str n))
    "abs"               (fn [n & _] (nex-abs n))
    "min"               (fn [n other & _] (min n other))
    "max"               (fn [n other & _] (max n other))
    "pick"              (fn [n & _] (rand-int n))
    "bitwise_left_shift" (fn [n shift & _] (nex-bitwise-left-shift n shift))
    "bitwise_right_shift" (fn [n shift & _] (nex-bitwise-right-shift n shift))
    "bitwise_logical_right_shift" (fn [n shift & _] (nex-bitwise-logical-right-shift n shift))
    "bitwise_rotate_left" (fn [n shift & _] (nex-bitwise-rotate-left n shift))
    "bitwise_rotate_right" (fn [n shift & _] (nex-bitwise-rotate-right n shift))
    "bitwise_is_set"    (fn [n idx & _] (nex-bitwise-is-set n idx))
    "bitwise_set"       (fn [n idx & _] (nex-bitwise-set n idx))
    "bitwise_unset"     (fn [n idx & _] (nex-bitwise-unset n idx))
    "bitwise_and"       (fn [n other & _] (nex-bitwise-and n other))
    "bitwise_or"        (fn [n other & _] (nex-bitwise-or n other))
    "bitwise_xor"       (fn [n other & _] (nex-bitwise-xor n other))
    "bitwise_not"       (fn [n & _] (nex-bitwise-not n))
    ;; Arithmetic operator methods
    "plus"              (fn [n other & _] (+ n other))
    "minus"             (fn [n other & _] (- n other))
    "times"             (fn [n other & _] (* n other))
    "divided_by"        (fn [n other & _] (/ n other))
    ;; Comparison operator methods
    "equals"            (fn [n other & _] (= n other))
    "not_equals"        (fn [n other & _] (not= n other))
    "less_than"         (fn [n other & _] (< n other))
    "less_than_or_equal" (fn [n other & _] (<= n other))
    "greater_than"      (fn [n other & _] (> n other))
    "greater_than_or_equal" (fn [n other & _] (>= n other))
    "compare"           (fn [n other & _] (nex-compare n other))
    "hash"              (fn [n & _] (hash n))}

   :Integer64
   {"to_string"         (fn [n & _] (str n))
    "abs"               (fn [n & _] (nex-abs n))
    "min"               (fn [n other & _] (min n other))
    "max"               (fn [n other & _] (max n other))
    ;; Arithmetic operator methods
    "plus"              (fn [n other & _] (+ n other))
    "minus"             (fn [n other & _] (- n other))
    "times"             (fn [n other & _] (* n other))
    "divided_by"        (fn [n other & _] (/ n other))
    ;; Comparison operator methods
    "equals"            (fn [n other & _] (= n other))
    "not_equals"        (fn [n other & _] (not= n other))
    "less_than"         (fn [n other & _] (< n other))
    "less_than_or_equal" (fn [n other & _] (<= n other))
    "greater_than"      (fn [n other & _] (> n other))
    "greater_than_or_equal" (fn [n other & _] (>= n other))
    "compare"           (fn [n other & _] (nex-compare n other))
    "hash"              (fn [n & _] (hash n))}

   :Real
   {"to_string"         (fn [n & _] (str n))
    "abs"               (fn [n & _] (nex-abs n))
    "min"               (fn [n other & _] (min n other))
    "max"               (fn [n other & _] (max n other))
    "round"             (fn [n & _] (nex-round n))
    ;; Arithmetic operator methods
    "plus"              (fn [n other & _] (+ n other))
    "minus"             (fn [n other & _] (- n other))
    "times"             (fn [n other & _] (* n other))
    "divided_by"        (fn [n other & _] (/ n other))
    ;; Comparison operator methods
    "equals"            (fn [n other & _] (= n other))
    "not_equals"        (fn [n other & _] (not= n other))
    "less_than"         (fn [n other & _] (< n other))
    "less_than_or_equal" (fn [n other & _] (<= n other))
    "greater_than"      (fn [n other & _] (> n other))
    "greater_than_or_equal" (fn [n other & _] (>= n other))
    "compare"           (fn [n other & _] (nex-compare n other))
    "hash"              (fn [n & _] (hash n))}

   :Decimal
   {"to_string"         (fn [n & _] (str n))
    "abs"               (fn [n & _] (nex-abs n))
    "min"               (fn [n other & _] (min n other))
    "max"               (fn [n other & _] (max n other))
    "round"             (fn [n & _] (nex-round n))
    ;; Arithmetic operator methods
    "plus"              (fn [n other & _] (+ n other))
    "minus"             (fn [n other & _] (- n other))
    "times"             (fn [n other & _] (* n other))
    "divided_by"        (fn [n other & _] (/ n other))
    ;; Comparison operator methods
    "equals"            (fn [n other & _] (= n other))
    "not_equals"        (fn [n other & _] (not= n other))
    "less_than"         (fn [n other & _] (< n other))
    "less_than_or_equal" (fn [n other & _] (<= n other))
    "greater_than"      (fn [n other & _] (> n other))
    "greater_than_or_equal" (fn [n other & _] (>= n other))
    "compare"           (fn [n other & _] (nex-compare n other))
    "hash"              (fn [n & _] (hash n))}

   :Char
   {"to_string"   (fn [c & _] (str c))
    "to_upper"    (fn [c & _] (str/upper-case (str c)))
    "to_lower"    (fn [c & _] (str/lower-case (str c)))
    "compare"     (fn [c other & _] (nex-compare c other))
    "hash"        (fn [c & _] (hash c))}

   :Boolean
   {"to_string"   (fn [b & _] (str b))
    ;; Boolean operator methods
    "and"         (fn [b other & _] (and b other))
    "or"          (fn [b other & _] (or b other))
    "not"         (fn [b & _] (not b))
    "equals"      (fn [b other & _] (= b other))
    "not_equals"  (fn [b other & _] (not= b other))
    "compare"     (fn [b other & _] (nex-compare b other))
    "hash"        (fn [b & _] (hash b))}

   :Array
   {"get"         (fn [arr index & _] (nex-array-get arr index))
    "add"         (fn [arr value & _] (nex-array-add arr value))
    "add_at"      (fn [arr index value & _] (nex-array-add-at arr index value))
    "put"         (fn [arr index value & _] (nex-array-set arr index value))
    "length"      (fn [arr & _] (nex-array-size arr))
    "is_empty"    (fn [arr & _] (nex-array-empty? arr))
    "contains"    (fn [arr elem & _] (nex-array-contains-value? arr elem))
    "index_of"    (fn [arr elem & _]
                    (let [idx (nex-array-index-of-value arr elem)]
                      (if (>= idx 0) idx -1)))
    "remove"      (fn [arr idx & _] (nex-array-remove arr idx))
    "reverse"     (fn [arr & _] (nex-array-reverse arr))
    "sort"        (fn [arr & args]
                    (let [ctx (last args)
                          method-args (butlast args)]
                      (case (count method-args)
                        0 (nex-array-sort-with-ctx ctx arr)
                        1 (nex-array-sort-with-ctx ctx arr (first method-args))
                        (throw (ex-info "Method sort expects 0 or 1 arguments"
                                        {:target arr :method "sort" :actual (count method-args)})))))
    "slice"       (fn [arr start end & _] (nex-array-slice arr start end))
    "to_string"   (fn [arr & _] (nex-array-str arr))
    "equals"      (fn [arr other & _] (nex-deep-equals? arr other))
    "clone"       (fn [arr & _] (nex-clone-value arr))
    "cursor"      (fn [arr & _]
                    {:nex-builtin-type :ArrayCursor
                     :source arr
                     :index (atom 0)})}

   :Map
   {"get"         (fn [m key & _]
                    (let [v (nex-map-get m key)]
                      (if (nil? v)
                        (report-contract-violation Precondition "key_must_exist" "has_key")
                        v)))
    "try_get"      (fn [m key default & _]
                    (let [v (nex-map-get m key)]
                      (if (nil? v)
                        default
                        v)))
    "put"          (fn [m key val & _] (nex-map-put m key val))
    "size"         (fn [m & _] (nex-map-size m))
    "is_empty"     (fn [m & _] (nex-map-empty? m))
    "contains_key" (fn [m key & _] (nex-map-contains-key-value? m key))
    "keys"         (fn [m & _] (nex-map-keys m))
    "values"       (fn [m & _] (nex-map-values m))
    "remove"       (fn [m key & _] (nex-map-remove m key))
    "to_string"    (fn [m & _] (nex-map-str m))
    "equals"       (fn [m other & _] (nex-deep-equals? m other))
    "clone"        (fn [m & _] (nex-clone-value m))
    "cursor"       (fn [m & _]
                     {:nex-builtin-type :MapCursor
                     :source m
                     :keys (atom (nex-map-keys m))
                     :index (atom 0)})}

   :Set
   {"contains"             (fn [s value & _] (nex-set-contains-value? s value))
    "union"                (fn [s other & _] (nex-set-union s other))
    "difference"           (fn [s other & _] (nex-set-difference s other))
    "intersection"         (fn [s other & _] (nex-set-intersection s other))
    "symmetric_difference" (fn [s other & _] (nex-set-symmetric-difference s other))
    "size"                 (fn [s & _] (nex-set-size s))
    "is_empty"             (fn [s & _] (nex-set-empty? s))
    "to_string"            (fn [s & _] (nex-set-str s))
    "equals"               (fn [s other & _] (nex-deep-equals? s other))
    "clone"                (fn [s & _] (nex-clone-value s))
    "cursor"               (fn [s & _]
                             {:nex-builtin-type :SetCursor
                              :source s
                              :values (atom #?(:clj (vec s) :cljs (vec (es6-iterator-seq (.values s)))))
                              :index (atom 0)})}

   :Min_Heap
   {"insert"          (fn [heap value & [ctx]] (heap-insert! ctx heap value))
    "extract_min"     (fn [heap & [ctx]]
                        (or (heap-extract-min! ctx heap)
                            (throw (ex-info "Min_Heap is empty" {:heap heap}))))
    "try_extract_min" (fn [heap & [ctx]] (heap-extract-min! ctx heap))
    "peek"            (fn [heap & _]
                        (or (heap-peek heap)
                            (throw (ex-info "Min_Heap is empty" {:heap heap}))))
    "try_peek"        (fn [heap & _] (heap-peek heap))
    "size"            (fn [heap & _] (count @(:data heap)))
    "is_empty"        (fn [heap & _] (empty? @(:data heap)))}

   :Atomic_Integer
   {"load"            (fn [atomic & _] #?(:clj (.get ^AtomicInteger (:state atomic))
                                          :cljs @(:state atomic)))
    "store"           (fn [atomic value & _]
                        #?(:clj (.set ^AtomicInteger (:state atomic) (int value))
                           :cljs (reset! (:state atomic) value))
                        nil)
    "compare_and_set" (fn [atomic expected update & _]
                        #?(:clj (.compareAndSet ^AtomicInteger (:state atomic) (int expected) (int update))
                           :cljs (if (= @(:state atomic) expected)
                                   (do (reset! (:state atomic) update) true)
                                   false)))
    "get_and_add"     (fn [atomic delta & _]
                        #?(:clj (.getAndAdd ^AtomicInteger (:state atomic) (int delta))
                           :cljs (let [current @(:state atomic)]
                                   (swap! (:state atomic) + delta)
                                   current)))
    "add_and_get"     (fn [atomic delta & _]
                        #?(:clj (.addAndGet ^AtomicInteger (:state atomic) (int delta))
                           :cljs (swap! (:state atomic) + delta)))
    "increment"       (fn [atomic & _]
                        #?(:clj (.incrementAndGet ^AtomicInteger (:state atomic))
                           :cljs (swap! (:state atomic) inc)))
    "decrement"       (fn [atomic & _]
                        #?(:clj (.decrementAndGet ^AtomicInteger (:state atomic))
                           :cljs (swap! (:state atomic) dec)))}

   :Atomic_Integer64
   {"load"            (fn [atomic & _] #?(:clj (.get ^AtomicLong (:state atomic))
                                          :cljs @(:state atomic)))
    "store"           (fn [atomic value & _]
                        #?(:clj (.set ^AtomicLong (:state atomic) (long value))
                           :cljs (reset! (:state atomic) value))
                        nil)
    "compare_and_set" (fn [atomic expected update & _]
                        #?(:clj (.compareAndSet ^AtomicLong (:state atomic) (long expected) (long update))
                           :cljs (if (= @(:state atomic) expected)
                                   (do (reset! (:state atomic) update) true)
                                   false)))
    "get_and_add"     (fn [atomic delta & _]
                        #?(:clj (.getAndAdd ^AtomicLong (:state atomic) (long delta))
                           :cljs (let [current @(:state atomic)]
                                   (swap! (:state atomic) + delta)
                                   current)))
    "add_and_get"     (fn [atomic delta & _]
                        #?(:clj (.addAndGet ^AtomicLong (:state atomic) (long delta))
                           :cljs (swap! (:state atomic) + delta)))
    "increment"       (fn [atomic & _]
                        #?(:clj (.incrementAndGet ^AtomicLong (:state atomic))
                           :cljs (swap! (:state atomic) inc)))
    "decrement"       (fn [atomic & _]
                        #?(:clj (.decrementAndGet ^AtomicLong (:state atomic))
                           :cljs (swap! (:state atomic) dec)))}

   :Atomic_Boolean
   {"load"            (fn [atomic & _] #?(:clj (.get ^AtomicBoolean (:state atomic))
                                          :cljs @(:state atomic)))
    "store"           (fn [atomic value & _]
                        #?(:clj (.set ^AtomicBoolean (:state atomic) (boolean value))
                           :cljs (reset! (:state atomic) value))
                        nil)
    "compare_and_set" (fn [atomic expected update & _]
                        #?(:clj (.compareAndSet ^AtomicBoolean (:state atomic)
                                                (boolean expected)
                                                (boolean update))
                           :cljs (if (= @(:state atomic) expected)
                                   (do (reset! (:state atomic) update) true)
                                   false)))}

   :Atomic_Reference
   {"load"            (fn [atomic & _] #?(:clj (.get ^AtomicReference (:state atomic))
                                          :cljs @(:state atomic)))
    "store"           (fn [atomic value & _]
                        #?(:clj (.set ^AtomicReference (:state atomic) value)
                           :cljs (reset! (:state atomic) value))
                        nil)
    "compare_and_set" (fn [atomic expected update & _]
                        (atomic-reference-cas! atomic expected update))}

   :Task
   {"await"    (fn [t & [timeout]]
                  (let [result (if (some? timeout)
                                 (task-await t timeout)
                                 (task-await t))]
                    (if (= result task-timeout-signal) nil result)))
    "cancel"   (fn [t & _] (task-cancel t))
    "is_done"  (fn [t & _] #?(:clj (.isDone ^CompletableFuture (:future t))
                              :cljs @(:done? t)))
    "is_cancelled" (fn [t & _] (task-cancelled? t))}

   :Channel
   {"send"      (fn [ch value & [timeout]]
                  (if (some? timeout)
                    (channel-send ch value timeout)
                    (channel-send ch value)))
    "try_send"  (fn [ch value & _] (channel-try-send ch value))
    "receive"   (fn [ch & [timeout]]
                  (if (some? timeout)
                    (channel-receive ch timeout)
                    (channel-receive ch)))
    "try_receive" (fn [ch & _] (channel-try-receive ch))
    "close"     (fn [ch & _] (channel-close ch))
    "is_closed" (fn [ch & _] (:closed? @(:state ch)))
    "capacity"  (fn [ch & _] (:capacity @(:state ch)))
    "size"      (fn [ch & _]
                  #?(:clj (count (:buffer @(:state ch)))
                     :cljs (count (:buffer @(:state ch)))))}

   :Console
   {"print"        (fn [_ msg & _] (nex-console-print (nex-display-value msg)) nil)
    "print_line"   (fn [_ msg & _] (nex-console-println (nex-display-value msg)) nil)
    "read_line"    (fn [_ & args] (when (seq args) (nex-console-print (str (first args)))) (nex-console-read-line))
    "error"        (fn [_ msg & _] (nex-console-error (nex-display-value msg)) nil)
    "new_line"     (fn [_ & _] (nex-console-newline) nil)
    "flush"        (fn [_ & _] (nex-console-flush) nil)
    "read_integer" (fn [_ & _] (nex-parse-integer (nex-console-read-line)))
    "read_real"    (fn [_ & _] (nex-parse-real (nex-console-read-line)))}

   :Process
   {"getenv"       (fn [_ name & _] (or (nex-process-getenv (str name)) ""))
    "setenv"       (fn [_ name value & _] (nex-process-setenv (str name) (str value)) nil)
    "command_line" (fn [_ & _] (nex-process-command-line))}

   :ArrayCursor
   {"start"   (fn [c & _] (reset! (:index c) 0) nil)
    "item"    (fn [c & _]
                (let [arr (:source c)
                      idx @(:index c)]
                  (if (< idx (nex-array-size arr))
                    (nex-array-get arr idx)
                    (throw (ex-info "Cursor is at end" {:index idx})))))
    "next"    (fn [c & _]
                (let [arr (:source c)
                      idx @(:index c)]
                  (when (< idx (nex-array-size arr))
                    (swap! (:index c) inc))
                  nil))
    "at_end"  (fn [c & _]
                (>= @(:index c) (nex-array-size (:source c))))}

   :StringCursor
   {"start"   (fn [c & _] (reset! (:index c) 0) nil)
    "item"    (fn [c & _]
                (let [s (:source c)
                      idx @(:index c)]
                  (if (< idx (count s))
                    (get s idx)
                    (throw (ex-info "Cursor is at end" {:index idx})))))
    "next"    (fn [c & _]
                (let [s (:source c)
                      idx @(:index c)]
                  (when (< idx (count s))
                    (swap! (:index c) inc))
                  nil))
    "at_end"  (fn [c & _]
                (>= @(:index c) (count (:source c))))}

   :MapCursor
   {"start"   (fn [c & _]
                (reset! (:keys c) (nex-map-keys (:source c)))
                (reset! (:index c) 0)
                nil)
    "item"    (fn [c & _]
                (let [ks @(:keys c)
                      idx @(:index c)]
                  (if (< idx (count ks))
                    (let [k (nth ks idx)
                          v (nex-map-get (:source c) k)]
                      (nex-array-from [k v]))
                    (throw (ex-info "Cursor is at end" {:index idx})))))
    "next"    (fn [c & _]
                (let [ks @(:keys c)
                      idx @(:index c)]
                  (when (< idx (count ks))
                    (swap! (:index c) inc))
                  nil))
    "at_end"  (fn [c & _]
                (>= @(:index c) (count @(:keys c))))}

      :SetCursor
   {"start"   (fn [c & _]
                (reset! (:values c) #?(:clj (vec (:source c))
                                       :cljs (vec (es6-iterator-seq (.values (:source c)))))
                )
                (reset! (:index c) 0)
                nil)
    "item"    (fn [c & _]
                (let [vals @(:values c)
                      idx @(:index c)]
                  (if (< idx (count vals))
                    (nth vals idx)
                    (throw (ex-info "Cursor is at end" {:index idx})))))
    "next"    (fn [c & _]
                (let [vals @(:values c)
                      idx @(:index c)]
                  (when (< idx (count vals))
                    (swap! (:index c) inc))
                  nil))
    "at_end"  (fn [c & _]
                (>= @(:index c) (count @(:values c))))}}))

(def get-type-name typeinfo/get-type-name)

(defn call-builtin-method
  "Call a built-in method on a primitive value"
  ([target value method-name args]
   (call-builtin-method nil target value method-name args))
  ([ctx target value method-name args]
   (if-let [method-fn
            (or (when-let [type-name (get-type-name value)]
                  (when-let [methods (get builtin-type-methods type-name)]
                    (get methods method-name)))
                (get-in builtin-type-methods [:Any method-name]))]
     (if (and ctx
              (let [type-name (get-type-name value)]
                (or (and (= method-name "sort")
                         (= type-name :Array))
                    (= type-name :Min_Heap))))
       (apply method-fn value (concat args [ctx]))
       (apply method-fn value args))
     (throw (ex-info (str "Method not found on type: " method-name)
                     {:target target :value value :method method-name})))))
;;
;; Node Evaluators
;;

(defmulti eval-node
  "Evaluate an AST node in the given context."
  (fn [ctx node]
    (cond
      (map? node) (:type node)
      :else :literal)))

#?(:clj
   (defn- intern-search-roots
     "Return directories to search for project-local interned classes.
      Prefer the currently loaded source file's directory when available, then
      fall back to the user's original working directory."
     [ctx]
     (let [source-dir (when-let [source (:debug-source ctx)]
                        (let [f (clojure.java.io/file source)]
                          (when (.isAbsolute f)
                            (.getParentFile f))))
           user-dir (when-let [udir (System/getProperty "nex.user.dir")]
                      (clojure.java.io/file udir))
           pwd (clojure.java.io/file ".")]
       (->> [source-dir user-dir pwd]
            (remove nil?)
            distinct)))

   :cljs
   (defn- intern-search-roots
     [ctx]
     (let [path-module (js/require "path")
           source-dir (when-let [source (:debug-source ctx)]
                        (when (.isAbsolute path-module source)
                          (.dirname path-module source)))
           user-dir (or (.-nex_user_dir js/process.env)
                        (.-NEX_USER_DIR js/process.env)
                        (.-PWD js/process.env))
           pwd (.resolve path-module ".")]
       (->> [source-dir user-dir pwd]
            (remove nil?)
            distinct))))

#?(:clj
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
           locations (vec (concat current-dir local-direct local-lib home-deps))
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
   :cljs
   (defn find-intern-file
     "Search for an intern file in the specified locations.
      Returns the absolute path if found, otherwise throws an exception."
     [ctx path class-name]
     (let [fs (js/require "fs")
           path-module (js/require "path")
           filenames (intern-filenames class-name)
           home (or (.-HOME js/process.env) (.-USERPROFILE js/process.env) ".")
           local-roots (intern-search-roots ctx)
           local-direct (mapcat (fn [root]
                                  (map #(.join path-module root %) filenames))
                                local-roots)
           local-lib (when (seq path)
                       (mapcat (fn [root]
                                 (concat
                                  (map #(.join path-module root "lib" path %) filenames)
                                  (map #(.join path-module root "lib" path "src" %) filenames)))
                               local-roots))
           home-deps (if (seq path)
                       (concat
                        (map #(str home "/.nex/deps/" path "/" %) filenames)
                        (map #(str home "/.nex/deps/" path "/src/" %) filenames))
                       (concat
                        (map #(str home "/.nex/deps/" %) filenames)
                        (map #(str home "/.nex/deps/src/" %) filenames)))
           locations (vec (concat local-direct local-lib home-deps))
           found (first (filter #(.existsSync fs %) locations))]
       (if found
         found
         (throw (ex-info (str "Cannot find intern file for "
                              (if (seq path)
                                (str path "/" class-name)
                                class-name))
                        {:path path
                         :class-name class-name
                         :searched-locations locations}))))))

#?(:clj
   (defn process-intern
     "Load and interpret an external file, then register the class with the given alias."
     [ctx {:keys [path class-name alias]}]
     (let [file-path (find-intern-file ctx path class-name)
           ;; Load and parse the external file
           file-content (slurp file-path)
           file-ast (parser/ast file-content)
           ;; Interpret the file to register its classes
           _ (eval-node ctx file-ast)
           ;; Look up the class that was registered
           registered-class (get @(:classes ctx) class-name)
           ;; Determine the name to use (alias or original)
           intern-name (or alias class-name)]
       (when-not registered-class
         (throw (ex-info (str "Class " class-name " not found in file " file-path)
                        {:file file-path :class-name class-name})))
       ;; Register the class under the new name (if alias is provided)
       (when alias
         (swap! (:classes ctx) assoc alias registered-class))
       ;; Return the class name that was registered
       intern-name))
   :cljs
   (defn process-intern
     "In ClojureScript, intern is not supported. Use registerClass instead."
     [ctx {:keys [path class-name alias]}]
     (throw (ex-info "intern is not supported in ClojureScript. Parse on the JVM and send the AST, or use registerClass to manually register classes."
                    {:path path :class-name class-name :alias alias}))))

#?(:clj
   (defn resolve-interned-classes
     "Resolve intern declarations to the class ASTs they bring into scope for static analysis.
      Returns a flat sequence of class definitions, including recursively interned classes.
      Aliased interns are represented as an additional class entry with the alias name."
     ([source-id program]
      (resolve-interned-classes source-id program #{}))
     ([source-id program seen-files]
      (letfn [(resolve* [current-source current-program seen]
                (let [ctx (assoc (make-context) :debug-source current-source)]
                  (reduce
                   (fn [{:keys [classes seen]} {:keys [path class-name alias]}]
                     (let [file-path (find-intern-file ctx path class-name)
                           canonical (.getCanonicalPath (clojure.java.io/file file-path))]
                       (if (contains? seen canonical)
                         {:classes classes :seen seen}
                         (let [file-ast (parser/ast (slurp file-path))
                               nested (resolve* canonical file-ast (conj seen canonical))
                               direct-classes (:classes file-ast)
                               all-file-classes (concat direct-classes (:classes nested))
                               aliased-class (when alias
                                               (when-let [class-def (some #(when (= (:name %) class-name) %) all-file-classes)]
                                                 [(assoc class-def :name alias)]))]
                           {:classes (into classes (concat all-file-classes aliased-class))
                            :seen (:seen nested)}))))
                   {:classes [] :seen seen}
                   (:interns current-program))))]
        (:classes (resolve* source-id program seen-files)))))
   :cljs
   (defn resolve-interned-classes
     [& _]
     []))

(defmethod eval-node :program
  [ctx {:keys [imports interns classes functions statements calls]}]
  ;; First, store all import statements (for code generation)
  (doseq [import-node imports]
    (when (map? import-node)
      (swap! (:imports ctx) conj import-node)))

  ;; Then, process all intern statements
  (doseq [intern-node interns]
    (when (map? intern-node)
      (process-intern ctx intern-node)))

  ;; Register all class definitions
  (doseq [class-node classes]
    (when (map? class-node)
      (register-class ctx class-node)))

  ;; Register and instantiate functions
  (doseq [fn-node functions]
    (when (map? fn-node)
      (eval-node ctx fn-node)))

  ;; Execute top-level executable statements in source order.
  ;; Fall back to legacy :calls-only programs if :statements is absent.
  (doseq [stmt-node (if (seq statements) statements calls)]
    (when (map? stmt-node)
      (eval-node ctx stmt-node)))

  ;; Return the context for inspection
  ctx)

(defmethod eval-node :class
  [ctx class-def]
  ;; Classes are just registered, not executed
  (register-class ctx class-def)
  nil)

(defmethod eval-node :function
  [ctx {:keys [name class-def class-name]}]
  ;; Register the generated function class and create a global instance
  (register-class ctx class-def)
  (let [obj (make-object class-name {})]
    (env-define (:current-env ctx) name obj)
    obj))

(defmethod eval-node :anonymous-function
  [ctx {:keys [class-def class-name]}]
  ;; Register the generated function class and return an object with closure env
  (register-class ctx class-def)
  (make-object class-name {} (:current-env ctx)))

(defn- write-back-target!
  "Propagate an updated object value back into a direct target expression.
   Supports plain variables, collection access (`get`), and field access."
  [ctx target-expr value & [old-value]]
  (letfn [(eval-target [expr]
            (if (string? expr)
              (env-lookup (:current-env ctx) expr)
              (eval-node ctx expr)))]
    (let [updated? (cond
                     (string? target-expr)
                     (do
                       (env-set! (:current-env ctx) target-expr value)
                       true)

                     (and (map? target-expr) (= :call (:type target-expr)))
                     (let [{:keys [target method args has-parens]} target-expr]
                       (cond
                         (some-> (-> (eval-target target-expr) meta write-back-target-key)
                                 (#(write-back-target! ctx % value old-value)))
                         true

                         (and has-parens (= method "get") (= 1 (count args)))
                         (let [coll (eval-target target)
                               idx  (eval-node ctx (first args))]
                           (cond
                             (nex-array? coll) (do (nex-array-set coll idx value) true)
                             (nex-map? coll)   (do (nex-map-put coll idx value) true)
                             :else false))

                         (and (false? has-parens)
                              target)
                         (let [parent (eval-target target)]
                           (if (and (nex-object? parent)
                                    (contains? (:fields parent) (keyword method)))
                             (let [updated-parent (make-object (:class-name parent)
                                                               (assoc (:fields parent) (keyword method) value)
                                                               (:closure-env parent))]
                               (write-back-target! ctx target updated-parent parent))
                             false))

                         :else false))

                     :else false)]
      (when updated?
        (env-replace-object-aliases! (:current-env ctx) old-value value))
      updated?)))

(defn dispatch-parent-call
  "Dispatch a call to a specific parent class's method/constructor on the current object."
  [ctx current-obj parent-class-name method arg-values]
  (let [parent-class-def (lookup-class ctx parent-class-name)
        ;; Try method first
        method-lookup (lookup-method-with-inheritance ctx parent-class-def method (count arg-values))
        ;; If no method found, try constructor
        ctor-def (when-not method-lookup
                   (lookup-constructor parent-class-def method))]
    (if-let [callable (or (:method method-lookup) ctor-def)]
      (let [class-def (lookup-class ctx (:class-name current-obj))
            all-fields (get-all-fields ctx class-def)
            ;; Track which fields belong to the parent class (for selective propagation)
            parent-fields (get-all-fields ctx parent-class-def)
            parent-field-names (set (map :name parent-fields))
            method-env (make-env (:current-env ctx))
            ;; Define fields first, then params (so params shadow fields with same name)
            _ (doseq [[field-name field-val] (:fields current-obj)]
                (env-define method-env (name field-name) field-val))
            params (:params callable)
            _ (ensure-callable-defined! callable)
            _ (when params
                (doseq [[param arg-val] (map vector params arg-values)]
                  (env-define method-env (:name param) arg-val)))
            return-type (:return-type callable)
            default-result (when return-type (get-default-field-value return-type))
            _ (env-define method-env "result" default-result)
            _ (env-define method-env "this" current-obj)
            new-ctx (-> ctx
                       (assoc :current-env method-env)
                       (assoc :current-object current-obj)
                       (assoc :current-target (:current-target ctx))
                       (assoc :current-class-name parent-class-name)
                       (assoc :current-method-name method)
                       (update :debug-stack (fnil conj [])
                               {:class parent-class-name
                                :method method
                                :env method-env
                                :arg-names (set (map :name (or params [])))
                                :field-names (set (map :name all-fields))
                                :source (:debug-source ctx)})
                       (assoc :debug-depth (inc (or (:debug-depth ctx) 0))))
            _ (if-let [rescue (:rescue callable)]
                (eval-body-with-rescue new-ctx (:body callable) rescue)
                (doseq [stmt (:body callable)]
                  (eval-node new-ctx stmt)))
            updated-fields (reduce (fn [m field]
                                    (let [field-name (:name field)
                                          field-key (keyword field-name)
                                          val (try
                                                (env-lookup method-env field-name)
                                                (catch #?(:clj Exception :cljs :default) _ ::not-found))]
                                      (if (not= val ::not-found)
                                        (assoc m field-key val)
                                        m)))
                                  (:fields current-obj)
                                  all-fields)
            updated-obj (make-object (:class-name current-obj) updated-fields (:closure-env current-obj))
            result (let [res (try
                               (env-lookup method-env "result")
                               (catch #?(:clj Exception :cljs :default) _ ::not-found))]
                     (if (not= res ::not-found)
                       res
                       nil))]
        ;; Update the object in the calling context
        (when-let [tgt (:current-target ctx)]
          (try
            (env-set! (:current-env ctx) tgt updated-obj)
            (catch #?(:clj Exception :cljs :default) _)))
        ;; Only update field env vars that belong to the parent class
        (doseq [[field-name field-val] (:fields updated-obj)]
          (when (contains? parent-field-names (name field-name))
            (try
              (env-set! (:current-env ctx) (name field-name) field-val)
              (catch #?(:clj Exception :cljs :default) _))))
        result)
      (throw (ex-info (str "Method not found in parent " parent-class-name ": " method)
                      {:parent parent-class-name :method method})))))

(defmethod eval-node :call
  [ctx {:keys [target method args has-parens]}]
  (maybe-debug-pause ctx {:type :call :target target :method method :args args :has-parens has-parens})
  (if (and (map? target) (= :create (:type target)) (nil? method))
    (eval-node ctx (assoc target :args args))
    (let [arg-values (mapv #(eval-node ctx %) args)]
      (if target
      (let [target-name (when (string? target) target)
            class-target (when target-name (lookup-class-if-exists ctx target-name))
            ;; Check if target is a parent class name (parent-qualified call: A.method())
            parent-class (when (and target-name (:current-object ctx))
                           (let [cls (lookup-class-if-exists ctx target-name)]
                             (when (and cls
                                        (is-parent? ctx (:class-name (:current-object ctx)) target-name))
                               cls)))
            obj (when-not parent-class
                  (if class-target
                    nil
                    (if target-name
                    (env-lookup (:current-env ctx) target-name)
                    (eval-node ctx target))))]
        (cond
          ;; Class-qualified constant access: A.CONST
          (and class-target
               (false? has-parens)
               (lookup-class-constant ctx class-target method))
          (eval-class-constant ctx class-target method)

          ;; Parent-qualified call: A.method() where A is a parent class
          parent-class
          (dispatch-parent-call ctx (:current-object ctx) target-name method arg-values)

          (nex-object? obj)
          (let [class-def (lookup-class ctx (:class-name obj))
                method-lookup (lookup-method-with-inheritance ctx class-def method (count arg-values))]
            (if method-lookup
                (let [method-def (:method method-lookup)
                    params (:params method-def)]
                (ensure-callable-defined! method-def)
                ;; Bug fix: disallow paren-less calls to methods that require arguments
                (when (and (false? has-parens) (seq params))
                  (throw (ex-info (str method " requires arguments")
                                  {:method method :params (mapv :name params)})))
                (let [source-class (:source-class method-lookup)
                    all-fields (get-all-fields ctx class-def)
                    effective-require (:effective-require method-lookup)
                    effective-ensure (:effective-ensure method-lookup)
                    has-postconditions? (seq effective-ensure)
                    old-values (when has-postconditions? (:fields obj))
                    source-obj (or (-> obj meta write-back-source-key) obj)]
                (let [method-env (make-env (or (:closure-env obj) (:current-env ctx)))
                      param-names (set (map :name params))
                      ;; Define fields first, then params — so params shadow fields
                      _ (doseq [[field-name field-val] (:fields obj)]
                          (env-define method-env (name field-name) field-val))
                      _ (bind-class-constants! ctx method-env class-def)
                      _ (when params
                          (doseq [[param arg-val] (map vector params arg-values)]
                            (env-define method-env (:name param) arg-val)))
                      modified-fields (atom #{})
                      return-type (:return-type method-def)
                      default-result (if return-type
                                      (get-default-field-value return-type)
                                      nil)
                      _ (env-define method-env "result" default-result)
                      _ (env-define method-env "this" obj)
                      new-ctx (-> ctx
                                 (assoc :current-env method-env)
                                 (assoc :current-object obj)
                                 (assoc :current-target target-name)
                                 (assoc :current-class-name (:name source-class))
                                 (assoc :current-method-name method)
                                 (assoc :old-values old-values)
                                 (assoc :modified-fields modified-fields)
                                 (update :debug-stack (fnil conj [])
                                         {:class (:name source-class)
                                          :method method
                                          :env method-env
                                          :arg-names (set (map :name (or params [])))
                                          :field-names (set (map name (keys (:fields obj))))
                                          :source (:debug-source ctx)})
                                 (assoc :debug-depth (inc (or (:debug-depth ctx) 0))))
                      _ (when-let [require-assertions effective-require]
                          (check-assertions new-ctx require-assertions Precondition))
                      _ (if-let [rescue (:rescue method-def)]
                          (eval-body-with-rescue new-ctx (:body method-def) rescue)
                          (doseq [stmt (:body method-def)]
                            (eval-node new-ctx stmt)))
                      updated-fields (reduce (fn [m field]
                                              (let [field-name (:name field)
                                                    field-key (keyword field-name)]
                                                ;; Skip fields shadowed by params unless explicitly modified via this.field :=
                                                (if (and (contains? param-names field-name)
                                                         (not (contains? @modified-fields field-name)))
                                                  m
                                                  (let [val (try
                                                              (env-lookup method-env field-name)
                                                              (catch #?(:clj Exception :cljs :default) _ ::not-found))]
                                                    (if (not= val ::not-found)
                                                      (assoc m field-key val)
                                                      m)))))
                                            (:fields obj)
                                            all-fields)
                      updated-obj (make-object (:class-name obj) updated-fields (:closure-env obj))
                      result-flag (try
                                    (env-lookup method-env "__result_assigned__")
                                    (catch #?(:clj Exception :cljs :default) _ ::not-found))
                      result (cond
                               (= result-flag "result")
                               (env-lookup method-env "result")
                               :else
                               (let [res (try
                                           (env-lookup method-env "result")
                                           (catch #?(:clj Exception :cljs :default) _ ::not-found))]
                                 (if (not= res ::not-found)
                                   res
                                   nil)))]
                  (try
                    (when-let [ensure-assertions effective-ensure]
                      (check-assertions new-ctx ensure-assertions Postcondition))
                    (check-class-invariant new-ctx class-def)
                    (write-back-target! ctx target updated-obj source-obj)
                    (annotate-reference-result target obj result)
                    (catch #?(:clj Exception :cljs :default) e
                      (write-back-target! ctx target source-obj source-obj)
                      (throw e))))))
              (let [field (lookup-field-with-inheritance ctx class-def method (:current-class-name ctx))]
                (if field
                  (let [field-val (get (:fields obj) (keyword method))]
                    (if (and has-parens (nex-object? field-val))
                      ;; Function field with parens: invoke callN on it
                      (let [call-method (str "call" (count arg-values))
                            literal-args (mapv (fn [v] {:type :literal :value v}) arg-values)]
                        (eval-node ctx {:type :call
                                        :target {:type :literal :value field-val}
                                        :method call-method
                                        :args literal-args}))
                      ;; No parens or not a Function: return field value (if no args)
                      (if (empty? arg-values)
                        field-val
                        (throw (ex-info (str "Method not found: " method)
                                        {:object obj :method method})))))
                  (if (get-in builtin-type-methods [:Any method])
                    (call-builtin-method ctx (or target-name target) obj method arg-values)
                    (throw (ex-info (str "Method not found: " method)
                                    {:object obj :method method})))))))

          (get-type-name obj)
          (call-builtin-method ctx (or target-name target) obj method arg-values)

          :else
          #?(:clj (let [compiled-class-name (compiled-runtime-class-name ctx obj)
                        compiled-class-def (when compiled-class-name
                                             (lookup-class-if-exists ctx compiled-class-name))]
                    (if compiled-class-def
                      (if (and (empty? arg-values)
                               (false? has-parens))
                        (if-let [_ (lookup-field-with-inheritance ctx
                                                                  compiled-class-def
                                                                  method
                                                                  (:current-class-name ctx))]
                          (if-let [[_ field-value] (compiled-object-field obj method)]
                            field-value
                            (throw (ex-info (str "Undefined field: " method)
                                            {:field method
                                             :class-name compiled-class-name})))
                          (if-let [_ (lookup-field-with-inheritance-any-visibility ctx
                                                                                   compiled-class-def
                                                                                   method)]
                            (throw (ex-info (str "Undefined field: " method)
                                            {:field method
                                             :class-name compiled-class-name}))
                            (runtime-resolve-call-user-method ctx obj method arg-values)))
                        (runtime-resolve-call-user-method ctx obj method arg-values))
                      (java-call-method obj method arg-values)))
             :cljs (throw (ex-info (str "Method not found on type: " method)
                                   {:target target :value obj :method method})))))

      (let [fn-obj (try
                     (env-lookup (:current-env ctx) method)
                     (catch #?(:clj Exception :cljs :default) _ ::not-found))]
        (if (not= fn-obj ::not-found)
          (let [compiled-callable? #?(:clj (boolean (compiled-runtime-class-name ctx fn-obj))
                                      :cljs false)]
            (if (or (nex-object? fn-obj) compiled-callable?)
            (if (not= has-parens false)
              ;; has-parens is true or nil (default): invoke the Function
              (let [call-method (str "call" (count args))]
                (eval-node ctx {:type :call
                                :target method
                                :method call-method
                                :args args}))
              ;; has-parens is false: return the Function object
              fn-obj)
            ;; Variable value found (non-callable). In no-parens form, treat as identifier.
            ;; This keeps expressions like x + 1 working when parser emits :call for bare identifiers.
            (if (false? has-parens)
              fn-obj
              (throw (ex-info (str "Undefined function: " method)
                              {:function method})))))
          (if-let [current-obj (:current-object ctx)]
            (let [class-def (lookup-class ctx (:class-name current-obj))
                  method-lookup (lookup-method-with-inheritance ctx
                                                                class-def
                                                                method
                                                                (count args)
                                                                (:current-class-name ctx))]
              (if method-lookup
                (let [all-fields (get-all-fields ctx class-def)
                  current-env (:current-env ctx)
                  updated-fields (reduce (fn [m field]
                                          (let [field-name (:name field)
                                                field-key (keyword field-name)
                                                val (try
                                                      (env-lookup current-env field-name)
                                                      (catch #?(:clj Exception :cljs :default) _ ::not-found))]
                                            (if (not= val ::not-found)
                                              (assoc m field-key val)
                                              m)))
                                        (:fields current-obj)
                                        all-fields)
                  updated-obj (make-object (:class-name current-obj) updated-fields (:closure-env current-obj))
                  _ (when-let [target-name (:current-target ctx)]
                      (env-set! (-> ctx :current-env :parent) target-name updated-obj))
                  result (eval-node ctx {:type :call
                                         :target (:current-target ctx)
                                         :method method
                                         :args args})
                  called-obj (when-let [target-name (:current-target ctx)]
                               (env-lookup (-> ctx :current-env :parent) target-name))
                  _ (when called-obj
                      (doseq [[field-name field-val] (:fields called-obj)]
                        (env-set! current-env (name field-name) field-val)))]
              result)
                (if-let [builtin (get builtins method)]
                  (apply builtin ctx arg-values)
                  (throw (ex-info (str "Undefined method: " method)
                                  {:function method :object current-obj})))))
            (if-let [builtin (get builtins method)]
              (apply builtin ctx arg-values)
              (throw (ex-info (str "Undefined function: " method)
                              {:function method}))))))))))

(defmethod eval-node :this
  [ctx _]
  (:current-object ctx))

(defmethod eval-node :member-assign
  [ctx {:keys [object object-type field value]}]
  (maybe-debug-pause ctx {:type :member-assign :object object :object-type object-type :field field :value value})
  (let [target-expr (or object (when (= object-type :this) {:type :this}))
        target-obj (eval-node ctx target-expr)
        class-name (or (:class-name target-obj) (:current-class-name ctx))
        class-def (when class-name (lookup-class-if-exists ctx class-name))
        field-def (when class-def
                    (lookup-field-with-inheritance ctx class-def field (:current-class-name ctx)))]
    (when-not (nex-object? target-obj)
      (throw (ex-info "Field assignment target must be an object"
                      {:target target-expr :value target-obj})))
    (when (and class-def (lookup-class-constant ctx class-def field))
      (throw (ex-info (str "Cannot assign to constant: " field)
                      {:field field :constant? true})))
    (when-not field-def
      (throw (ex-info (str "Undefined field: " field)
                      {:field field :class-name class-name})))
    (when-not (= (:current-class-name ctx) (:declaring-class field-def))
      (throw (ex-info (field-write-error-message field (:declaring-class field-def))
                      {:field field
                       :class-name class-name
                       :declaring-class (:declaring-class field-def)})))
    (let [val (eval-node ctx value)]
      (if (and (= (:type target-expr) :this) (:current-object ctx))
        (do
          ;; Track that this field was explicitly modified via this.field :=
          (when-let [mf (:modified-fields ctx)]
            (swap! mf conj field))
          ;; this.field sets the env variable
          ;; (fields are tracked as env vars, extracted back to object after body)
          (env-set! (:current-env ctx) field val)
          val)
        (let [updated-obj (make-object (:class-name target-obj)
                                       (assoc (:fields target-obj) (keyword field) val)
                                       (:closure-env target-obj))
              write-back-target (if (= :identifier (:type target-expr))
                                  (:name target-expr)
                                  target-expr)]
          (when-not (write-back-target! ctx write-back-target updated-obj target-obj)
            (throw (ex-info "Field assignment target is not writable"
                            {:target target-expr :field field})))
          val)))))

(defmethod eval-node :assign
  [ctx {:keys [target value]}]
  (maybe-debug-pause ctx {:type :assign :target target :value value})
  (when-let [current-class-name (:current-class-name ctx)]
    (when-let [class-def (lookup-class-if-exists ctx current-class-name)]
      (when (lookup-class-constant ctx class-def target)
        (throw (ex-info (str "Cannot assign to constant: " target)
                        {:target target :constant? true})))))
  (let [val (eval-node ctx value)]
    ;; Assignment (without let) ONLY updates existing variables
    ;; It should fail if the variable doesn't exist
    (env-set! (:current-env ctx) target val)
    (when (#{"result"} target)
      (env-define (:current-env ctx) "__result_assigned__" target))
    ;; Strictly speaking, assignment is a statement and does not have a value,
    ;; but returning the value is helpful for repl users.
    val))

(defmethod eval-node :let
  [ctx {:keys [name value]}]
  (maybe-debug-pause ctx {:type :let :name name :value value})
  (let [val (eval-node ctx value)]
    ;; Always define a new binding in the current scope (can shadow outer scopes)
    (env-define (:current-env ctx) name val)
    (when (#{"result"} name)
      (env-define (:current-env ctx) "__result_assigned__" name))
    ;; Strictly speaking, let-binding is a statement and does not have a value,
    ;; but returning the value is helpful for repl users.
    val))

(defmethod eval-node :block
  [ctx statements]
  ;; Execute each statement and return the last value
  (when (sequential? statements)
    (last (map #(eval-node ctx %) statements))))

(defmethod eval-node :raise
  [ctx {:keys [value]}]
  (maybe-debug-pause ctx {:type :raise :value value})
  (let [val (eval-node ctx value)]
    (throw (ex-info (str val) {:type :nex-exception :value val}))))

(defmethod eval-node :retry
  [ctx _node]
  (maybe-debug-pause ctx {:type :retry})
  (throw (ex-info "retry" {:type :nex-retry})))

(defn eval-body-with-rescue
  "Execute body statements with rescue/retry support.
   If rescue contains retry, re-executes body.
   If rescue completes without retry, the exception is considered handled."
  [ctx body rescue]
  (let [should-retry (atom true)]
    (while @should-retry
      (reset! should-retry false)
      (try
        (doseq [stmt body] (eval-node ctx stmt))
        (catch #?(:clj Exception :cljs :default) e
          ;; Don't catch retry markers from nested blocks
          (if (and (instance? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e)
                   (= :nex-retry (:type (ex-data e))))
            (throw e)
            ;; Real exception — run rescue
            (let [exc-value (if (and (instance? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e)
                                    (= :nex-exception (:type (ex-data e))))
                              (:value (ex-data e))
                              #?(:clj (.getMessage e) :cljs (.-message e)))
                  rescue-env (make-env (:current-env ctx))
                  _ (env-define rescue-env "exception" exc-value)
                  rescue-ctx (assoc ctx :current-env rescue-env)]
              (try
                (doseq [stmt rescue] (eval-node rescue-ctx stmt))
                (catch #?(:clj Exception :cljs :default) re
                  (if (and (instance? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) re)
                           (= :nex-retry (:type (ex-data re))))
                    (reset! should-retry true) ;; retry: loop back
                    (throw re)))))))))))

(defmethod eval-node :scoped-block
  [ctx {:keys [body rescue]}]
  (maybe-debug-pause ctx {:type :scoped-block :body body :rescue rescue})
  ;; Create a new lexical scope
  (let [new-env (make-env (:current-env ctx))
        new-ctx (assoc ctx :current-env new-env)]
    (if rescue
      (eval-body-with-rescue new-ctx body rescue)
      ;; Execute the block in the new scope
      (last (map #(eval-node new-ctx %) body)))))

(defmethod eval-node :when
  [ctx {:keys [condition consequent alternative]}]
  (if (eval-node ctx condition)
    (eval-node ctx consequent)
    (eval-node ctx alternative)))

(defmethod eval-node :convert
  [ctx {:keys [value var-name target-type]}]
  (let [v (eval-node ctx value)
        target-name (if (map? target-type) (:base-type target-type) target-type)
        runtime-name (runtime-type-name v)
        ok? (and (some? v)
                 (string? target-name)
                 (convert-compatible-runtime? ctx runtime-name target-name))]
    (env-define (:current-env ctx) var-name (if ok? v nil))
    ok?))

(defmethod eval-node :if
  [ctx {:keys [condition then elseif else]}]
  (maybe-debug-pause ctx {:type :if :condition condition :then then :elseif elseif :else else})
  ;; Evaluate condition, then elseif chain, then optional else
  (let [cond-val (eval-node ctx condition)]
    (if cond-val
      (last (map #(eval-node ctx %) then))
      (if-let [matched (some (fn [clause]
                               (when (eval-node ctx (:condition clause))
                                 clause))
                             elseif)]
        (last (map #(eval-node ctx %) (:then matched)))
        (when else
          (last (map #(eval-node ctx %) else)))))))

(defmethod eval-node :case
  [ctx {:keys [expr clauses else]}]
  (maybe-debug-pause ctx {:type :case :expr expr :clauses clauses :else else})
  (let [val (eval-node ctx expr)
        matched (loop [cs clauses]
                  (if (empty? cs)
                    ::no-match
                    (let [{:keys [values body]} (first cs)]
                      (if (some #(= val (eval-node ctx %)) values)
                        (eval-node ctx body)
                        (recur (rest cs))))))]
    (if (= matched ::no-match)
      (if else
        (eval-node ctx else)
        (throw (ex-info "No matching case and no else clause"
                        {:value val})))
      matched)))

(defmethod eval-node :select
  [ctx {:keys [clauses else timeout] :as node}]
  (let [prepared (mapv #(prepare-select-clause ctx %) clauses)
        timeout-ms-val (when timeout (timeout-ms (eval-node ctx (:duration timeout))))
        deadline (when timeout-ms-val (+ (current-time-ms) timeout-ms-val))]
    (loop []
      (if-let [[clause outcome] (some (fn [prepared-clause]
                                        (when-let [outcome (attempt-select-clause prepared-clause)]
                                          [prepared-clause outcome]))
                                      prepared)]
        (execute-select-body ctx (:body clause) (:alias clause) (:value outcome))
        (if else
          (last (map #(eval-node ctx %) else))
          (if (and deadline (>= (current-time-ms) deadline))
            (last (map #(eval-node ctx %) (:body timeout)))
            (do
              (sleep-select-step!)
              (recur))))))))

(defmethod eval-node :loop
  [ctx {:keys [init invariant variant until body]}]
  (maybe-debug-pause ctx {:type :loop :init init :invariant invariant :variant variant :until until :body body})
  (let [loop-env (make-env (:current-env ctx))
        loop-ctx (assoc ctx :current-env loop-env)]
    ;; Execute initialization statements inside the loop scope.
    (doseq [stmt init]
      (eval-node loop-ctx stmt))

    ;; Loop until the 'until' condition becomes true
    (loop [last-result nil
           prev-variant nil
           iteration 0]
      ;; Check invariant before iteration (if present)
      (when invariant
        (check-assertions loop-ctx invariant Loop-invariant))

      ;; Check exit condition
      (let [until-val (eval-node loop-ctx until)]
        (if until-val
          ;; Exit loop
          last-result
          ;; Continue loop
          (let [;; Evaluate variant before body (if present)
                curr-variant (when variant (eval-node loop-ctx variant))

                ;; Check variant decreases (if present and not first iteration)
                _ (when (and variant prev-variant)
                    (when-not (< curr-variant prev-variant)
                      (throw (ex-info "Loop variant must decrease"
                                      {:iteration iteration
                                       :previous-variant prev-variant
                                       :current-variant curr-variant}))))

                ;; Execute loop body in a NEW scope each iteration
                ;; This ensures 'let' creates shadowed variables that don't persist
                ;; while plain ':=' can still update variables in parent scopes
                body-env (make-env (:current-env loop-ctx))
                body-ctx (assoc loop-ctx :current-env body-env)
                result (last (map #(eval-node body-ctx %) body))]

            ;; Check invariant after iteration (if present)
            (when invariant
              (check-assertions loop-ctx invariant Loop-invariant))

            ;; Recur with new state
            (recur result curr-variant (inc iteration))))))))

(defmethod eval-node :statement
  [ctx node]
  ;; Statement wrapper - just evaluate the inner node
  (eval-node ctx node))

(defmethod eval-node :binary
  [ctx {:keys [operator left right]}]
  (let [left-val (eval-node ctx left)
        right-val (eval-node ctx right)]
    (if (and (= operator "+")
             (or (string? left-val) (string? right-val)))
      (str (concat-string-value ctx left-val)
           (concat-string-value ctx right-val))
      (apply-binary-op operator left-val right-val))))

(defmethod eval-node :unary
  [ctx {:keys [operator expr]}]
  (let [val (eval-node ctx expr)]
    (apply-unary-op operator val)))

(defmethod eval-node :integer
  [_ctx {:keys [value]}]
  value)

(defmethod eval-node :real
  [_ctx {:keys [value]}]
  value)

(defmethod eval-node :boolean
  [_ctx {:keys [value]}]
  value)

(defmethod eval-node :char
  [_ctx {:keys [value]}]
  value)

(defmethod eval-node :string
  [_ctx {:keys [value]}]
  value)

(defmethod eval-node :nil
  [_ctx _node]
  nil)

(defmethod eval-node :array-literal
  [ctx {:keys [elements]}]
  ;; Evaluate all elements and return as a mutable array
  (nex-array-from (mapv #(eval-node ctx %) elements)))

(defmethod eval-node :set-literal
  [ctx {:keys [elements]}]
  (nex-set-from (mapv #(eval-node ctx %) elements)))

(defmethod eval-node :map-literal
  [ctx {:keys [entries]}]
  ;; Evaluate all key-value pairs and return as a mutable map
  (nex-map-from (mapv (fn [{:keys [key value]}]
                        [(eval-node ctx key) (eval-node ctx value)])
                      entries)))

(defmethod eval-node :identifier
  [ctx {:keys [name]}]
  (let [val (try
              (env-lookup (:current-env ctx) name)
              (catch #?(:clj Exception :cljs :default) _ ::not-found))]
    (if (not= val ::not-found)
      val
      ;; Not in env - check if it's a zero-arg method on current object
      (if-let [current-class-name (:current-class-name ctx)]
        (let [class-def (lookup-class ctx current-class-name)]
          (if-let [constant (lookup-class-constant ctx class-def name)]
            (eval-class-constant ctx (:declaring-class constant class-def) name)
            (if-let [current-obj (:current-object ctx)]
              (let [method-lookup (lookup-method-with-inheritance ctx
                                                                  class-def
                                                                  name
                                                                  0
                                                                  current-class-name)]
                (if method-lookup
                  ;; It's a method - invoke it (implicit this)
                  (eval-node ctx {:type :call
                                  :target (:current-target ctx)
                                  :method name
                                  :args []})
                  (throw (ex-info (str "Undefined variable: " name)
                                  {:var-name name}))))
              (throw (ex-info (str "Undefined variable: " name)
                              {:var-name name})))))
        (throw (ex-info (str "Undefined variable: " name)
                        {:var-name name}))))))

(defn get-all-fields
  "Get all fields from a class and its parents"
  [ctx class-def]
  (let [;; Get fields from parents first
        parent-fields (when-let [parents (get-parent-classes ctx class-def)]
                       (mapcat (fn [parent-info]
                                (get-all-fields ctx (:class-def parent-info)))
                              parents))
        ;; Get fields from current class
        current-fields (->> (:body class-def)
                           (mapcat (fn [section]
                                    (when (= (:type section) :feature-section)
                                      (:members section))))
                           (filter #(and (= (:type %) :field)
                                         (not (:constant? %)))))]
    (concat parent-fields current-fields)))

(defn lookup-constructor
  "Look up a constructor by name in a class"
  [class-def constructor-name]
  (->> (:body class-def)
       (mapcat (fn [section]
                 (when (= (:type section) :constructors)
                   (:constructors section))))
       (filter #(= (:name %) constructor-name))
       first))

(defn lookup-constructor-with-inheritance
  "Look up a constructor by name in a class and its parent chain."
  [ctx class-def constructor-name]
  (or (some-> (lookup-constructor class-def constructor-name)
              (assoc :source-class class-def))
      (some (fn [{:keys [class-def]}]
              (lookup-constructor-with-inheritance ctx class-def constructor-name))
            (get-parent-classes ctx class-def))))

(defmethod eval-node :create
  [ctx {:keys [class-name generic-args constructor args]}]
  ;; Handle built-in IO types
  (case class-name
    "Console" {:nex-builtin-type :Console}
    "Process" {:nex-builtin-type :Process}
    "Array" (let [arg-values (mapv #(eval-node ctx %) args)]
              (cond
                (nil? constructor) (nex-array)
                (= constructor "filled")
                (let [[size value] arg-values]
                  (when-not (integer? size)
                    (throw (ex-info "Array.filled requires an Integer size"
                                    {:class-name "Array" :constructor constructor})))
                  (when (neg? size)
                    (throw (ex-info "Array size must be non-negative"
                                    {:class-name "Array" :constructor constructor})))
                  (nex-array-from (vec (repeat size value))))
                :else
                (throw (ex-info (str "Constructor not found: Array." constructor)
                                {:class-name "Array" :constructor constructor}))))
    "Map" (nex-map)
    "Min_Heap" (let [arg-values (mapv #(eval-node ctx %) args)]
                 (cond
                   (or (nil? constructor) (= constructor "empty"))
                   (do
                     (when (seq arg-values)
                       (throw (ex-info "Min_Heap.empty expects no arguments"
                                       {:class-name "Min_Heap" :constructor constructor})))
                     (make-min-heap nil))

                   (= constructor "from_comparator")
                   (do
                     (when-not (= 1 (count arg-values))
                       (throw (ex-info "Min_Heap.from_comparator expects 1 argument"
                                       {:class-name "Min_Heap" :constructor constructor})))
                     (make-min-heap (first arg-values)))

                   :else
                   (throw (ex-info (str "Constructor not found: Min_Heap." constructor)
                                   {:class-name "Min_Heap" :constructor constructor}))))
    "Atomic_Integer" (let [arg-values (mapv #(eval-node ctx %) args)]
                       (when-not (= constructor "make")
                         (throw (ex-info (str "Constructor not found: Atomic_Integer." constructor)
                                         {:class-name "Atomic_Integer" :constructor constructor})))
                       (when-not (= 1 (count arg-values))
                         (throw (ex-info "Atomic_Integer.make expects 1 argument"
                                         {:class-name "Atomic_Integer" :constructor constructor})))
                       (make-atomic-integer (first arg-values)))
    "Atomic_Integer64" (let [arg-values (mapv #(eval-node ctx %) args)]
                         (when-not (= constructor "make")
                           (throw (ex-info (str "Constructor not found: Atomic_Integer64." constructor)
                                           {:class-name "Atomic_Integer64" :constructor constructor})))
                         (when-not (= 1 (count arg-values))
                           (throw (ex-info "Atomic_Integer64.make expects 1 argument"
                                           {:class-name "Atomic_Integer64" :constructor constructor})))
                         (make-atomic-integer64 (first arg-values)))
    "Atomic_Boolean" (let [arg-values (mapv #(eval-node ctx %) args)]
                       (when-not (= constructor "make")
                         (throw (ex-info (str "Constructor not found: Atomic_Boolean." constructor)
                                         {:class-name "Atomic_Boolean" :constructor constructor})))
                       (when-not (= 1 (count arg-values))
                         (throw (ex-info "Atomic_Boolean.make expects 1 argument"
                                         {:class-name "Atomic_Boolean" :constructor constructor})))
                       (make-atomic-boolean (first arg-values)))
    "Atomic_Reference" (let [arg-values (mapv #(eval-node ctx %) args)]
                         (when-not (= constructor "make")
                           (throw (ex-info (str "Constructor not found: Atomic_Reference." constructor)
                                           {:class-name "Atomic_Reference" :constructor constructor})))
                         (when-not (= 1 (count arg-values))
                           (throw (ex-info "Atomic_Reference.make expects 1 argument"
                                           {:class-name "Atomic_Reference" :constructor constructor})))
                         (make-atomic-reference (first arg-values)))
    "Channel" #?(:clj (let [arg-values (mapv #(eval-node ctx %) args)]
                        (cond
                          (nil? constructor) (make-channel)
                          (= constructor "with_capacity")
                          (let [capacity (first arg-values)]
                            (when-not (integer? capacity)
                              (throw (ex-info "Channel.with_capacity requires an Integer capacity"
                                              {:class-name "Channel" :constructor constructor})))
                            (when (neg? capacity)
                              (throw (ex-info "Channel capacity must be non-negative"
                                              {:class-name "Channel" :constructor constructor})))
                            (make-channel capacity))
                          :else
                          (throw (ex-info (str "Constructor not found: Channel." constructor)
                                          {:class-name "Channel" :constructor constructor}))))
                 :cljs (throw (ex-info "Channels are not supported in ClojureScript interpreter"
                                       {:class-name "Channel"})))
    "Set" (let [arg-values (mapv #(eval-node ctx %) args)]
            (cond
              (nil? constructor) (nex-set)
              (= constructor "from_array") (let [source (first arg-values)]
                                             (cond
                                               (nex-array? source) (nex-set-from #?(:clj source :cljs (array-seq source)))
                                               (sequential? source) (nex-set-from source)
                                               :else (throw (ex-info "Set.from_array requires an array"
                                                                     {:class-name "Set"}))))
              :else (throw (ex-info (str "Constructor not found: Set." constructor)
                                    {:class-name "Set" :constructor constructor}))))
  ;; Resolve effective class name (handle generic specialization)
  (let [effective-class-name
        (if (seq generic-args)
          (let [spec-name (specialized-class-name class-name generic-args)]
            (if (lookup-specialized-class ctx spec-name)
              spec-name
              ;; Need to create the specialization
              (let [template (get @(:classes ctx) class-name)]
                (when-not template
                  (throw (ex-info (str "Undefined template class: " class-name)
                                  {:class-name class-name})))
                (when-not (:generic-params template)
                  (throw (ex-info (str "Class " class-name " is not generic")
                                  {:class-name class-name})))
                (when (not= (count (:generic-params template)) (count generic-args))
                  (throw (ex-info (str "Type argument count mismatch for " class-name
                                       ": expected " (count (:generic-params template))
                                       ", got " (count generic-args))
                                  {:class-name class-name
                                   :expected (count (:generic-params template))
                                   :got (count generic-args)})))
                (let [specialized (specialize-class template generic-args)]
                  (register-specialized-class ctx specialized)
                  spec-name))))
          class-name)
        class-def (lookup-class-if-exists ctx effective-class-name)
        _ (when (and class-def (:deferred? class-def))
            (throw (ex-info (str "Cannot instantiate deferred class: " class-name)
                            {:class-name class-name
                             :deferred? true})))
        ;; Get all fields (including inherited)
        all-fields (when class-def (get-all-fields ctx class-def))
        ;; Initialize fields with default values
        initial-field-map (when class-def
                            (reduce (fn [m field]
                                      (assoc m (keyword (:name field))
                                             (get-default-field-value (:field-type field))))
                                    {}
                                    all-fields))
        ;; If a constructor is specified, call it and update fields
        final-field-map (when class-def
                          (if constructor
                            (let [ctor-def (lookup-constructor-with-inheritance ctx class-def constructor)]
                              (when-not ctor-def
                                (throw (ex-info (str "Constructor not found: " class-name "." constructor)
                                                {:class-name class-name :constructor constructor})))
                              ;; Create environment for constructor execution
                              (let [ctor-env (make-env (:current-env ctx))
                                    ;; Bind fields as local variables FIRST
                                    _ (doseq [[field-name field-val] initial-field-map]
                                        (env-define ctor-env (name field-name) field-val))
                                    _ (bind-class-constants! ctx ctor-env class-def)
                                    ;; Bind parameters SECOND (so they shadow fields with same name)
                                    params (:params ctor-def)
                                    arg-values (mapv #(eval-node ctx %) args)
                                    _ (when params
                                        (doseq [[param arg-val] (map vector params arg-values)]
                                          (env-define ctor-env (:name param) arg-val)))
                                    temp-obj (make-object effective-class-name initial-field-map)
                                    source-class-name (:name (:source-class ctor-def))
                                    new-ctx (-> ctx
                                               (assoc :current-env ctor-env)
                                               (assoc :current-object temp-obj)
                                               (assoc :current-class-name source-class-name)
                                               (assoc :current-method-name constructor)
                                               (update :debug-stack (fnil conj [])
                                                       {:class source-class-name
                                                        :method (or constructor "make")
                                                        :env ctor-env
                                                        :arg-names (set (map :name (or params [])))
                                                        :field-names (set (map name (keys initial-field-map)))
                                                        :source (:debug-source ctx)})
                                               (assoc :debug-depth (inc (or (:debug-depth ctx) 0))))
                                    ;; Check pre-conditions
                                    _ (when-let [require-assertions (:require ctor-def)]
                                        (check-assertions new-ctx require-assertions Precondition))
                                    ;; Execute constructor body
                                    _ (if-let [rescue (:rescue ctor-def)]
                                        (eval-body-with-rescue new-ctx (:body ctor-def) rescue)
                                        (doseq [stmt (:body ctor-def)]
                                          (eval-node new-ctx stmt)))
                                    ;; Update object fields from modified environment
                                 updated-fields (reduce (fn [m field]
                                                         (let [field-name (:name field)
                                                               field-key (keyword field-name)
                                                               val (try
                                                                     (env-lookup ctor-env field-name)
                                                                     (catch #?(:clj Exception :cljs :default) _ ::not-found))]
                                                           (if (not= val ::not-found)
                                                             (assoc m field-key val)
                                                             m)))
                                                       initial-field-map
                                                       all-fields)
                                    ;; Check post-conditions
                                    _ (when-let [ensure-assertions (:ensure ctor-def)]
                                        (check-assertions new-ctx ensure-assertions Postcondition))]
                                updated-fields))
                            ;; No constructor: use default initialization
                            initial-field-map))
        ;; Create the final object
        obj (when class-def
              (make-object effective-class-name final-field-map))]

    ;; Check class invariant with object fields in scope
    (if class-def
      (do
        (let [inv-env (make-env (:current-env ctx))
              _ (doseq [[field-name field-val] final-field-map]
                  (env-define inv-env (name field-name) field-val))
              inv-ctx (assoc ctx :current-env inv-env)]
          (check-class-invariant inv-ctx class-def))
        ;; Return the object
        obj)
      ;; Java interop fallback (CLJ only)
      #?(:clj (java-create-object ctx class-name (mapv #(eval-node ctx %) args))
         :cljs (throw (ex-info (str "Undefined class: " class-name)
                               {:class-name class-name})))))))

(defmethod eval-node :spawn
  [ctx {:keys [body]}]
  #?(:clj
     (make-task
       (CompletableFuture/supplyAsync
        (reify java.util.function.Supplier
          (get [_]
            (let [spawn-env (make-env (:current-env ctx))
                  _ (env-define spawn-env "result" nil)
                  spawn-ctx (assoc ctx :current-env spawn-env)]
              (doseq [stmt body]
                (eval-node spawn-ctx stmt))
              (let [result-flag (try
                                  (env-lookup spawn-env "__result_assigned__")
                                  (catch Exception _ ::not-found))]
                (if (= result-flag "result")
                  (env-lookup spawn-env "result")
                  nil)))))
        concurrent-executor))
     :cljs
     (throw (ex-info "spawn is not supported in ClojureScript interpreter"
                     {:type :unsupported}))))

(defmethod eval-node :literal
  [_ctx node]
  ;; Handle literal values that might be passed directly
  (cond
    (string? node) node
    (map? node) (:value node)
    :else node))

(defmethod eval-node :old
  [ctx {:keys [expr]}]
  ;; Look up the value from the old-values snapshot
  (if-let [old-values (:old-values ctx)]
    (if (and (map? expr) (= (:type expr) :identifier))
      ;; Simple identifier: look up in old values
      (let [var-name (:name expr)]
        (if (contains? old-values (keyword var-name))
          (get old-values (keyword var-name))
          (throw (ex-info (str "'old' can only be used on object fields in postconditions")
                         {:variable var-name}))))
      ;; More complex expression: evaluate it in an environment with old values
      (let [old-env (make-env (:current-env ctx))
            _ (doseq [[field-name field-val] old-values]
                (env-define old-env (name field-name) field-val))
            old-ctx (assoc ctx :current-env old-env)]
        (eval-node old-ctx expr)))
    (throw (ex-info "'old' can only be used in postconditions"
                   {:expr expr}))))

(defmethod eval-node :with
  [ctx {:keys [target body]}]
  ;; With statements are for code generation only, skip in interpreter
  ;; Could optionally evaluate for a specific target if needed
  nil)

(defmethod eval-node :default
  [ctx node]
  ;; If it's a plain string (identifier), look it up
  (if (string? node)
    (env-lookup (:current-env ctx) node)
    (throw (ex-info (str "Cannot evaluate node type: " (or (:type node) (type node)))
                    {:node node}))))

(defn- async-result-value [env]
  (let [result-flag (try
                      (env-lookup env "__result_assigned__")
                      (catch #?(:clj Exception :cljs :default) _ ::not-found))]
    (if (= result-flag "result")
      (env-lookup env "result")
      (let [res (try
                  (env-lookup env "result")
                  (catch #?(:clj Exception :cljs :default) _ ::not-found))]
        (if (not= res ::not-found) res nil)))))

(defn- check-assertions-async [ctx assertions contract-type]
  #?(:clj
     (do
       (check-assertions ctx assertions contract-type)
       nil)
     :cljs
     (promise-reduce assertions nil
                     (fn [_ {:keys [label condition]}]
                       (.then (->promise (eval-node-async ctx condition))
                              (fn [result]
                                (when-not result
                                  (report-contract-violation contract-type label condition))
                                nil))))))

(defn- check-class-invariant-async [ctx class-def]
  (letfn [(collect-invariants [class-def seen]
            (let [class-name (:name class-def)
                  already-seen? (and class-name (contains? seen class-name))
                  seen' (if class-name (conj seen class-name) seen)]
              (if already-seen?
                [[] seen]
                (let [[parent-invariants seen'']
                      (if-let [parents (get-parent-classes ctx class-def)]
                        (reduce (fn [[acc seen-so-far] {parent-class-def :class-def}]
                                  (let [[inv seen-next] (collect-invariants parent-class-def seen-so-far)]
                                    [(into acc inv) seen-next]))
                                [[] seen']
                                parents)
                        [[] seen'])
                      local-invariants (or (:invariant class-def) [])]
                  [(vec (concat parent-invariants local-invariants)) seen'']))))]
    (let [[assertions _] (collect-invariants class-def #{})]
      #?(:clj
         (do
           (when (seq assertions)
             (check-assertions ctx assertions Class-invariant))
           nil)
         :cljs
         (if (seq assertions)
           (check-assertions-async ctx assertions Class-invariant)
           (js/Promise.resolve nil))))))

(defn- eval-body-async [ctx body]
  #?(:clj
     (last (map #(eval-node ctx %) body))
     :cljs
     (promise-reduce body nil
                     (fn [_ stmt]
                       (eval-node-async ctx stmt)))))

(defn- async-free-node?
  [node]
  (cond
    (nil? node) true
    (string? node) true
    (not (map? node)) true
    :else
    (case (:type node)
      :integer true
      :real true
      :boolean true
      :char true
      :string true
      :nil true
      :identifier true
      :literal true
      :old true
      :unary (async-free-node? (:expr node))
      :binary (and (async-free-node? (:left node))
                   (async-free-node? (:right node)))
      :array-literal (every? async-free-node? (:elements node))
      :set-literal (every? async-free-node? (:elements node))
      :map-literal (every? (fn [{:keys [key value]}]
                             (and (async-free-node? key)
                                  (async-free-node? value)))
                           (:entries node))
      :statement (async-free-node? (:node node))
      false)))

(defn- eval-body-with-rescue-async [ctx body rescue]
  #?(:clj
     (eval-body-with-rescue ctx body rescue)
     :cljs
     (letfn [(run-body []
               (.catch
                (->promise (eval-body-async ctx body))
                (fn [e]
                  (if (and (instance? ExceptionInfo e)
                           (= :nex-retry (:type (ex-data e))))
                    (js/Promise.reject e)
                    (let [exc-value (if (and (instance? ExceptionInfo e)
                                             (= :nex-exception (:type (ex-data e))))
                                      (:value (ex-data e))
                                      (.-message e))
                          rescue-env (make-env (:current-env ctx))
                          _ (env-define rescue-env "exception" exc-value)
                          rescue-ctx (assoc ctx :current-env rescue-env)]
                      (.catch
                       (.then (->promise (eval-body-async rescue-ctx rescue))
                              (fn [_] nil))
                       (fn [re]
                         (if (and (instance? ExceptionInfo re)
                                  (= :nex-retry (:type (ex-data re))))
                           (run-body)
                           (js/Promise.reject re)))))))))]
       (run-body))))

(defn- dispatch-parent-call-async
  [ctx current-obj parent-class-name method arg-values]
  #?(:clj
     (dispatch-parent-call ctx current-obj parent-class-name method arg-values)
     :cljs
     (let [parent-class-def (lookup-class ctx parent-class-name)
           method-lookup (lookup-method-with-inheritance ctx parent-class-def method (count arg-values))
           ctor-def (when-not method-lookup
                      (lookup-constructor parent-class-def method))]
       (if-let [callable (or (:method method-lookup) ctor-def)]
         (let [class-def (lookup-class ctx (:class-name current-obj))
               all-fields (get-all-fields ctx class-def)
               parent-fields (get-all-fields ctx parent-class-def)
               parent-field-names (set (map :name parent-fields))
               method-env (make-env (:current-env ctx))
               _ (doseq [[field-name field-val] (:fields current-obj)]
                   (env-define method-env (name field-name) field-val))
               params (:params callable)
               _ (ensure-callable-defined! callable)
               _ (doseq [[param arg-val] (map vector params arg-values)]
                   (env-define method-env (:name param) arg-val))
               return-type (:return-type callable)
               default-result (when return-type (get-default-field-value return-type))
               _ (env-define method-env "result" default-result)
               _ (env-define method-env "this" current-obj)
               new-ctx (-> ctx
                           (assoc :current-env method-env)
                           (assoc :current-object current-obj)
                           (assoc :current-target (:current-target ctx))
                           (assoc :current-class-name (:class-name current-obj))
                           (assoc :current-method-name method)
                           (update :debug-stack (fnil conj [])
                                   {:class (:class-name current-obj)
                                    :method method
                                    :env method-env
                                    :arg-names (set (map :name (or params [])))
                                    :field-names (set (map :name all-fields))
                                    :source (:debug-source ctx)})
                           (assoc :debug-depth (inc (or (:debug-depth ctx) 0))))]
           (.then (->promise (if-let [rescue (:rescue callable)]
                               (eval-body-with-rescue-async new-ctx (:body callable) rescue)
                               (eval-body-async new-ctx (:body callable))))
                  (fn [_]
                    (let [updated-fields (reduce (fn [m field]
                                                  (let [field-name (:name field)
                                                        field-key (keyword field-name)
                                                        val (try
                                                              (env-lookup method-env field-name)
                                                              (catch :default _ ::not-found))]
                                                    (if (not= val ::not-found)
                                                      (assoc m field-key val)
                                                      m)))
                                                (:fields current-obj)
                                                all-fields)
                          updated-obj (make-object (:class-name current-obj) updated-fields (:closure-env current-obj))
                          result (async-result-value method-env)]
                      (when-let [tgt (:current-target ctx)]
                        (try
                          (env-set! (:current-env ctx) tgt updated-obj)
                          (catch :default _)))
                      (doseq [[field-name field-val] (:fields updated-obj)]
                        (when (contains? parent-field-names (name field-name))
                          (try
                            (env-set! (:current-env ctx) (name field-name) field-val)
                            (catch :default _))))
                      result))))
         (js/Promise.reject
          (ex-info (str "Method not found in parent " parent-class-name ": " method)
                   {:parent parent-class-name :method method}))))))

(defn eval-node-async [ctx node]
  #?(:cljs
     (cond
       (nil? node) (js/Promise.resolve nil)
       (string? node) (js/Promise.resolve node)
       (not (map? node)) (js/Promise.resolve node)
       (async-free-node? node) (js/Promise.resolve (eval-node ctx node))
       (= :spawn (:type node))
       (js/Promise.resolve
        (let [spawn-promise
              (.then
               (js/Promise.resolve nil)
               (fn [_]
                 (let [spawn-env (make-env (:current-env ctx))
                       _ (env-define spawn-env "result" nil)
                       spawn-ctx (assoc ctx :current-env spawn-env)]
                   (.then
                    (->promise (eval-body-async spawn-ctx (:body node)))
                    (fn [_]
                      (async-result-value spawn-env))))))]
          (make-task spawn-promise)))
       :else
       (let [node-type (:type node)]
         (cond
         (= node-type :program)
         (let [{:keys [imports interns classes functions statements calls]} node]
           (doseq [import-node imports]
             (when (map? import-node)
               (swap! (:imports ctx) conj import-node)))
           (doseq [intern-node interns]
             (when (map? intern-node)
               (process-intern ctx intern-node)))
           (doseq [class-node classes]
             (when (map? class-node)
               (register-class ctx class-node)))
           (.then (promise-reduce functions nil
                                  (fn [_ fn-node]
                                    (when (map? fn-node)
                                      (eval-node-async ctx fn-node))))
                  (fn [_]
                    (.then (promise-reduce (if (seq statements) statements calls) nil
                                           (fn [_ stmt-node]
                                             (when (map? stmt-node)
                                               (eval-node-async ctx stmt-node))))
                           (fn [_] ctx)))))

         (= node-type :class)
         (js/Promise.resolve (do (register-class ctx node) nil))

         (= node-type :function)
         (let [{:keys [name class-def class-name]} node]
           (register-class ctx class-def)
           (let [obj (make-object class-name {})]
             (env-define (:current-env ctx) name obj)
             (js/Promise.resolve obj)))

         (= node-type :anonymous-function)
         (let [{:keys [class-def class-name]} node]
           (register-class ctx class-def)
           (js/Promise.resolve (make-object class-name {} (:current-env ctx))))

         (= node-type :call)
         (let [{:keys [target method args has-parens]} node]
           (if (and (map? target) (= :create (:type target)) (nil? method))
             (eval-node-async ctx (assoc target :args args))
             (.then (promise-all (map #(eval-node-async ctx %) args))
                    (fn [arg-values]
                      (if target
                        (let [target-name (when (string? target) target)
                              class-target (when target-name (lookup-class-if-exists ctx target-name))
                              parent-class (when (and target-name (:current-object ctx))
                                             (let [cls (lookup-class-if-exists ctx target-name)]
                                               (when (and cls
                                                          (is-parent? ctx (:class-name (:current-object ctx)) target-name))
                                                 cls)))]
                          (.then (if (or parent-class class-target target-name)
                                   (js/Promise.resolve nil)
                                   (eval-node-async ctx target))
                                 (fn [target-value]
                                   (let [obj (when-not parent-class
                                               (if class-target
                                                 nil
                                                 (if target-name
                                                   (env-lookup (:current-env ctx) target-name)
                                                   target-value)))]
                                     (cond
                                       (and class-target
                                            (false? has-parens)
                                            (lookup-class-constant ctx class-target method))
                                       (js/Promise.resolve (eval-class-constant ctx class-target method))

                                       parent-class
                                       (dispatch-parent-call-async ctx (:current-object ctx) target-name method arg-values)

                                       (nex-object? obj)
          (let [class-def (lookup-class ctx (:class-name obj))
                method-lookup (lookup-method-with-inheritance ctx
                                                              class-def
                                                              method
                                                              (count arg-values)
                                                              (:current-class-name ctx))]
            (if method-lookup
                                           (let [method-def (:method method-lookup)
                                                 params (:params method-def)]
                                             (ensure-callable-defined! method-def)
                                             (when (and (false? has-parens) (seq params))
                                               (throw (ex-info (str method " requires arguments")
                                                               {:method method :params (mapv :name params)})))
                                             (let [all-fields (get-all-fields ctx class-def)
                                                   effective-require (:effective-require method-lookup)
                                                   effective-ensure (:effective-ensure method-lookup)
                                                   has-postconditions? (seq effective-ensure)
                                                   old-values (when has-postconditions? (:fields obj))
                                                   source-obj (or (-> obj meta write-back-source-key) obj)
                                                   method-env (make-env (or (:closure-env obj) (:current-env ctx)))
                                                   param-names (set (map :name params))
                                                   _ (doseq [[field-name field-val] (:fields obj)]
                                                       (env-define method-env (name field-name) field-val))
                                                   _ (bind-class-constants! ctx method-env class-def)
                                                   _ (doseq [[param arg-val] (map vector params arg-values)]
                                                       (env-define method-env (:name param) arg-val))
                                                   modified-fields (atom #{})
                                                   return-type (:return-type method-def)
                                                   default-result (if return-type
                                                                    (get-default-field-value return-type)
                                                                    nil)
                                                   _ (env-define method-env "result" default-result)
                                                   _ (env-define method-env "this" obj)
                                                   new-ctx (-> ctx
                                                               (assoc :current-env method-env)
                                                               (assoc :current-object obj)
                                                               (assoc :current-target target-name)
                                                               (assoc :current-class-name (:name source-class))
                                                               (assoc :current-method-name method)
                                                               (assoc :old-values old-values)
                                                               (assoc :modified-fields modified-fields)
                                                               (update :debug-stack (fnil conj [])
                                                                       {:class (:name source-class)
                                                                        :method method
                                                                        :env method-env
                                                                        :arg-names (set (map :name (or params [])))
                                                                        :field-names (set (map name (keys (:fields obj))))
                                                                        :source (:debug-source ctx)})
                                                               (assoc :debug-depth (inc (or (:debug-depth ctx) 0))))]
                                               (.then (->promise (if effective-require
                                                                   (check-assertions-async new-ctx effective-require Precondition)
                                                                   nil))
                                                      (fn [_]
                                                        (.then (->promise (if-let [rescue (:rescue method-def)]
                                                                            (eval-body-with-rescue-async new-ctx (:body method-def) rescue)
                                                                            (eval-body-async new-ctx (:body method-def))))
                                                               (fn [_]
                                                                 (let [updated-fields (reduce (fn [m field]
                                                                                               (let [field-name (:name field)
                                                                                                     field-key (keyword field-name)]
                                                                                                 (if (and (contains? param-names field-name)
                                                                                                          (not (contains? @modified-fields field-name)))
                                                                                                   m
                                                                                                   (let [val (try
                                                                                                               (env-lookup method-env field-name)
                                                                                                               (catch :default _ ::not-found))]
                                                                                                     (if (not= val ::not-found)
                                                                                                       (assoc m field-key val)
                                                                                                       m)))))
                                                                                             (:fields obj)
                                                                                             all-fields)
                                                                       updated-obj (make-object (:class-name obj) updated-fields (:closure-env obj))
                                                                       result (async-result-value method-env)]
                                                                   (.then
                                                                    (->promise
                                                                    (if effective-ensure
                                                                       (check-assertions-async new-ctx effective-ensure Postcondition)
                                                                       nil))
                                                                    (fn [_]
                                                                      (.then (check-class-invariant-async new-ctx class-def)
                                                                             (fn [_]
                                                                               (write-back-target! ctx target updated-obj source-obj)
                                                                               (annotate-reference-result target obj result)))))))))))
              (let [field (lookup-field-with-inheritance ctx class-def method (:current-class-name ctx))]
                (if field
                                               (let [field-val (get (:fields obj) (keyword method))]
                                               (if (and has-parens (nex-object? field-val))
                                                  (let [call-method (str "call" (count arg-values))
                                                        literal-args (mapv (fn [v] {:type :literal :value v}) arg-values)]
                                                     (eval-node-async ctx {:type :call
                                                                           :target {:type :literal :value field-val}
                                                                           :method call-method
                                                                           :args literal-args}))
                                                  (if (empty? arg-values)
                                                    (js/Promise.resolve field-val)
                                                    (js/Promise.reject (ex-info (str "Method not found: " method)
                                                                                {:object obj :method method})))))
                                               (if (get-in builtin-type-methods [:Any method])
                                                 (->promise (call-builtin-method ctx (or target-name target) obj method arg-values))
                                                 (js/Promise.reject (ex-info (str "Method not found: " method)
                                                                             {:object obj :method method})))))))))

                                       (get-type-name obj)
                                       (->promise (call-builtin-method ctx (or target-name target) obj method arg-values))

                                       :else
                                       (js/Promise.reject
                                        (ex-info (str "Method not found on type: " method)
                                                 {:target target :value obj :method method})))))))
                        (let [fn-obj (try
                                       (env-lookup (:current-env ctx) method)
                                       (catch :default _ ::not-found))]
                          (cond
                            (not= fn-obj ::not-found)
                            (if (nex-object? fn-obj)
                              (if (not= has-parens false)
                                (eval-node-async ctx {:type :call
                                                      :target method
                                                      :method (str "call" (count args))
                                                      :args args})
                                (js/Promise.resolve fn-obj))
                              (if (false? has-parens)
                                (js/Promise.resolve fn-obj)
                                (js/Promise.reject (ex-info (str "Undefined function: " method)
                                                            {:function method}))))

                            (:current-object ctx)
                            (let [current-obj (:current-object ctx)
                                  class-def (lookup-class ctx (:class-name current-obj))
                                  method-lookup (lookup-method-with-inheritance ctx class-def method (count args))]
                              (if method-lookup
                                (let [all-fields (get-all-fields ctx class-def)
                                      current-env (:current-env ctx)
                                      updated-fields (reduce (fn [m field]
                                                               (let [field-name (:name field)
                                                                     field-key (keyword field-name)
                                                                     val (try
                                                                           (env-lookup current-env field-name)
                                                                           (catch :default _ ::not-found))]
                                                                 (if (not= val ::not-found)
                                                                   (assoc m field-key val)
                                                                   m)))
                                                             (:fields current-obj)
                                                             all-fields)
                                      updated-obj (make-object (:class-name current-obj) updated-fields (:closure-env current-obj))
                                      _ (when-let [target-name (:current-target ctx)]
                                          (env-set! (-> ctx :current-env :parent) target-name updated-obj))]
                                  (.then (->promise (eval-node-async ctx {:type :call
                                                                          :target (:current-target ctx)
                                                                          :method method
                                                                          :args args}))
                                         (fn [result]
                                           (when-let [target-name (:current-target ctx)]
                                             (let [called-obj (env-lookup (-> ctx :current-env :parent) target-name)]
                                               (when called-obj
                                                 (doseq [[field-name field-val] (:fields called-obj)]
                                                   (env-set! current-env (name field-name) field-val)))))
                                           result)))
                                (if-let [builtin (get builtins method)]
                                  (->promise (apply builtin ctx arg-values))
                                  (js/Promise.reject (ex-info (str "Undefined method: " method)
                                                              {:function method :object current-obj})))))

                            :else
                            (if-let [builtin (get builtins method)]
                              (->promise (apply builtin ctx arg-values))
                              (js/Promise.reject (ex-info (str "Undefined function: " method)
                                                          {:function method}))))))))))

         (= node-type :this)
         (js/Promise.resolve (:current-object ctx))

         (= node-type :member-assign)
         (.then (->promise (eval-node-async ctx (or (:object node) {:type :this})))
                (fn [target-obj]
                  (when-not (nex-object? target-obj)
                    (throw (ex-info "Field assignment target must be an object"
                                    {:target (or (:object node) {:type :this})
                                     :value target-obj})))
                  (.then (->promise (eval-node-async ctx (:value node)))
                         (fn [val]
                           (if (and (or (= (:object-type node) :this)
                                        (= :this (:type (:object node))))
                                    (:current-object ctx))
                             (do
                               (when-let [mf (:modified-fields ctx)]
                                 (swap! mf conj (:field node)))
                               (env-set! (:current-env ctx) (:field node) val)
                               val)
                             (let [updated-obj (make-object (:class-name target-obj)
                                                            (assoc (:fields target-obj) (keyword (:field node)) val)
                                                            (:closure-env target-obj))
                                   write-back-target (let [target-expr (or (:object node) {:type :this})]
                                                       (if (= :identifier (:type target-expr))
                                                         (:name target-expr)
                                                         target-expr))]
                               (when-not (write-back-target! ctx write-back-target updated-obj target-obj)
                                 (throw (ex-info "Field assignment target is not writable"
                                                 {:target (or (:object node) {:type :this})
                                                  :field (:field node)})))
                               val))))))

         (= node-type :assign)
         (do
          (.then (->promise (eval-node-async ctx (:value node)))
                 (fn [val]
                   (env-set! (:current-env ctx) (:target node) val)
                   (when (= "result" (:target node))
                     (env-define (:current-env ctx) "__result_assigned__" "result"))
                   val)))

         (= node-type :let)
         (.then (->promise (eval-node-async ctx (:value node)))
                (fn [val]
                  (env-define (:current-env ctx) (:name node) val)
                  (when (= "result" (:name node))
                    (env-define (:current-env ctx) "__result_assigned__" "result"))
                  val))

         (= node-type :block)
         (eval-body-async ctx node)

         (= node-type :raise)
         (.then (->promise (eval-node-async ctx (:value node)))
                (fn [val]
                  (throw (ex-info (str val) {:type :nex-exception :value val}))))

         (= node-type :retry)
         (js/Promise.reject (ex-info "retry" {:type :nex-retry}))

         (= node-type :scoped-block)
         (let [new-env (make-env (:current-env ctx))
               new-ctx (assoc ctx :current-env new-env)]
           (if-let [rescue (:rescue node)]
             (eval-body-with-rescue-async new-ctx (:body node) rescue)
             (eval-body-async new-ctx (:body node))))

         (= node-type :when)
         (.then (->promise (eval-node-async ctx (:condition node)))
                (fn [cond-val]
                  (if cond-val
                    (->promise (eval-node-async ctx (:consequent node)))
                    (->promise (eval-node-async ctx (:alternative node))))))

         (= node-type :convert)
         (.then (->promise (eval-node-async ctx (:value node)))
                (fn [v]
                  (let [target-name (if (map? (:target-type node)) (:base-type (:target-type node)) (:target-type node))
                        runtime-name (runtime-type-name v)
                        ok? (and (some? v)
                                 (string? target-name)
                                 (convert-compatible-runtime? ctx runtime-name target-name))]
                    (env-define (:current-env ctx) (:var-name node) (if ok? v nil))
                    ok?)))

         (= node-type :if)
         (.then (->promise (eval-node-async ctx (:condition node)))
                (fn [cond-val]
                  (if cond-val
                    (->promise (eval-body-async ctx (:then node)))
                    (letfn [(eval-elseif [clauses]
                              (if (empty? clauses)
                                (if (:else node)
                                  (->promise (eval-body-async ctx (:else node)))
                                  (js/Promise.resolve nil))
                                (.then (->promise (eval-node-async ctx (:condition (first clauses))))
                                       (fn [matched?]
                                         (if matched?
                                           (->promise (eval-body-async ctx (:then (first clauses))))
                                           (eval-elseif (rest clauses)))))))]
                      (eval-elseif (:elseif node))))))

         (= node-type :case)
         (.then (->promise (eval-node-async ctx (:expr node)))
                (fn [val]
                  (letfn [(match-clauses [clauses]
                            (if (empty? clauses)
                              (if-let [else-node (:else node)]
                                (->promise (eval-node-async ctx else-node))
                                (js/Promise.reject (ex-info "No matching case and no else clause"
                                                            {:value val})))
                              (.then (promise-all (map #(eval-node-async ctx %) (:values (first clauses))))
                                     (fn [values]
                                       (if (some #(= val %) values)
                                         (->promise (eval-node-async ctx (:body (first clauses))))
                                         (match-clauses (rest clauses)))))))]
                    (match-clauses (:clauses node)))))

         (= node-type :select)
         (.then (promise-all (map #(prepare-select-clause-async ctx %) (:clauses node)))
                (fn [prepared]
                  (let [timeout-ms-p (if-let [timeout-node (:timeout node)]
                                       (.then (->promise (eval-node-async ctx (:duration timeout-node)))
                                              (fn [v] (timeout-ms v)))
                                       (js/Promise.resolve nil))]
                    (.then timeout-ms-p
                           (fn [timeout-ms-val]
                             (let [deadline (when timeout-ms-val (+ (.now js/Date) timeout-ms-val))]
                               (letfn [(attempt-loop []
                                         (.then
                                          (promise-all (map attempt-select-clause-async prepared))
                                          (fn [outcomes]
                                            (if-let [idx (first (keep-indexed (fn [i outcome]
                                                                                (when (:selected? outcome) i))
                                                                              outcomes))]
                                              (let [clause (nth prepared idx)
                                                    outcome (nth outcomes idx)]
                                                (execute-select-body-async ctx (:body clause) (:alias clause) (:value outcome)))
                                              (if-let [else-body (:else node)]
                                                (eval-body-async ctx else-body)
                                                (if (and deadline (>= (.now js/Date) deadline))
                                                  (eval-body-async ctx (get-in node [:timeout :body]))
                                                  (.then (sleep-select-step-async)
                                                         (fn [_]
                                                           (attempt-loop)))))))))]
                                 (attempt-loop)))))))

         (= node-type :loop)
         (let [loop-env (make-env (:current-env ctx))
               loop-ctx (assoc ctx :current-env loop-env)]
           (.then (promise-reduce (:init node) nil
                                  (fn [_ stmt] (eval-node-async loop-ctx stmt)))
                  (fn [_]
                    (letfn [(step [last-result prev-variant iteration]
                              (.then (if (and (pos? iteration) (zero? (mod iteration 25)))
                                       (yield-browser-async)
                                       (js/Promise.resolve nil))
                                     (fn [_]
                                       (.then (->promise (when-let [invariant (:invariant node)]
                                                           (check-assertions-async loop-ctx invariant Loop-invariant)))
                                              (fn [_]
                                                (.then (->promise (eval-node-async loop-ctx (:until node)))
                                                       (fn [until-val]
                                                         (if until-val
                                                           last-result
                                                           (.then (->promise (when-let [variant (:variant node)]
                                                                               (eval-node-async loop-ctx variant)))
                                                                  (fn [curr-variant]
                                                                    (when (and (:variant node) prev-variant)
                                                                      (when-not (< curr-variant prev-variant)
                                                                        (throw (ex-info "Loop variant must decrease"
                                                                                        {:iteration iteration
                                                                                         :previous-variant prev-variant
                                                                                         :current-variant curr-variant}))))
                                                                    (let [body-env (make-env (:current-env loop-ctx))
                                                                          body-ctx (assoc loop-ctx :current-env body-env)]
                                                                      (.then (eval-body-async body-ctx (:body node))
                                                                             (fn [result]
                                                                               (.then (->promise (when-let [invariant (:invariant node)]
                                                                                                   (check-assertions-async loop-ctx invariant Loop-invariant)))
                                                                                      (fn [_]
                                                                                        (step result curr-variant (inc iteration)))))))))))))))))]
                      (step nil nil 0)))))

         (= node-type :statement)
         (eval-node-async ctx (:node node))

         (= node-type :binary)
         (.then (promise-all [(eval-node-async ctx (:left node))
                              (eval-node-async ctx (:right node))])
                (fn [[left-val right-val]]
                  (if (and (= (:operator node) "+")
                           (or (string? left-val) (string? right-val)))
                    (.then (promise-all [(concat-string-value-async ctx left-val)
                                         (concat-string-value-async ctx right-val)])
                           (fn [[left-str right-str]]
                             (str left-str right-str)))
                    (apply-binary-op (:operator node) left-val right-val))))

         (= node-type :unary)
         (.then (->promise (eval-node-async ctx (:expr node)))
                (fn [val]
                  (apply-unary-op (:operator node) val)))

         (= node-type :integer) (js/Promise.resolve (:value node))
         (= node-type :real) (js/Promise.resolve (:value node))
         (= node-type :boolean) (js/Promise.resolve (:value node))
         (= node-type :char) (js/Promise.resolve (:value node))
         (= node-type :string) (js/Promise.resolve (:value node))
         (= node-type :nil) (js/Promise.resolve nil)

         (= node-type :array-literal)
         (.then (promise-all (map #(eval-node-async ctx %) (:elements node)))
                nex-array-from)

         (= node-type :set-literal)
         (.then (promise-all (map #(eval-node-async ctx %) (:elements node)))
                nex-set-from)

         (= node-type :map-literal)
         (.then (promise-all (map (fn [{:keys [key value]}]
                                    (promise-all [(eval-node-async ctx key)
                                                  (eval-node-async ctx value)]))
                                  (:entries node)))
                nex-map-from)

         (= node-type :identifier)
         (js/Promise.resolve (eval-node ctx node))

         (= node-type :create)
         (let [{:keys [class-name generic-args constructor args]} node]
           (.then (promise-all (map #(eval-node-async ctx %) args))
                  (fn [arg-values]
                   (case class-name
                      "Console" {:nex-builtin-type :Console}
                      "Process" {:nex-builtin-type :Process}
                      "Array" (cond
                                (nil? constructor) (nex-array)
                                (= constructor "filled")
                                (let [[size value] arg-values]
                                  (when-not (integer? size)
                                    (throw (ex-info "Array.filled requires an Integer size"
                                                    {:class-name "Array" :constructor constructor})))
                                  (when (neg? size)
                                    (throw (ex-info "Array size must be non-negative"
                                                    {:class-name "Array" :constructor constructor})))
                                  (nex-array-from (vec (repeat size value))))
                                :else
                                (throw (ex-info (str "Constructor not found: Array." constructor)
                                                {:class-name "Array" :constructor constructor})))
                      "Map" (nex-map)
                      "Min_Heap" (cond
                                   (or (nil? constructor) (= constructor "empty"))
                                   (do
                                     (when (seq arg-values)
                                       (throw (ex-info "Min_Heap.empty expects no arguments"
                                                       {:class-name "Min_Heap" :constructor constructor})))
                                     (make-min-heap nil))
                                   (= constructor "from_comparator")
                                   (do
                                     (when-not (= 1 (count arg-values))
                                       (throw (ex-info "Min_Heap.from_comparator expects 1 argument"
                                                       {:class-name "Min_Heap" :constructor constructor})))
                                     (make-min-heap (first arg-values)))
                                   :else
                                   (throw (ex-info (str "Constructor not found: Min_Heap." constructor)
                                                   {:class-name "Min_Heap" :constructor constructor})))
                      "Atomic_Integer" (do
                                         (when-not (= constructor "make")
                                           (throw (ex-info (str "Constructor not found: Atomic_Integer." constructor)
                                                           {:class-name "Atomic_Integer" :constructor constructor})))
                                         (when-not (= 1 (count arg-values))
                                           (throw (ex-info "Atomic_Integer.make expects 1 argument"
                                                           {:class-name "Atomic_Integer" :constructor constructor})))
                                         (make-atomic-integer (first arg-values)))
                      "Atomic_Integer64" (do
                                           (when-not (= constructor "make")
                                             (throw (ex-info (str "Constructor not found: Atomic_Integer64." constructor)
                                                             {:class-name "Atomic_Integer64" :constructor constructor})))
                                           (when-not (= 1 (count arg-values))
                                             (throw (ex-info "Atomic_Integer64.make expects 1 argument"
                                                             {:class-name "Atomic_Integer64" :constructor constructor})))
                                           (make-atomic-integer64 (first arg-values)))
                      "Atomic_Boolean" (do
                                         (when-not (= constructor "make")
                                           (throw (ex-info (str "Constructor not found: Atomic_Boolean." constructor)
                                                           {:class-name "Atomic_Boolean" :constructor constructor})))
                                         (when-not (= 1 (count arg-values))
                                           (throw (ex-info "Atomic_Boolean.make expects 1 argument"
                                                           {:class-name "Atomic_Boolean" :constructor constructor})))
                                         (make-atomic-boolean (first arg-values)))
                      "Atomic_Reference" (do
                                           (when-not (= constructor "make")
                                             (throw (ex-info (str "Constructor not found: Atomic_Reference." constructor)
                                                             {:class-name "Atomic_Reference" :constructor constructor})))
                                           (when-not (= 1 (count arg-values))
                                             (throw (ex-info "Atomic_Reference.make expects 1 argument"
                                                             {:class-name "Atomic_Reference" :constructor constructor})))
                                           (make-atomic-reference (first arg-values)))
                      "Channel" (cond
                                  (nil? constructor) (make-channel)
                                  (= constructor "with_capacity")
                                  (let [capacity (first arg-values)]
                                    (when-not (integer? capacity)
                                      (throw (ex-info "Channel.with_capacity requires an Integer capacity"
                                                      {:class-name "Channel" :constructor constructor})))
                                    (when (neg? capacity)
                                      (throw (ex-info "Channel capacity must be non-negative"
                                                      {:class-name "Channel" :constructor constructor})))
                                    (make-channel capacity))
                                  :else
                                  (throw (ex-info (str "Constructor not found: Channel." constructor)
                                                  {:class-name "Channel" :constructor constructor})))
                      "Set" (cond
                              (nil? constructor) (nex-set)
                              (= constructor "from_array") (let [source (first arg-values)]
                                                             (cond
                                                               (nex-array? source) (nex-set-from (array-seq source))
                                                               (sequential? source) (nex-set-from source)
                                                               :else (throw (ex-info "Set.from_array requires an array"
                                                                                     {:class-name "Set"}))))
                              :else (throw (ex-info (str "Constructor not found: Set." constructor)
                                                    {:class-name "Set" :constructor constructor})))
                      (let [effective-class-name
                            (if (seq generic-args)
                              (let [spec-name (specialized-class-name class-name generic-args)]
                                (if (lookup-specialized-class ctx spec-name)
                                  spec-name
                                  (let [template (get @(:classes ctx) class-name)]
                                    (when-not template
                                      (throw (ex-info (str "Undefined template class: " class-name)
                                                      {:class-name class-name})))
                                    (let [specialized (specialize-class template generic-args)]
                                      (register-specialized-class ctx specialized)
                                      spec-name))))
                              class-name)
                            class-def (lookup-class-if-exists ctx effective-class-name)]
                        (when-not class-def
                          (throw (ex-info (str "Undefined class: " class-name)
                                          {:class-name class-name})))
                        (when (:deferred? class-def)
                          (throw (ex-info (str "Cannot instantiate deferred class: " class-name)
                                          {:class-name class-name :deferred? true})))
                        (let [all-fields (get-all-fields ctx class-def)
                              initial-field-map (reduce (fn [m field]
                                                          (assoc m (keyword (:name field))
                                                                 (get-default-field-value (:field-type field))))
                                                        {}
                                                        all-fields)
                              finalize-object
                              (fn [field-map]
                                (let [obj (make-object effective-class-name field-map)
                                      inv-env (make-env (:current-env ctx))
                                      _ (doseq [[field-name field-val] field-map]
                                          (env-define inv-env (name field-name) field-val))
                                      inv-ctx (assoc ctx :current-env inv-env)]
                                  (.then (check-class-invariant-async inv-ctx class-def)
                                         (fn [_] obj))))]
                          (if constructor
                            (let [ctor-def (lookup-constructor-with-inheritance ctx class-def constructor)]
                              (when-not ctor-def
                                (throw (ex-info (str "Constructor not found: " class-name "." constructor)
                                                {:class-name class-name :constructor constructor})))
                              (let [ctor-env (make-env (:current-env ctx))
                                    _ (doseq [[field-name field-val] initial-field-map]
                                        (env-define ctor-env (name field-name) field-val))
                                    _ (bind-class-constants! ctx ctor-env class-def)
                                    params (:params ctor-def)
                                    _ (doseq [[param arg-val] (map vector params arg-values)]
                                        (env-define ctor-env (:name param) arg-val))
                                    temp-obj (make-object effective-class-name initial-field-map)
                                    source-class-name (:name (:source-class ctor-def))
                                    new-ctx (-> ctx
                                                (assoc :current-env ctor-env)
                                                (assoc :current-object temp-obj)
                                                (assoc :current-class-name source-class-name)
                                                (assoc :current-method-name constructor)
                                                (update :debug-stack (fnil conj [])
                                                        {:class source-class-name
                                                         :method (or constructor "make")
                                                         :env ctor-env
                                                         :arg-names (set (map :name (or params [])))
                                                         :field-names (set (map name (keys initial-field-map)))
                                                         :source (:debug-source ctx)})
                                                (assoc :debug-depth (inc (or (:debug-depth ctx) 0))))]
                                (.then (->promise (when-let [require-assertions (:require ctor-def)]
                                                    (check-assertions-async new-ctx require-assertions Precondition)))
                                       (fn [_]
                                         (.then (->promise (if-let [rescue (:rescue ctor-def)]
                                                             (eval-body-with-rescue-async new-ctx (:body ctor-def) rescue)
                                                             (eval-body-async new-ctx (:body ctor-def))))
                                                (fn [_]
                                                  (let [updated-fields (reduce (fn [m field]
                                                                                 (let [field-name (:name field)
                                                                                       field-key (keyword field-name)
                                                                                       val (try
                                                                                             (env-lookup ctor-env field-name)
                                                                                             (catch :default _ ::not-found))]
                                                                                   (if (not= val ::not-found)
                                                                                     (assoc m field-key val)
                                                                                     m)))
                                                                               initial-field-map
                                                                               all-fields)]
                                                    (.then (->promise (when-let [ensure-assertions (:ensure ctor-def)]
                                                                        (check-assertions-async new-ctx ensure-assertions Postcondition)))
                                                           (fn [_]
                                                             (finalize-object updated-fields)))))))))
                            (finalize-object initial-field-map)))))))))

         (= node-type :literal)
         (js/Promise.resolve (if (map? node) (:value node) node))

         (= node-type :old)
         (js/Promise.resolve (eval-node ctx node))

         (= node-type :with)
         (js/Promise.resolve nil)

         (= node-type :default)
         (js/Promise.resolve (eval-node ctx node))

         :else
         (js/Promise.reject
          (ex-info (str "Cannot evaluate node type: " (:type node))
                   {:node node})))))))

;;
;; Public API
;;

(defn interpret
  "Interpret an AST and return the context with results."
  [ast]
  (let [ctx (make-context)]
    (eval-node ctx ast)
    ctx))

(defn interpret-and-get-output
  "Interpret an AST and return the output as a vector of strings."
  [ast]
  (let [ctx (interpret ast)]
    @(:output ctx)))

(defn run
  "Convenience function to interpret and print output."
  [ast]
  (let [output (interpret-and-get-output ast)]
    (doseq [line output]
      (println line))
    output))
