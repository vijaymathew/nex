(ns nex.types.json
  (:require #?(:clj [clojure.data.json :as json])
            [nex.types.runtime :as rt]))

(defn json-value->nex
  [value]
  (cond
    (nil? value) nil
    (or (string? value) (boolean? value) (char? value)) value
    (integer? value)
    (let [n (long value)]
      (if #?(:clj (<= Integer/MIN_VALUE n Integer/MAX_VALUE)
             :cljs true)
        (int n)
        n))
    (number? value) (double value)
    (vector? value) (rt/nex-array-from (mapv json-value->nex value))
    (sequential? value) (rt/nex-array-from (mapv json-value->nex value))
    (map? value)
    (let [m (rt/nex-map)]
      (doseq [[k v] value]
        (rt/nex-map-put m (str k) (json-value->nex v)))
      m)
    :else value))

(defn nex-value->json
  [value]
  (cond
    (nil? value) nil
    (or (string? value) (boolean? value) (number? value)) value
    (char? value) (str value)
    (rt/nex-array? value) (mapv nex-value->json value)
    (rt/nex-map? value)
    (into {}
          (map (fn [[k v]] [(str k) (nex-value->json v)]))
          value)
    (rt/nex-set? value) (mapv nex-value->json value)
    :else (throw (ex-info (str "Value is not JSON-serializable: " (pr-str (type value)))
                          {:value value}))))

#?(:clj
   (defn nex-json-parse
     [text]
     (json-value->nex (json/read-str (str text)))))

#?(:clj
   (defn nex-json-stringify
     [value]
     (json/write-str (nex-value->json value))))
