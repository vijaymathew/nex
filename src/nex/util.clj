(ns nex.util
  (:require [clojure.string :as str])
  (:import [java.io File]))

(defn relative-path?
  [^String path]
  (when (and path (not (str/blank? path)))
       (not (.isAbsolute (File. path)))))
