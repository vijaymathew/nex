(ns nex.http-implied-intern-test
  "A program that calls the http builtins gets the library classes those
   builtins hand it (Http_Response; Http_Request and Http_Server_Response), as
   if it had interned net/Http_Client or net/Http_Server itself
   (nex.walker/add-implied-interns). Without them the classes did not exist in
   the program: `response.status()` failed to compile, and the runtime had to
   build a bare object map instead."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
            [nex.parser :as p]))

(defn- implied-interns [source]
  (->> (:interns (p/ast source))
       (filter :implied)
       (mapv (juxt :path :class-name))))

(deftest builtins-imply-their-library-test
  (testing "the client builtins imply net/Http_Client, the server builtins net/Http_Server"
    (is (= [["net" "Http_Client"]]
           (implied-interns "let r := http_get(\"http://localhost\")\nprint(r.status())")))
    (is (= [["net" "Http_Client"]]
           (implied-interns "function f(): Integer do result := http_post(\"u\", \"b\").status() end")))
    (is (= [["net" "Http_Server"]]
           (implied-interns "let h := http_server_create(0)\nprint(http_server_start(h))"))))
  (testing "no implied intern when the program already has the library or the call is not the builtin"
    (is (= [] (implied-interns "intern net/Http_Client\nprint(http_get(\"u\").status())")))
    (is (= [] (implied-interns (slurp "lib/net/http_client.nex"))))
    (is (= [] (implied-interns (slurp "lib/net/http_server.nex"))))
    (is (= [] (implied-interns "function http_get(u: String): Integer do result := 1 end\nprint(http_get(\"u\"))")))
    (is (= [] (implied-interns "print(1)")))))

(defn- run [code opts]
  (let [f (java.io.File/createTempFile "http_implied" ".nex")]
    (try
      (spit f code)
      (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) opts))))
      (finally (.delete f)))))

(def ^:private server-and-client
  "let h := http_server_create(0)
http_server_get(h, \"/hello\", fn (req: Http_Request): Http_Server_Response do
  result := create Http_Server_Response.text(\"hi \" + req.path())
end)
let port := http_server_start(h)
let r := http_get(\"http://127.0.0.1:\" + port.to_string + \"/hello\")
print(r.status())
print(r.body())
let missing := http_get(\"http://127.0.0.1:\" + port.to_string + \"/nope\")
print(missing.status())
http_server_stop(h)")

(deftest server-and-client-without-intern-test
  (testing "compiled: the builtins' objects are instances of the library classes"
    (is (= ["200" "\"hi /hello\"" "404"] (run server-and-client {}))))
  (testing "interpreted: the same program runs (status of an unknown route aside)"
    (is (= ["200" "\"hi /hello\""] (take 2 (run server-and-client {:interpret? true}))))))
