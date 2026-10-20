(ns nex.compiler.jvm.classloader
  "Helpers around a dedicated dynamic classloader for Nex JVM compilation."
  (:require [clojure.string :as str]
            [nex.compiler.jvm.emit :as emit])
  (:import [clojure.lang DynamicClassLoader RT]))

(defn make-loader
  []
  (DynamicClassLoader. ^ClassLoader (RT/baseLoader)))

(def ^:private dispatch-interfaces-defined
  "Loader -> the set of dispatch interfaces (internal names) defined in it."
  (java.util.WeakHashMap.))

(defn- dispatch-interface-refs
  "Internal names of the dispatch interfaces (emit/dispatch-interface-name)
   that BYTECODE names, as interfaces its class implements or at its call
   sites. Read from the constant pool's UTF-8 entries, where each name is
   followed by the next entry's tag byte, never a name character."
  [^bytes bytecode]
  (into #{}
        (map first)
        (re-seq #"nex/dispatch/[FU]_([A-Za-z_][A-Za-z0-9_]*)\$(\d+)"
                (String. bytecode java.nio.charset.StandardCharsets/ISO_8859_1))))

(defn- ensure-dispatch-interface!
  "Define the dispatch interface INTERNAL-NAME in LOADER unless it already
   has. Each loader defines its own, before any class that names it: a
   DynamicClassLoader looks in Clojure's JVM-wide class cache before its
   parent, so one it had to look up could be another loader's, built against
   a different NexReplState, and fail to link."
  [^DynamicClassLoader loader ^String internal-name]
  (locking dispatch-interfaces-defined
    (let [defined (or (.get ^java.util.WeakHashMap dispatch-interfaces-defined loader) #{})]
      (when-not (contains? defined internal-name)
        (let [[_ kind method arity] (re-matches #"nex/dispatch/([FU])_(.+)\$(\d+)" internal-name)]
          (.defineClass loader (.replace internal-name \/ \.)
                        (emit/compile-dispatch-interface->bytes kind method (Long/parseLong arity))
                        nil))
        (.put ^java.util.WeakHashMap dispatch-interfaces-defined loader (conj defined internal-name))))))

(defn define-class!
  "Define a generated class in the supplied loader, after any dispatch
   interface it names that the loader does not have yet."
  [^DynamicClassLoader loader class-name ^bytes bytecode]
  (doseq [iface (dispatch-interface-refs bytecode)]
    (ensure-dispatch-interface! loader iface))
  ;; A user class implements the runtime's NexCarrier (and NexInvariant, with
  ;; an invariant), which exist only once the runtime namespace has loaded;
  ;; generated code
  ;; otherwise loads it lazily, from a class's static initialiser, which runs
  ;; after the class is defined.
  (when (str/includes? (String. bytecode java.nio.charset.StandardCharsets/ISO_8859_1)
                       "nex/compiler/jvm/runtime/Nex")
    (require 'nex.compiler.jvm.runtime))
  (.defineClass loader class-name bytecode nil))

(defn resolve-class
  [^DynamicClassLoader loader class-name]
  (try
    (.loadClass loader class-name)
    (catch ClassNotFoundException _
      nil)))
