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

(defn ensure-absolute-path
  "If `path` is relative, prepend nex.user.dir system property.
   Returns an absolute path string."
  [^String path]
  #?(:clj
     (when (and path (not (str/blank? path)))
       (if (relative-path? path)
         (let [user-dir (System/getProperty "nex.user.dir")]
           (when-not (str/blank? user-dir)
             (str user-dir File/separator path)))
         (.getAbsolutePath (File. path))))
     :cljs path))
