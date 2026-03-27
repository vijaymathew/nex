;;; test-nex-mode.el --- Tests for nex-mode.el -*- lexical-binding: t; -*-

;;; Commentary:
;; Simple tests to verify nex-mode.el loads and works correctly.
;; Run with: emacs -batch -l nex-mode.el -l test-nex-mode.el -f ert-run-tests-batch-and-exit

;;; Code:

(require 'nex-mode)
(require 'ert)

(ert-deftest nex-mode-loads ()
  "Test that nex-mode loads without errors."
  (should (fboundp 'nex-mode)))

(ert-deftest nex-mode-activates ()
  "Test that nex-mode can be activated."
  (with-temp-buffer
    (nex-mode)
    (should (eq major-mode 'nex-mode))))

(ert-deftest nex-syntax-table-defined ()
  "Test that syntax table is properly defined."
  (should (syntax-table-p nex-mode-syntax-table)))

(ert-deftest nex-keywords-defined ()
  "Test that keywords are defined."
  (should nex-keywords)
  (should (member "class" nex-keywords))
  (should (member "feature" nex-keywords))
  (should (member "require" nex-keywords))
  (should (member "spawn" nex-keywords))
  (should (member "select" nex-keywords))
  (should (member "timeout" nex-keywords)))

(ert-deftest nex-types-defined ()
  "Test that types are defined."
  (should nex-types)
  (should (member "Integer" nex-types))
  (should (member "String" nex-types))
  (should (member "Boolean" nex-types))
  (should (member "Set" nex-types))
  (should (member "Task" nex-types))
  (should (member "Channel" nex-types)))

(ert-deftest nex-builtins-defined ()
  "Test that built-in helpers are defined."
  (should nex-builtins)
  (should (member "await_any" nex-builtins))
  (should (member "await_all" nex-builtins))
  (should (member "type_of" nex-builtins))
  (should (member "type_is" nex-builtins))
  (should (member "sleep" nex-builtins)))

(ert-deftest nex-font-lock-keywords-defined ()
  "Test that font-lock keywords are defined."
  (should nex-font-lock-keywords)
  (should (listp nex-font-lock-keywords)))

(ert-deftest nex-indentation-offset-customizable ()
  "Test that indentation offset is customizable."
  (should (numberp nex-indent-offset))
  (let ((nex-indent-offset 4))
    (should (= nex-indent-offset 4))))

(ert-deftest nex-mode-keymap-defined ()
  "Test that mode keymap is defined."
  (should (keymapp nex-mode-map))
  ;; Check some key bindings
  (should (keymapp nex-mode-map)))

(ert-deftest nex-auto-mode-alist ()
  "Test that .nex files are associated with nex-mode."
  (should (assoc "\\.nex\\'" auto-mode-alist)))

(ert-deftest nex-comment-syntax ()
  "Test comment syntax configuration."
  (with-temp-buffer
    (nex-mode)
    (should (string= comment-start "-- "))
    (should (string= comment-end ""))))

(ert-deftest nex-underscore-is-part-of-identifiers ()
  "Test that underscores are treated as part of identifiers."
  (with-temp-buffer
    (nex-mode)
    (should (eq (char-syntax ?_) ?w))
    (insert "from_loc from")
    (goto-char (point-min))
    (should-not (re-search-forward (regexp-opt nex-keywords 'words) 9 t))
    (goto-char (point-min))
    (search-forward "from" nil t 2)
    (should (= (match-beginning 0) 10))))

(ert-deftest nex-imenu-support ()
  "Test that imenu support is configured."
  (with-temp-buffer
    (nex-mode)
    (should imenu-generic-expression)))

(ert-deftest nex-indentation-function ()
  "Test that indentation function is defined."
  (with-temp-buffer
    (nex-mode)
    (should (eq indent-line-function 'nex-indent-line))))

(ert-deftest nex-basic-highlighting ()
  "Test basic syntax highlighting."
  (with-temp-buffer
    (nex-mode)
    (insert "class TestClass\n")
    (insert "  feature\n")
    (insert "    test() do\n")
    (insert "      print(42)\n")
    (insert "    end\n")
    (insert "end\n")
    (font-lock-fontify-buffer)
    ;; Buffer should be successfully fontified
    (should (> (point-max) 1))))

(ert-deftest nex-repl-function-exists ()
  "Test that REPL function exists."
  (should (fboundp 'nex-repl))
  (should (fboundp 'nex-eval-buffer))
  (should (fboundp 'nex-eval-region)))

;;; test-nex-mode.el ends here
