(ns nex.parser
  (:require [clojure.java.io :as io]
            [nex.walker :as walker]
            [clj-antlr.core :as antlr]))

(defn- packaged-grammar-path
  []
  (let [resource-name "grammar/nexlang.g4"
        local-file (io/file resource-name)]
    (cond
      (.exists local-file)
      (.getPath local-file)

      :else
      (when-let [resource (io/resource resource-name)]
        (let [tmp-dir (doto (.toFile (java.nio.file.Files/createTempDirectory
                                      "nexlang-grammar"
                                      (make-array java.nio.file.attribute.FileAttribute 0)))
                        (.deleteOnExit))
              tmp-file (doto (io/file tmp-dir "nexlang.g4")
                         (.deleteOnExit))]
          (with-open [in (io/input-stream resource)
                      out (io/output-stream tmp-file)]
            (io/copy in out))
          (.getPath tmp-file))))))

(def parser
  (antlr/parser (or (packaged-grammar-path)
                    (throw (ex-info "Could not locate grammar/nexlang.g4"
                                    {:resource "grammar/nexlang.g4"})))))

(defn parse [input]
  (antlr/parse parser input))

(defn ast [input]
  (-> input
      parse
      walker/walk-node))
