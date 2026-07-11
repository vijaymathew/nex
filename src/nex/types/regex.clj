(ns nex.types.regex
  (:require [clojure.string :as str]))

(defn regex-compile [pattern flags]
     (let [embedded (str (when (str/includes? flags "i") "(?i)")
                         (when (str/includes? flags "m") "(?m)")
                         pattern)]
       (re-pattern embedded)))

(defn regex-validate [pattern flags]
     (regex-compile (str pattern) (str flags))
     true)

(defn regex-matches? [pattern flags text]
     (boolean (re-matches (regex-compile (str pattern) (str flags)) (str text))))

(defn regex-find [pattern flags text]
     (let [m (re-find (regex-compile (str pattern) (str flags)) (str text))]
       (cond
         (string? m) m
         (vector? m) (first m)
         :else nil)))

(defn regex-find-all [pattern flags text]
     (let [matches (re-seq (regex-compile (str pattern) (str flags)) (str text))
           normalized (map (fn [m] (if (vector? m) (first m) m)) matches)]
       (java.util.ArrayList. normalized)))

(defn regex-replace [pattern flags text replacement]
     (str/replace (str text) (regex-compile (str pattern) (str flags)) (str replacement)))

(defn regex-split [pattern flags text]
     (java.util.ArrayList. (vec (str/split (str text) (regex-compile (str pattern) (str flags))))))
