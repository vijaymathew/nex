#!/usr/bin/env clojure

(binding [*command-line-args* (into ["--tut"] *command-line-args*)]
  (load-file "test/scripts/check_docs_examples.clj"))
