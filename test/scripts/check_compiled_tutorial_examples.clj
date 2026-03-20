#!/usr/bin/env clojure

(binding [*command-line-args* (into ["--compiled-backend" "--tut"] *command-line-args*)]
  (load-file "test/scripts/check_docs_examples.clj"))
