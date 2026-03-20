(ns nex.network-lib-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.repl :as repl]))

(deftest tcp-and-server-socket-loopback-runtime
  (testing "timeout-aware connect and accept work over loopback"
    (binding [repl/*repl-backend* (atom :interpreter)]
      (let [ctx (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx "intern net/Server_Socket")
                     (repl/eval-code ctx "intern net/Tcp_Socket")
                     (repl/eval-code ctx "let server: Server_Socket := create Server_Socket.make(0)")
                     (repl/eval-code ctx "server.open()")
                     (repl/eval-code ctx "let port: Integer := server.port")
                     (repl/eval-code ctx "let accept_task: Task[?Tcp_Socket] := spawn do result := server.accept(1000) end")
                     (repl/eval-code ctx "sleep(50)")
                     (repl/eval-code ctx "let client: Tcp_Socket := create Tcp_Socket.make(\"127.0.0.1\", port)")
                     (repl/eval-code ctx "print(client.connect(500))")
                     (repl/eval-code ctx "let server_client: ?Tcp_Socket := accept_task.await(1500)")
                     (repl/eval-code ctx "print(server_client /= nil)")
                     (repl/eval-code ctx "if server_client /= nil then server_client.send_line(\"hello\") end")
                     (repl/eval-code ctx "print(client.read_line())")
                     (repl/eval-code ctx "if server_client /= nil then server_client.close() end")
                     (repl/eval-code ctx "client.close()")
                     (repl/eval-code ctx "server.close()"))]
        (is (.contains output "true"))
        (is (.contains output "\"hello\""))))))

(deftest tcp-and-server-socket-timeout-runtime
  (testing "accept(timeout) and connect(timeout) fail promptly on timeout or unavailable endpoint"
    (binding [repl/*repl-backend* (atom :interpreter)]
      (let [ctx (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx "intern net/Server_Socket")
                     (repl/eval-code ctx "intern net/Tcp_Socket")
                     (repl/eval-code ctx "let server: Server_Socket := create Server_Socket.make(0)")
                     (repl/eval-code ctx "server.open()")
                     (repl/eval-code ctx "print(server.accept(50))")
                     (repl/eval-code ctx "let closed_port: Integer := server.port")
                     (repl/eval-code ctx "server.close()")
                     (repl/eval-code ctx "let client: Tcp_Socket := create Tcp_Socket.make(\"127.0.0.1\", closed_port)")
                     (repl/eval-code ctx "print(client.connect(50))"))]
        (is (.contains output "Accept timed out"))
        (is (.contains output "Connection refused"))))))
