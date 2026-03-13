(ns nex.io-lib-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [nex.repl :as repl]))

(defn- delete-tree! [f]
  (when (.exists ^java.io.File f)
    (doseq [child (reverse (file-seq f))]
      (.delete ^java.io.File child))))

(deftest io-path-and-text-file-runtime-test
  (testing "Path and Text_File libraries work through the JVM interpreter"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-io-" (System/nanoTime)))
          ctx (repl/init-repl-context)
          root-path (.getAbsolutePath tmp-dir)]
      (.mkdirs tmp-dir)
      (try
        (let [output (with-out-str
                       (repl/eval-code ctx "intern io/Path")
                       (repl/eval-code ctx "intern io/Text_File")
                       (repl/eval-code ctx (str "let root: Path := create Path.make(\"" root-path "\")"))
                       (repl/eval-code ctx "print(root.exists())")
                       (repl/eval-code ctx "let nested: Path := root.child(\"nested\")")
                       (repl/eval-code ctx "nested.create_directories()")
                       (repl/eval-code ctx "let file: Path := nested.child(\"notes.txt\")")
                       (repl/eval-code ctx "file.create_file()")
                       (repl/eval-code ctx "file.write_text(\"hello\")")
                       (repl/eval-code ctx "file.append_text(\" world\")")
                       (repl/eval-code ctx "print(file.exists())")
                       (repl/eval-code ctx "print(file.is_file())")
                       (repl/eval-code ctx "print(nested.is_directory())")
                       (repl/eval-code ctx "print(file.name())")
                       (repl/eval-code ctx "print(file.extension())")
                       (repl/eval-code ctx "print(file.name_without_extension())")
                       (repl/eval-code ctx "print(file.absolute().exists())")
                       (repl/eval-code ctx "print(create Path.make(\"tmp/../tmp/notes.txt\").normalize().to_string())")
                       (repl/eval-code ctx "print(file.size() > 0)")
                       (repl/eval-code ctx "print(file.modified_time() > 0)")
                       (repl/eval-code ctx "let parent: ?Path := file.parent()")
                       (repl/eval-code ctx "if parent /= nil then print(parent.name()) end")
                       (repl/eval-code ctx "print(file.read_text())")
                       (repl/eval-code ctx "print(nested.list().length)")
                       (repl/eval-code ctx "let copied: Path := root.child(\"copied.txt\")")
                       (repl/eval-code ctx "file.copy_to(copied)")
                       (repl/eval-code ctx "print(copied.read_text())")
                       (repl/eval-code ctx "let moved: Path := root.child(\"moved.txt\")")
                       (repl/eval-code ctx "copied.move_to(moved)")
                       (repl/eval-code ctx "print(moved.exists())")
                       (repl/eval-code ctx "let writer: Text_File := create Text_File.open_write(file)")
                       (repl/eval-code ctx "writer.write_line(\"alpha\")")
                       (repl/eval-code ctx "writer.write_line(\"beta\")")
                       (repl/eval-code ctx "writer.close()")
                       (repl/eval-code ctx "let reader: Text_File := create Text_File.open_read(file)")
                       (repl/eval-code ctx "print(reader.read_line())")
                       (repl/eval-code ctx "print(reader.read_line())")
                       (repl/eval-code ctx "print(reader.read_line())")
                       (repl/eval-code ctx "reader.close()")
                       (repl/eval-code ctx "root.delete_tree()")
                       (repl/eval-code ctx "print(root.exists())"))]
          (is (.contains output "true"))
          (is (.contains output "\"notes.txt\""))
          (is (.contains output "\"txt\""))
          (is (.contains output "\"notes\""))
          (is (.contains output "tmp/notes.txt"))
          (is (.contains output "\"nested\""))
          (is (.contains output "\"hello world\""))
          (is (.contains output "true"))
          (is (.contains output "1"))
          (is (.contains output "\"hello world\""))
          (is (.contains output "\"alpha\""))
          (is (.contains output "\"beta\""))
          (is (.contains output "false")))
        (finally
          (delete-tree! tmp-dir))))))

(deftest io-directory-runtime-test
  (testing "Directory library filters child files and directories through the JVM interpreter"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-dir-io-" (System/nanoTime)))
          ctx (repl/init-repl-context)
          root-path (.getAbsolutePath tmp-dir)
          root-name (.getName tmp-dir)]
      (.mkdirs tmp-dir)
      (try
        (let [output (with-out-str
                       (repl/eval-code ctx "intern io/Path")
                       (repl/eval-code ctx "intern io/Directory")
                       (repl/eval-code ctx (str "let root: Directory := create Directory.make(\"" root-path "\")"))
                       (repl/eval-code ctx "let data: Directory := root.child_dir(\"data\")")
                       (repl/eval-code ctx "data.create_tree()")
                       (repl/eval-code ctx "let logs: Directory := root.child_dir(\"logs\")")
                       (repl/eval-code ctx "logs.create_tree()")
                       (repl/eval-code ctx "let f: Path := data.child_path(\"items.txt\")")
                       (repl/eval-code ctx "f.write_text(\"one\")")
                       (repl/eval-code ctx "print(root.exists())")
                       (repl/eval-code ctx "print(root.name())")
                       (repl/eval-code ctx "print(root.directories().length)")
                       (repl/eval-code ctx "print(data.files().length)")
                       (repl/eval-code ctx "print(data.files().get(0).name())")
                       (repl/eval-code ctx "print(root.absolute().to_string())")
                       (repl/eval-code ctx "root.delete_tree()")
                       (repl/eval-code ctx "print(root.exists())"))]
          (is (.contains output "true"))
          (is (.contains output (str "\"" root-name "\"")))
          (is (.contains output "2"))
          (is (.contains output "1"))
          (is (.contains output "\"items.txt\""))
          (is (.contains output "false")))
        (finally
          (delete-tree! tmp-dir))))))

(deftest io-binary-file-runtime-test
  (testing "Binary_File library reads and writes bytes through the JVM interpreter"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-bin-io-" (System/nanoTime)))
          ctx (repl/init-repl-context)
          root-path (.getAbsolutePath tmp-dir)]
      (.mkdirs tmp-dir)
      (try
        (let [output (with-out-str
                       (repl/eval-code ctx "intern io/Path")
                       (repl/eval-code ctx "intern io/Binary_File")
                       (repl/eval-code ctx (str "let root: Path := create Path.make(\"" root-path "\")"))
                       (repl/eval-code ctx "let file: Path := root.child(\"bytes.bin\")")
                       (repl/eval-code ctx "let writer: Binary_File := create Binary_File.open_write(file)")
                       (repl/eval-code ctx "writer.write([65, 66, 67, 68])")
                       (repl/eval-code ctx "writer.close()")
                       (repl/eval-code ctx "let reader: Binary_File := create Binary_File.open_read(file)")
                       (repl/eval-code ctx "print(reader.read(2))")
                       (repl/eval-code ctx "reader.close()")
                       (repl/eval-code ctx "let reader2: Binary_File := create Binary_File.open_read(file)")
                       (repl/eval-code ctx "print(reader2.read_all())")
                       (repl/eval-code ctx "reader2.close()"))]
          (is (.contains output "[65, 66]"))
          (is (.contains output "[65, 66, 67, 68]")))
        (finally
          (delete-tree! tmp-dir))))))
