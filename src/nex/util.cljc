(ns nex.util
  #?(:clj
     (:require [clojure.string :as str]))
  #?(:clj (:import [java.io File])))

(defn relative-path?
  [^String path]
  #?(:clj
     (when (and path (not (str/blank? path)))
       (not (.isAbsolute (File. path))))
     :cljs false))
