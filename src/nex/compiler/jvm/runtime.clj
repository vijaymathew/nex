(ns nex.compiler.jvm.runtime
  "Small runtime support for the future JVM bytecode compiler."
  (:require [clojure.string :as str])
  (:import [clojure.lang DynamicClassLoader]
           [java.lang.reflect Method]
           [java.util HashMap]))

(defrecord NexReplState [^clojure.lang.Atom values
                         ^clojure.lang.Atom types
                         ^clojure.lang.Atom functions
                         ^clojure.lang.Atom counter
                         ^DynamicClassLoader loader])

(defn make-repl-state
  ([] (make-repl-state nil))
  ([loader]
   (->NexReplState (atom (HashMap.))
                   (atom (HashMap.))
                   (atom (HashMap.))
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
