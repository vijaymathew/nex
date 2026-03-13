(ns nex.types.http
  (:require [clojure.string :as str]
            [nex.types.runtime :as rt]))

#?(:clj
   (defn http-response-headers->nex-map
     [headers]
     (let [m (rt/nex-map)]
       (doseq [[k values] (.map ^java.net.http.HttpHeaders headers)]
         (rt/nex-map-put m k (if (seq values) (str (first values)) "")))
       m)))

#?(:clj
   (defn make-http-response-object
     [make-object-fn status body headers]
     (make-object-fn "Http_Response"
                     {"status_code" status
                      "body_text" body
                      "header_map" headers})))

#?(:clj
   (defn make-http-server-request-object
     [make-object-fn method-name path-value body-text header-map route-params query-map]
     (make-object-fn "Http_Request"
                     {"method_name" method-name
                      "path_value" path-value
                      "body_text" body-text
                      "header_map" header-map
                      "route_params" route-params
                      "query_map" query-map})))

#?(:clj
   (defn make-http-server-default-response-object
     [make-object-fn]
     (make-object-fn "Http_Server_Response"
                     {"status_code" 404
                      "body_text" "Not Found"
                      "header_map" (rt/nex-map)})))

#?(:clj
   (defn http-exchange-headers->nex-map
     [headers]
     (let [m (rt/nex-map)]
       (doseq [entry (.entrySet headers)]
         (let [k (.getKey entry)
               values (.getValue entry)]
           (rt/nex-map-put m (str k)
                           (if (seq values)
                             (str (first values))
                             ""))))
       m)))

#?(:clj
   (defn java-http-request
     [make-object-fn method url body timeout-ms]
     (let [builder (java.net.http.HttpRequest/newBuilder
                    (java.net.URI/create url))
           _ (when (some? timeout-ms)
               (.timeout builder (java.time.Duration/ofMillis (long timeout-ms))))
           publisher (if (= method "POST")
                       (java.net.http.HttpRequest$BodyPublishers/ofString (or body ""))
                       (java.net.http.HttpRequest$BodyPublishers/noBody))
           _ (if (= method "POST")
               (.POST builder publisher)
               (.GET builder))
           request (.build builder)
           client (.build (java.net.http.HttpClient/newBuilder))
           response (.send client request (java.net.http.HttpResponse$BodyHandlers/ofString))]
       (make-http-response-object make-object-fn
                                  (.statusCode response)
                                  (.body response)
                                  (http-response-headers->nex-map (.headers response))))))

#?(:clj
   (defn make-http-server-handle
     [port]
     {:nex-builtin-type :HttpServerHandle
      :port (atom port)
      :server (atom nil)
      :routes {"GET" (atom [])
               "POST" (atom [])
               "PUT" (atom [])
               "DELETE" (atom [])}}))

#?(:clj
   (defn url-decode
     [s]
     (java.net.URLDecoder/decode (str (or s "")) "UTF-8")))

#?(:clj
   (defn path-segments
     [path]
     (let [trimmed (or path "")]
       (if (or (= trimmed "") (= trimmed "/"))
         []
         (->> (str/split trimmed #"/")
              (remove str/blank?)
              vec)))))

#?(:clj
   (defn parse-query-map
     [query]
     (let [m (rt/nex-map)]
       (when (seq query)
         (doseq [part (str/split (str query) #"&")]
           (when (seq part)
             (let [[k v] (str/split part #"=" 2)]
               (rt/nex-map-put m (url-decode k) (url-decode (or v "")))))))
       m)))

#?(:clj
   (defn route-match
     [pattern path]
     (let [pattern-segments (path-segments pattern)
           path-segments* (path-segments path)
           params (rt/nex-map)]
       (loop [ps pattern-segments
              xs path-segments*]
         (cond
           (and (empty? ps) (empty? xs))
           params

           (empty? ps)
           nil

           (= (first ps) "*")
           (do
             (rt/nex-map-put params "*" (str/join "/" xs))
             params)

           (empty? xs)
           nil

           (str/starts-with? (first ps) ":")
           (do
             (rt/nex-map-put params (subs (first ps) 1) (url-decode (first xs)))
             (recur (rest ps) (rest xs)))

           (= (first ps) (first xs))
           (recur (rest ps) (rest xs))

           :else nil)))))

#?(:clj
   (defn find-route
     [handle method path]
     (some (fn [{:keys [path-pattern handler]}]
             (when-let [params (route-match path-pattern path)]
               {:handler handler :params params}))
           @(get (:routes handle) method))))

#?(:clj
   (defn http-server-response-status
     [response]
     (let [fields (:fields response)]
       (or (get fields :status_code)
           200))))

#?(:clj
   (defn http-server-response-body
     [response]
     (let [fields (:fields response)]
       (str (or (get fields :body_text) "")))))

#?(:clj
   (defn http-server-response-headers
     [response]
     (let [fields (:fields response)
           headers (or (get fields :header_map) (rt/nex-map))]
       headers)))

#?(:clj
   (defn start-http-server!
     [make-object-fn invoke-handler-fn ctx handle]
     (let [server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" (int @(:port handle))) 0)
           dispatch
           (proxy [com.sun.net.httpserver.HttpHandler] []
             (handle [exchange]
               (try
                 (let [method (.getRequestMethod exchange)
                       uri (.getRequestURI exchange)
                       path (.getPath uri)
                       query (.getRawQuery uri)
                       body (slurp (.getRequestBody exchange))
                       route (find-route handle method path)
                       request-obj (make-http-server-request-object make-object-fn method path body
                                                                    (http-exchange-headers->nex-map (.getRequestHeaders exchange))
                                                                    (or (:params route) (rt/nex-map))
                                                                    (parse-query-map query))
                       response-obj (if route
                                      (invoke-handler-fn ctx (:handler route) request-obj)
                                      (make-http-server-default-response-object make-object-fn))
                       status (int (http-server-response-status response-obj))
                       response-body (http-server-response-body response-obj)
                       response-bytes (.getBytes response-body java.nio.charset.StandardCharsets/UTF_8)
                       response-headers (http-server-response-headers response-obj)]
                   (doseq [[k v] response-headers]
                     (.add (.getResponseHeaders exchange) (str k) (str v)))
                   (.sendResponseHeaders exchange status (long (alength response-bytes)))
                   (with-open [os (.getResponseBody exchange)]
                     (.write os response-bytes))
                   nil)
                 (catch Exception ex
                   (let [message (.getMessage ex)
                         bytes (.getBytes (str "Server error: " (or message "unknown")) java.nio.charset.StandardCharsets/UTF_8)]
                     (.sendResponseHeaders exchange 500 (long (alength bytes)))
                     (with-open [os (.getResponseBody exchange)]
                       (.write os bytes)))
                   nil))))]
       (.createContext server "/" dispatch)
       (.start server)
       (reset! (:server handle) server)
       (reset! (:port handle) (.getPort (.getAddress server)))
       @(:port handle))))
