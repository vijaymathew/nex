(ns nex.http-client-lib-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.repl :as repl])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(defn- start-test-http-server []
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server
     "/hello"
     (proxy [HttpHandler] []
       (handle [^HttpExchange exchange]
         (let [method (.getRequestMethod exchange)
               body (slurp (.getRequestBody exchange))
               text (if (= method "POST")
                      (str "posted:" body)
                      "hello")
               bytes (.getBytes text StandardCharsets/UTF_8)
               headers (.getResponseHeaders exchange)]
           (.add headers "X-Nex" "ok")
           (.sendResponseHeaders exchange 200 (long (alength bytes)))
           (with-open [os (.getResponseBody exchange)]
             (.write os bytes))))))
    (.start server)
    server))

(deftest http-client-get-and-post-runtime
  (testing "Http_Client performs GET and POST requests in the JVM interpreter"
    (binding [repl/*repl-backend* (atom :interpreter)]
      (let [server (start-test-http-server)
            port (.getPort (.getAddress server))
            base-url (str "http://127.0.0.1:" port "/hello")
            ctx (repl/init-repl-context)]
        (try
          (let [output (with-out-str
                         (repl/eval-code ctx "intern net/Http_Client")
                         (repl/eval-code ctx "let client: Http_Client := create Http_Client.make()")
                         (repl/eval-code ctx (str "let get_response: Http_Response := client.get(\"" base-url "\", 500)"))
                         (repl/eval-code ctx "print(get_response.status())")
                         (repl/eval-code ctx "print(get_response.body())")
                         (repl/eval-code ctx "print(type_of(get_response.headers()))")
                         (repl/eval-code ctx (str "let post_response: Http_Response := client.post(\"" base-url "\", \"payload\", 500)"))
                         (repl/eval-code ctx "print(post_response.status())")
                         (repl/eval-code ctx "print(post_response.body())"))]
            (is (.contains output "200"))
            (is (.contains output "\"hello\""))
            (is (.contains output "\"Map\""))
            (is (.contains output "\"posted:payload\"")))
          (finally
            (.stop server 0)))))))

(defn- start-echo-http-server
  "Answers every request with \"METHOD|X-Key header|body\"."
  []
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server
     "/echo"
     (proxy [HttpHandler] []
       (handle [^HttpExchange exchange]
         (let [text (str (.getRequestMethod exchange) "|"
                         (.getFirst (.getRequestHeaders exchange) "X-Key") "|"
                         (slurp (.getRequestBody exchange)))
               bytes (.getBytes text StandardCharsets/UTF_8)]
           (.sendResponseHeaders exchange 200 (long (alength bytes)))
           (with-open [os (.getResponseBody exchange)]
             (.write os bytes))))))
    (.start server)
    server))

(deftest http-client-request-with-headers
  (doseq [backend [:interpreter :compiled]]
    (testing (str "Http_Client.request sends the method, headers and body (" (name backend) ")")
      (binding [repl/*repl-backend* (atom backend)]
        (let [server (start-echo-http-server)
              port (.getPort (.getAddress server))
              url (str "http://127.0.0.1:" port "/echo")
              ctx (repl/init-repl-context)]
          (try
            (let [output (with-out-str
                           (repl/eval-code ctx "intern net/Http_Client")
                           (repl/eval-code ctx "let client: Http_Client := create Http_Client.make()")
                           (repl/eval-code ctx (str "print(client.request(\"POST\", \"" url "\", \"payload\", {\"X-Key\": \"secret\"}).body())"))
                           (repl/eval-code ctx (str "print(client.request(\"PUT\", \"" url "\", \"more\", {\"X-Key\": \"k2\"}, 500).body())")))]
              (is (.contains output "\"POST|secret|payload\""))
              (is (.contains output "\"PUT|k2|more\"")))
            (finally
              (.stop server 0))))))))
