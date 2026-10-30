;;; nex-mode.el --- Major mode for editing Nex programming language files -*- lexical-binding: t; -*-

;; Copyright (C) 2026 Nex Contributors

;; Author: Nex Contributors
;; Keywords: languages, nex, design-by-contract
;; Version: 0.6.7
;; Package-Requires: ((emacs "24.3"))
;; URL: https://github.com/vijaymathew/nex

;;; Commentary:

;; This package provides a major mode for editing Nex programming language files.
;; Nex is an Eiffel-inspired language with Design by Contract features.
;;
;; Features:
;; - Syntax highlighting for Nex keywords, types, and contracts
;; - Automatic indentation
;; - REPL integration (C-c C-z to start, C-c C-c to eval buffer)
;; - Comment support
;; - Imenu support for navigation
;; - Errors as you type and formatting through eglot (`nex lsp')
;;
;; Installation:
;;
;; Add this file to your load-path and add to your init.el:
;;   (require 'nex-mode)
;;   (add-to-list 'auto-mode-alist '("\\.nex\\'" . nex-mode))
;;
;; Usage:
;;
;; Open a .nex file and the mode will activate automatically.
;;
;; Key bindings:
;;   C-c C-z    Start Nex REPL
;;   C-c C-c    Evaluate current buffer in REPL
;;   C-c C-r    Evaluate region in REPL
;;   C-c C-l    Load file in REPL
;;   C-c C-d    Show documentation (help)
;;   C-M-a      Beginning of defun (class/method)
;;   C-M-e      End of defun (class/method)

;;; Code:

(require 'comint)
(require 'seq)

;;;; Customization

(defgroup nex nil
  "Major mode for editing Nex code."
  :prefix "nex-"
  :group 'languages)

(defcustom nex-repl-program "nex"
  "Program to run for Nex REPL."
  :type 'string
  :group 'nex)

(defcustom nex-repl-arguments '()
  "Arguments to pass to Nex REPL."
  :type '(repeat string)
  :group 'nex)

(defcustom nex-indent-offset 2
  "Number of spaces for each indentation level in Nex mode."
  :type 'integer
  :group 'nex)

;;;; Faces

(defface nex-comment-face
  '((((background light)) :foreground "gray50" :slant italic)
    (((background dark)) :foreground "gray70" :slant italic)
    (t :foreground "gray60" :slant italic))
  "Face for comments in Nex code."
  :group 'nex)

;;;; Syntax Table

(defvar nex-mode-syntax-table
  (let ((table (make-syntax-table)))
    ;; Comments.  `-' is punctuation (class ".") that also carries the comment
    ;; flags "12": it is both the first and second character of the "--" two-char
    ;; comment opener.  Do NOT re-modify ?- below as a plain operator (".") — that
    ;; strips these flags, leaving "--" unrecognized as a comment, so the string
    ;; scanner then treats a " or ' inside a comment as opening a string and
    ;; mis-highlights everything after it.
    (modify-syntax-entry ?- ". 12" table)  ;; -- starts and continues comment
    (modify-syntax-entry ?\n ">" table)    ;; newline ends comment

    ;; Strings
    (modify-syntax-entry ?\" "\"" table)
    (modify-syntax-entry ?\' "\"" table)

    ;; Operators
    (modify-syntax-entry ?+ "." table)
    ;; NB: ?- is deliberately NOT set here; its comment-aware ". 12" entry above
    ;; already gives it punctuation class.  Re-setting it to "." would break "--"
    ;; comment parsing (see the comment block above).
    (modify-syntax-entry ?* "." table)
    (modify-syntax-entry ?/ "." table)
    (modify-syntax-entry ?= "." table)
    (modify-syntax-entry ?< "." table)
    (modify-syntax-entry ?> "." table)
    (modify-syntax-entry ?& "." table)
    (modify-syntax-entry ?| "." table)

    ;; Parentheses, brackets, and braces
    (modify-syntax-entry ?\( "()" table)
    (modify-syntax-entry ?\) ")(" table)
    (modify-syntax-entry ?\[ "(]" table)
    (modify-syntax-entry ?\] ")[" table)
    (modify-syntax-entry ?\{ "(}" table)
    (modify-syntax-entry ?\} "){" table)

    ;; Treat underscores as part of words so keyword regexps do not match
    ;; prefixes inside identifiers like "from_loc".
    (modify-syntax-entry ?_ "w" table)

    table)
  "Syntax table for Nex mode.")

;;;; Font Lock (Syntax Highlighting)

(defconst nex-keywords
  '("class" "feature" "inherit" "end" "do" "if" "then" "else" "elseif"
    "when" "from" "until" "invariant" "variant" "require" "ensure"
    "let" "as" "and" "or" "implies" "not" "fn" "deferred" "convert" "to"
    "old" "create" "private" "note" "with" "import" "intern" "function"
    "raise" "rescue" "retry" "assert" "repeat" "across" "case" "of"
    "spawn" "select" "timeout" "declare" "sealed" "once" "match" "type"
    "enum" "union" "where" "this" "super")
  "Nex language keywords.")

(defconst nex-types
  '("Integer" "Integer64" "Integer16" "Integer32" "Byte" "Real" "Decimal" "Char" "Boolean" "String"
    "Array" "Map" "Set" "Task" "Channel" "Any" "Void" "Function" "Cursor")
  "Nex built-in types.")

(defconst nex-constants
  '("true" "false" "nil")
  "Nex language constants.")

(defconst nex-builtins
  '("print" "println" "result" "exception" "sleep"
    "type_of" "type_is" "await_any" "await_all" "alias")
  "Nex built-in functions and special variables.")

(defvar nex-font-lock-keywords
  `(
    ;; NB: comments are fontified syntactically (see the syntax table and the
    ;; buffer-local `font-lock-comment-face' set in `nex-mode'), NOT by a keyword
    ;; here.  A keyword regexp like "--.*$" cannot tell a real comment from "--"
    ;; inside a string, and would also fight the syntactic pass.

    ;; Contract labels (e.g., "positive:" in require clause)
    ("\\b\\([a-z_][a-z0-9_]*\\):" 1 font-lock-constant-face)

    ;; Keywords
    (,(regexp-opt nex-keywords 'words) . font-lock-keyword-face)

    ;; Types
    (,(regexp-opt nex-types 'words) . font-lock-type-face)

    ;; Constants
    (,(regexp-opt nex-constants 'words) . font-lock-constant-face)

    ;; Built-in functions
    (,(regexp-opt nex-builtins 'words) . font-lock-builtin-face)

    ;; Class names (after "class" keyword)
    ("\\<class\\s-+\\([A-Z][a-zA-Z0-9_]*\\)" 1 font-lock-type-face)

    ;; Union type names (after "union" keyword)
    ("\\<union\\s-+\\([A-Z][a-zA-Z0-9_]*\\)" 1 font-lock-type-face)

    ;; Method/function definitions
    ("\\<\\([a-z_][a-z0-9_]*\\)\\s-*(" 1 font-lock-function-name-face)

    ;; Anonymous functions
    ("\\<fn\\>\\s-*(" . font-lock-keyword-face)

    ;; Variables after "let"
    ("\\<let\\s-+\\([a-z_][a-z0-9_]*\\)" 1 font-lock-variable-name-face)

    ;; Parameterized types (e.g., Array[String], Map[K, V], Task[Integer])
    ("\\<\\(Array\\|Map\\|Set\\|Task\\|Channel\\)\\s-*\\[" 1 font-lock-type-face)

    ;; Generic constraints (arrow operator ->)
    ("->" . font-lock-keyword-face)

    ;; Expression-bodied anonymous functions: fn (x) => e
    ("=>" . font-lock-keyword-face)

    ;; Assignment operator
    (":=" . font-lock-keyword-face)

    ;; Numbers
    ("\\<[0-9]+\\(\\.[0-9]+\\)?\\>" . font-lock-constant-face)
    )
  "Font lock keywords for Nex mode.")

;;;; Indentation
;;
;; A port of the indentation pass of `nex format' (src/nex/fmt.clj), so that
;; Emacs and the formatter always agree.  A change to one should be made to
;; the other; test/scripts/test-nex-mode.el and test/nex/fmt_test.clj cover
;; the same rules.
;;
;; The whole buffer is tokenized and walked once, top to bottom, keeping a
;; stack of the blocks that are open: a line's indentation comes from the
;; innermost one.  Code lines are indented from the code alone; a comment line
;; moves by as much as the next line of code does.

(defconst nex-fmt--continuing-ends
  '("+" "-" "*" "/" "%" "^" "=" "/=" "<" ">" "<=" ">=" ":=" "," "." "?." "->" "=>"
    ":" "and" "or" "implies" "not")
  "A line ending with one of these continues on the next line.")

(defconst nex-fmt--continuing-starts
  '("and" "or" "implies" "." "?." "+" "*" "/" "%" "^" "=" "/=" "<" ">" "<=" ">=" ":=")
  "A line starting with one of these continues the line before.")

(defconst nex-fmt--clause-words
  '((class "feature" "private" "create" "invariant")
    (routine "require" "do" "ensure" "rescue" "deferred")
    (do "ensure" "rescue")
    (if "then" "elseif" "else")
    (loop "invariant" "variant" "until" "do" "rescue")
    (block-loop "do" "rescue")
    (case "else")
    (select "when" "timeout" "else"))
  "For each kind of block, the words that start one of its clauses.")

(defsubst nex-fmt--text (tok) (and tok (aref tok 0)))
(defsubst nex-fmt--line (tok) (aref tok 1))
(defsubst nex-fmt--kind (tok) (and tok (aref tok 2)))

(defun nex-fmt--letter-p (c)
  (memq (get-char-code-property c 'general-category) '(Lu Ll Lt Lm Lo)))

(defun nex-fmt--digit-p (c)
  (eq (get-char-code-property c 'general-category) 'Nd))

(defun nex-fmt--word-start-p (c)
  (or (eq c ?_) (nex-fmt--letter-p c)))

(defun nex-fmt--word-char-p (c)
  (or (eq c ?_) (nex-fmt--letter-p c) (nex-fmt--digit-p c)))

(defun nex-fmt--tokenize (src)
  "The tokens of SRC and the lines that end inside a string.
Return (TOKENS . STRING-LINES): TOKENS a list of [TEXT LINE KIND], KIND
being `word', `string', `char', `comment', `punct' or `other', and lines
counted from 0; STRING-LINES a hash table of line numbers."
  (let ((n (length src)) (i 0) (line 0) (tokens nil)
        (string-lines (make-hash-table)))
    (while (< i n)
      (let ((c (aref src i)))
        (cond
         ((eq c ?\n) (setq i (1+ i) line (1+ line)))
         ((memq c '(?\s ?\t ?\r ?\f ?\v)) (setq i (1+ i)))
         ((and (eq c ?-) (< (1+ i) n) (eq (aref src (1+ i)) ?-))
          (let ((end (or (string-search "\n" src i) n)))
            (push (vector (substring src i end) line 'comment) tokens)
            (setq i end)))
         ((memq c '(?\" ?'))
          (let ((j (1+ i)) (count 0) (done nil))
            (while (not done)
              (let ((d (and (< j n) (aref src j))))
                (cond
                 ((null d) (setq j n done t))
                 ((eq d ?\\)
                  (when (and (< (1+ j) n) (eq (aref src (1+ j)) ?\n))
                    (puthash (+ line count) t string-lines)
                    (setq count (1+ count)))
                  (setq j (+ j 2)))
                 ((eq d c) (setq j (1+ j) done t))
                 ((eq d ?\n)
                  (puthash (+ line count) t string-lines)
                  (setq count (1+ count) j (1+ j)))
                 (t (setq j (1+ j))))))
            (setq j (min j n))
            (push (vector (substring src i j) line 'string) tokens)
            (setq i j line (+ line count))))
         ((eq c ?#)
          (let* ((d (and (< (1+ i) n) (aref src (1+ i))))
                 (end (cond
                       ((eq d ?{) (+ i 2))
                       ((and d (nex-fmt--word-char-p d))
                        (let ((j (+ i 2)))
                          (while (and (< j n) (nex-fmt--word-char-p (aref src j)))
                            (setq j (1+ j)))
                          j))
                       ((and d (not (eq d ?\n))) (+ i 2))
                       (t (1+ i)))))
            (push (vector (substring src i end) line (if (eq d ?{) 'punct 'char))
                  tokens)
            (setq i end)))
         ((nex-fmt--word-start-p c)
          (let ((j (1+ i)))
            (while (and (< j n) (nex-fmt--word-char-p (aref src j)))
              (setq j (1+ j)))
            (push (vector (substring src i j) line 'word) tokens)
            (setq i j)))
         ((nex-fmt--digit-p c)
          (let ((j (1+ i)) (go t))
            (while go
              (let ((d (and (< j n) (aref src j))))
                (cond
                 ((null d) (setq go nil))
                 ((nex-fmt--word-char-p d) (setq j (1+ j)))
                 ((and (eq d ?.) (< (1+ j) n) (nex-fmt--digit-p (aref src (1+ j))))
                  (setq j (1+ j)))
                 (t (setq go nil)))))
            (push (vector (substring src i j) line 'other) tokens)
            (setq i j)))
         (t
          (let* ((two (and (< (1+ i) n) (substring src i (+ i 2))))
                 (end (if (member two '(":=" "/=" "<=" ">=" "->" "=>" "?.")) (+ i 2) (1+ i))))
            (push (vector (substring src i end) line 'punct) tokens)
            (setq i end))))))
    (cons (nreverse tokens) string-lines)))

(defun nex-fmt--frame (kind at &rest more)
  "A block of KIND opened on a line indented AT columns: its clauses go at
:c, its lines at :body, its `end' at :end.  MORE overrides those."
  (let ((f (list :kind kind :c at :body (+ at nex-indent-offset) :end at)))
    (while more
      (setq f (plist-put f (car more) (cadr more))
            more (cddr more)))
    f))

(defun nex-fmt--with (frame &rest kvs)
  "A copy of FRAME with the keys and values KVS set."
  (let ((f (copy-sequence frame)))
    (while kvs
      (setq f (plist-put f (car kvs) (cadr kvs))
            kvs (cddr kvs)))
    f))

(defsubst nex-fmt--clause-word-p (kind word)
  (and word (member word (cdr (assq kind nex-fmt--clause-words)))))

(defun nex-fmt--continuation-indent (top t0 prev op head head-word prev-new)
  "The indentation of a line that continues a statement.
TOP is the innermost open frame, T0 the line's first token, PREV the last
code token of the line above, OP whether the line continues by an operator,
HEAD and HEAD-WORD the indentation and first word of the statement's first
line, PREV-NEW the new indentation of the line above."
  (let ((t0-text (nex-fmt--text t0)))
    (pcase (plist-get top :kind)
      ;; Inside brackets: one level past the line that opened them, and a
      ;; closing bracket level with that line.  An operator line continuing
      ;; an argument written on a line of its own goes one level past it.
      ('bracket
       (cond
        ((member t0-text '(")" "]" "}")) (plist-get top :line))
        ((and op (plist-get top :arg)) (+ (plist-get top :arg) nex-indent-offset))
        (t (+ (plist-get top :line) nex-indent-offset))))
      ;; A `when ... then ... else ... end' expression reads like an `if'.
      ('when-expr
       (if (member t0-text '("then" "else" "elseif" "end"))
           (plist-get top :line)
         (+ (plist-get top :line) nex-indent-offset)))
      (_
       (cond
        ;; the next item of a list, as after `inherit'
        ((equal "," (nex-fmt--text prev)) prev-new)
        ((member t0-text '("then" "else" "elseif" "end")) head)
        ;; A condition goes two levels in, apart from the body below it.
        ((member head-word '("if" "elseif" "until"))
         (+ head (* 2 nex-indent-offset)))
        (t (+ head nex-indent-offset)))))))

(defun nex-fmt--code-indents (order code)
  "Indent the code lines ORDER (sorted line numbers); CODE maps each to a
vector of its code tokens.  Return (INDENT . AFTER), two hash tables from
line number to the line's indentation and to where a statement would go on
the line after (absent inside an expression)."
  (let ((indents (make-hash-table)) (afters (make-hash-table))
        (stack (list (nex-fmt--frame 'top 0 :body 0)))
        (prev nil) (head 0) (head-word nil) (pending nil) (prev-new nil)
        (rest order))
    (while rest
      (let* ((i (car rest))
             (toks (gethash i code))
             (n (length toks))
             (t0 (aref toks 0))
             (w0 (and (eq 'word (nex-fmt--kind t0)) (nex-fmt--text t0)))
             (prev-text (nex-fmt--text prev))
             ;; Whether the line continues an expression by an operator, at
             ;; its start or at the end of the line above.
             (op (or (member (nex-fmt--text t0) nex-fmt--continuing-starts)
                     (and (member prev-text nex-fmt--continuing-ends)
                          (not (equal "," prev-text)))))
             (continues
              (or (memq (plist-get (car stack) :kind) '(bracket when-expr))
                  ;; a routine's `note "..."' and `alias "-"' belong to its header
                  (and pending (member w0 '("note" "alias"))
                       (> n 1) (eq 'string (nex-fmt--kind (aref toks 1))))
                  (member prev-text nex-fmt--continuing-ends)
                  (member (nex-fmt--text t0) nex-fmt--continuing-starts)))
             top clause case-level indent clause-line)
        ;; A lone `assert' takes the `label: expression' lines after it.
        (when (and (eq 'assert (plist-get (car stack) :kind)) (not continues)
                   (not (and (eq 'word (nex-fmt--kind t0))
                             (> n 1) (equal ":" (nex-fmt--text (aref toks 1))))))
          (setq stack (cdr stack)))
        ;; A header followed by `require' or `do' on a line of its own is a
        ;; routine.  Its clauses go flush with the header.
        (when (and (not continues) pending
                   (member w0 '("require" "do" "deferred"))
                   (not (plist-get (car stack) :await-do)))
          (push (nex-fmt--frame 'routine head :await-do t) stack))
        ;; A class's sections go flush with `class'.
        (let ((f (car stack)))
          (when (and (not continues) (eq 'class (plist-get f :kind))
                     (null (plist-get f :c))
                     (nex-fmt--clause-word-p 'class w0))
            (setq stack (cons (nex-fmt--with f :c (plist-get f :end)) (cdr stack)))))
        (setq top (car stack))
        ;; `do' in a routine or loop body starts a block, unless the routine
        ;; or loop is still waiting for its own `do'.
        (setq clause (and (not continues)
                          (nex-fmt--clause-word-p (plist-get top :kind) w0)
                          (or (not (equal "do" w0)) (plist-get top :await-do)
                              (not (memq (plist-get top :kind)
                                         '(routine loop block-loop))))))
        (setq case-level (and (eq 'case (plist-get top :kind))
                              (if (or (plist-get top :stmt) (plist-get top :else-block))
                                  (+ (plist-get top :c) nex-indent-offset)
                                (plist-get top :c))))
        (setq indent
              (cond
               (continues (nex-fmt--continuation-indent
                           top t0 prev op head head-word prev-new))
               ((equal "end" w0) (plist-get top :end))
               (clause (plist-get top :c))
               ((and (eq 'class (plist-get top :kind)) (equal "note" w0))
                (+ (plist-get top :end) nex-indent-offset))
               ;; `inherit' goes flush with `class'.
               ((and (eq 'class (plist-get top :kind)) (equal "inherit" w0))
                (plist-get top :end))
               (case-level case-level)
               (t (plist-get top :body))))
        (setq clause-line (and (not continues) (eq 'case (plist-get top :kind))
                               (eql indent (plist-get top :c))
                               (not (equal "else" w0))))
        (cond
         ;; The parents listed under `inherit' go one level deeper.
         ((and (not continues) (eq 'class (plist-get top :kind)) (equal "inherit" w0))
          (setq stack (cons (nex-fmt--with top :body (+ indent nex-indent-offset))
                            (cdr stack))))
         ;; A clause sets the level of the lines after it.
         ((and clause (not (eq 'case (plist-get top :kind))))
          (setq stack (cons (nex-fmt--with top :body (+ (plist-get top :c) nex-indent-offset))
                            (cdr stack))))
         ;; A case or match clause's statement ends at the line's end.
         ((eq 'case (plist-get top :kind))
          (setq stack (cons (nex-fmt--with top :stmt nil) (cdr stack)))))
        ;; An argument line inside brackets is what an operator line after
        ;; it continues.
        (when (and continues (not op) (eq 'bracket (plist-get top :kind)))
          (setq stack (cons (nex-fmt--with (car stack) :arg indent) (cdr stack))))
        (unless continues
          (setq head indent head-word w0))
        ;; The blocks opened on this line.  Those opened on a statement's
        ;; first line, or outside any bracket, line up with that line; one
        ;; opened inside brackets on a later line lines up with its own line.
        (let ((j 0) (seen-then nil))
          (while (< j n)
            (let* ((tok (aref toks j))
                   (text (nex-fmt--text tok))
                   (after-dot (and (> j 0)
                                   (member (nex-fmt--text (aref toks (1- j))) '("." "?."))))
                   (last (= j (1- n)))
                   (f (car stack))
                   (kind (plist-get f :kind))
                   (at (if (eq 'bracket kind) indent head))
                   (word (and (eq 'word (nex-fmt--kind tok)) (not after-dot) text)))
              (cond
               ((member text '("(" "[" "{" "#{"))
                (push (list :kind 'bracket :line indent) stack))
               ((member text '(")" "]" "}"))
                (while (and (cdr stack) (not (eq 'bracket (plist-get (car stack) :kind))))
                  (setq stack (cdr stack)))
                (when (cdr stack) (setq stack (cdr stack))))
               ((null word))
               ((equal "class" word)
                (push (nex-fmt--frame 'class at :c nil) stack))
               ((and (equal "union" word) (member w0 '("union" "enum")))
                (push (nex-fmt--frame 'union at :c (+ at nex-indent-offset)) stack))
               ((equal "if" word)
                (unless (and clause-line (not seen-then)) ; a match clause's guard
                  (push (nex-fmt--frame 'if at) stack)))
               ((and (equal "from" word) (not (equal "import" w0)))
                (push (nex-fmt--frame 'loop at :await-do t) stack))
               ((member word '("repeat" "across" "with"))
                (push (nex-fmt--frame 'block-loop at :await-do t) stack))
               ((member word '("case" "match"))
                (push (nex-fmt--frame 'case at :c (+ at nex-indent-offset)) stack))
               ((equal "select" word)
                (push (nex-fmt--frame 'select at :c (+ at nex-indent-offset)
                                      :body (+ at (* 2 nex-indent-offset)))
                      stack))
               ;; `when' starting a line of a select is a clause; anywhere
               ;; else it starts a `when ... end' expression.
               ((equal "when" word)
                (unless (and (eq 'select kind) (= j 0))
                  (push (list :kind 'when-expr :line indent) stack)))
               ((equal "do" word)
                (if (plist-get f :await-do)
                    (setq stack (cons (nex-fmt--with f :await-do nil
                                                     :body (+ (plist-get f :c) nex-indent-offset))
                                      (cdr stack)))
                  (push (nex-fmt--frame 'do at) stack)))
               ((and (equal "then" word) (eq 'case kind))
                (when last
                  (setq stack (cons (nex-fmt--with f :stmt t) (cdr stack)))))
               ((and (equal "else" word) (eq 'case kind) last)
                (setq stack (cons (nex-fmt--with f :else-block t) (cdr stack))))
               ((equal "end" word)
                (when (cdr stack) (setq stack (cdr stack))))
               ;; A deferred routine without `ensure' has no `end'.
               ((and (equal "deferred" word) (= j 0) (eq 'routine kind)
                     (not (let* ((next (cadr rest))
                                 (nt (and next (aref (gethash next code) 0))))
                            (and (eq 'word (nex-fmt--kind nt))
                                 (equal "ensure" (nex-fmt--text nt))))))
                (setq stack (cdr stack)))
               ((and (equal "assert" word) (= j 0) (= n 1))
                (push (list :kind 'assert :c indent :body (+ indent nex-indent-offset)
                            :end indent)
                      stack)))
              (setq seen-then (or seen-then (equal "then" word))
                    j (1+ j)))))
        (let* ((words (delq nil (mapcar (lambda (tok)
                                          (and (eq 'word (nex-fmt--kind tok))
                                               (nex-fmt--text tok)))
                                        toks)))
               (fn-rest (member "fn" words))
               (no-do-after-fn (and fn-rest (not (member "do" fn-rest)))))
          (setq pending
                (if continues
                    (and pending (not (member "do" words)))
                  (or (and (equal "function" w0) (not (member "do" words)))
                      ;; a member, maybe after its section's word
                      ;; (`create make(x: Integer)')
                      (and (eq 'class (plist-get (seq-find
                                                  (lambda (f) (not (eq 'bracket (plist-get f :kind))))
                                                  stack)
                                                 :kind))
                           (eq 'word (nex-fmt--kind t0))
                           (not (member "class" words))
                           (seq-some (lambda (w) (not (nex-fmt--clause-word-p 'class w))) words)
                           (not (member w0 '("note" "inherit"))))
                      no-do-after-fn))))
        (puthash i indent indents)
        (let* ((f (car stack))
               (after (pcase (plist-get f :kind)
                        ((or 'bracket 'when-expr 'assert) nil)
                        ('case (if (or (plist-get f :stmt) (plist-get f :else-block))
                                   (+ (plist-get f :c) nex-indent-offset)
                                 (plist-get f :c)))
                        (_ (plist-get f :body)))))
          (when after (puthash i after afters)))
        (setq prev (aref toks (1- n))
              prev-new indent
              rest (cdr rest))))
    (cons indents afters)))

(defun nex-fmt--indent-width (line)
  (let ((i 0) (w 0) (n (length line)) (go t))
    (while (and go (< i n))
      (pcase (aref line i)
        (?\s (setq w (1+ w)))
        (?\t (setq w (+ w nex-indent-offset)))
        (_ (setq go nil)))
      (when go (setq i (1+ i))))
    w))

(defun nex-fmt--line-indents (src)
  "The new indentation of each line of SRC, as `nex format' would give it.
Return a vector with one element per line: the indentation, `blank' for
a line of only spaces, or nil for a line to leave as it is (the `#!' line,
or one starting inside a string)."
  (let* ((shebang (and (string-prefix-p "#!" src)
                       (substring src 0 (or (string-search "\n" src) (length src)))))
         ;; Blank out the `#!' line so its text isn't read as code.
         (scan (if shebang
                   (concat (make-string (length shebang) ?\s) (substring src (length shebang)))
                 src))
         (tokenized (nex-fmt--tokenize scan))
         (string-lines (cdr tokenized))
         (text-lines (vconcat (split-string src "\n")))
         (nlines (length text-lines))
         (starts-in-string (lambda (i) (gethash (1- i) string-lines)))
         (code (make-hash-table))
         (has-tokens (make-hash-table)))
    ;; A line that starts inside a string belongs to the line where the
    ;; string started.
    (dolist (tok (car tokenized))
      (let ((i (nex-fmt--line tok)))
        (while (funcall starts-in-string i) (setq i (1- i)))
        (puthash i t has-tokens)
        (unless (eq 'comment (nex-fmt--kind tok))
          (puthash i (cons tok (gethash i code)) code))))
    (let (order)
      (maphash (lambda (i toks)
                 (puthash i (vconcat (nreverse toks)) code)
                 (push i order))
               code)
      (setq order (sort order #'<))
      (let* ((result (nex-fmt--code-indents order code))
             (indents (car result))
             (afters (cdr result))
             (code-vec (vconcat order))
             (out (make-vector nlines nil))
             (old-of (lambda (i) (nex-fmt--indent-width (aref text-lines i))))
             (delta-of (lambda (j) (- (gethash j indents) (funcall old-of j))))
             (k 0))
        (dotimes (i nlines)
          ;; k: the index in code-vec of the first code line after i
          (while (and (< k (length code-vec)) (<= (aref code-vec k) i))
            (setq k (1+ k)))
          (let ((line (aref text-lines i)))
            (cond
             ((funcall starts-in-string i))
             ((and shebang (= i 0)))
             ((string-match-p "\\`[ \t\r]*\\'" line) (aset out i 'blank))
             ((gethash i indents) (aset out i (gethash i indents)))
             (t
              ;; A comment line moves by as much as the next line of code
              ;; does, unless it is deeper than that line: then it ends the
              ;; block above and goes where a statement there would.
              (let* ((old (funcall old-of i))
                     (next-code (and (< k (length code-vec)) (aref code-vec k)))
                     (prev-code (let ((p (1- k)))
                                  (while (and (>= p 0) (>= (aref code-vec p) i))
                                    (setq p (1- p)))
                                  (and (>= p 0) (aref code-vec p))))
                     (next-old (and next-code (funcall old-of next-code))))
                (aset out i
                      (cond
                       ((and next-code (<= old next-old))
                        (max 0 (+ old (- (gethash next-code indents) next-old))))
                       ;; far to the right: a note lined up with end-of-line
                       ;; comments above, so it moves with the line above
                       ((and prev-code (> old (+ (funcall old-of prev-code)
                                                 (* 2 nex-indent-offset))))
                        (max 0 (+ old (funcall delta-of prev-code))))
                       ((and prev-code (gethash prev-code afters))
                        (gethash prev-code afters))
                       (next-code (max 0 (+ old (- (gethash next-code indents) next-old))))
                       (prev-code (max 0 (+ old (funcall delta-of prev-code))))
                       (t old))))))))
        out))))

(defun nex-indent-line ()
  "Indent the current line as `nex format' would.
A blank line gets the indentation a statement typed there would have."
  (interactive)
  (let* ((line (count-lines (point-min) (line-beginning-position)))
         (blank (save-excursion (beginning-of-line) (looking-at "[ \t]*$")))
         (src (buffer-substring-no-properties (point-min) (point-max)))
         (col (aref (nex-fmt--line-indents
                     (if blank
                         ;; Stand in a statement for the blank line.
                         (let ((start (- (line-beginning-position) (point-min)))
                               (end (- (line-end-position) (point-min))))
                           (concat (substring src 0 start) "x" (substring src end)))
                       src))
                    line))
         (offset (- (current-column) (current-indentation))))
    (when (numberp col)
      (indent-line-to col)
      (when (> offset 0)
        (move-to-column (+ col offset))))))

(defun nex-indent-region (start end)
  "Indent the lines between START and END as `nex format' would."
  (let ((indents (nex-fmt--line-indents
                  (buffer-substring-no-properties (point-min) (point-max))))
        (first (count-lines (point-min) (save-excursion (goto-char start)
                                                        (line-beginning-position))))
        (end-marker (copy-marker end)))
    (save-excursion
      (goto-char start)
      (beginning-of-line)
      (let ((i first))
        (while (and (< (point) end-marker) (not (eobp)))
          (let ((col (and (< i (length indents)) (aref indents i))))
            (cond
             ((numberp col) (indent-line-to col))
             ;; a line of only spaces becomes empty, as `nex format' makes it
             ((and (eq col 'blank) (looking-at "[ \t]+$"))
              (delete-region (point) (match-end 0)))))
          (forward-line 1)
          (setq i (1+ i)))))
    (set-marker end-marker nil)))

;;;; Navigation

(defun nex-beginning-of-defun (&optional arg)
  "Move backward to the beginning of a class or method definition.
ARG is the number of definitions to move."
  (interactive "^p")
  (or arg (setq arg 1))
  (let ((case-fold-search nil))
    (re-search-backward
     "^\\s-*\\(class\\|\\sw+\\s-*(\\)"
     nil 'move arg)))

(defun nex-end-of-defun (&optional arg)
  "Move forward to the end of a class or method definition.
ARG is the number of definitions to move."
  (interactive "^p")
  (or arg (setq arg 1))
  (let ((case-fold-search nil))
    (re-search-forward "^\\s-*end\\s-*$" nil 'move arg)))

;;;; Imenu Support

(defvar nex-imenu-generic-expression
  '(("Classes" "^\\s-*class\\s-+\\([A-Z][a-zA-Z0-9_]*\\)" 1)
    ("Methods" "^\\s-*\\([a-z_][a-z0-9_]*\\)\\s-*(" 1))
  "Imenu generic expression for Nex mode.")

;;;; REPL Integration

(defvar nex-repl-buffer nil
  "Buffer for Nex REPL.")

(defun nex-repl ()
  "Start a Nex REPL session."
  (interactive)
  (unless (and nex-repl-buffer
               (comint-check-proc nex-repl-buffer))
    (setq nex-repl-buffer
          (apply 'make-comint
                 "nex-repl"
                 nex-repl-program
                 nil
                 nex-repl-arguments))
    (with-current-buffer nex-repl-buffer
      (nex-repl-mode)))
  (pop-to-buffer nex-repl-buffer))

(defun nex-eval-buffer ()
  "Evaluate the current buffer in the Nex REPL."
  (interactive)
  (nex-eval-region (point-min) (point-max)))

(defun nex-eval-region (start end)
  "Evaluate the region between START and END in the Nex REPL."
  (interactive "r")
  (let ((code (buffer-substring-no-properties start end)))
    (unless (and nex-repl-buffer
                 (comint-check-proc nex-repl-buffer))
      (nex-repl))
    (with-current-buffer nex-repl-buffer
      (goto-char (point-max))
      (insert code)
      (comint-send-input))))

(defun nex-load-file (filename)
  "Load FILENAME into the Nex REPL."
  (interactive (list (buffer-file-name)))
  (when filename
    (unless (and nex-repl-buffer
                 (comint-check-proc nex-repl-buffer))
      (nex-repl))
    (with-current-buffer nex-repl-buffer
      (goto-char (point-max))
      (insert (format ":load %s" filename))
      (comint-send-input))
    (message "Loaded %s" filename)))

(defun nex-repl-show-help ()
  "Show Nex REPL help."
  (interactive)
  (unless (and nex-repl-buffer
               (comint-check-proc nex-repl-buffer))
    (nex-repl))
  (with-current-buffer nex-repl-buffer
    (goto-char (point-max))
    (insert ":help")
    (comint-send-input)))

;;;; REPL Mode

(defvar nex-repl-mode-map
  (let ((map (make-sparse-keymap)))
    (set-keymap-parent map comint-mode-map)
    (define-key map (kbd "C-c C-d") 'nex-repl-show-help)
    map)
  "Keymap for Nex REPL mode.")

(define-derived-mode nex-repl-mode comint-mode "Nex-REPL"
  "Major mode for interacting with Nex REPL."
  :syntax-table nex-mode-syntax-table
  (setq comint-prompt-regexp "^nex> \\|^\\.\\.\\. ")
  (setq comint-process-echoes nil)
  (set (make-local-variable 'comint-prompt-read-only) t))

;;;; Main Mode

(defvar nex-mode-map
  (let ((map (make-sparse-keymap)))
    (define-key map (kbd "C-c C-z") 'nex-repl)
    (define-key map (kbd "C-c C-c") 'nex-eval-buffer)
    (define-key map (kbd "C-c C-r") 'nex-eval-region)
    (define-key map (kbd "C-c C-l") 'nex-load-file)
    (define-key map (kbd "C-c C-d") 'nex-repl-show-help)
    (define-key map (kbd "C-M-a") 'nex-beginning-of-defun)
    (define-key map (kbd "C-M-e") 'nex-end-of-defun)
    map)
  "Keymap for Nex mode.")

;;;###autoload
(define-derived-mode nex-mode prog-mode "Nex"
  "Major mode for editing Nex programming language files.

Nex is an Eiffel-inspired language with Design by Contract features.

\\{nex-mode-map}"
  :syntax-table nex-mode-syntax-table

  ;; Set up font-lock
  (setq-local font-lock-defaults '(nex-font-lock-keywords))

  ;; Comments
  (setq-local comment-start "-- ")
  (setq-local comment-end "")
  (setq-local comment-start-skip "--+\\s-*")

  ;; Render syntactic comments (and their "--" delimiter) in the custom gray
  ;; `nex-comment-face' rather than the theme's default comment color.
  (setq-local font-lock-comment-face 'nex-comment-face)
  (setq-local font-lock-comment-delimiter-face 'nex-comment-face)

  ;; Indentation
  (setq-local indent-line-function 'nex-indent-line)
  (setq-local indent-region-function #'nex-indent-region)
  (setq-local tab-width nex-indent-offset)
  (setq-local indent-tabs-mode nil)  ; Use spaces, not tabs

  ;; Navigation
  (setq-local beginning-of-defun-function 'nex-beginning-of-defun)
  (setq-local end-of-defun-function 'nex-end-of-defun)

  ;; Imenu
  (setq-local imenu-generic-expression nex-imenu-generic-expression)
  (setq-local imenu-case-fold-search nil)

  ;; Electric indentation
  (when (boundp 'electric-indent-chars)
    (setq-local electric-indent-chars
                (append '(?\n) electric-indent-chars))))

;;;###autoload
(add-to-list 'auto-mode-alist '("\\.nex\\'" . nex-mode))

;;;; Diagnostics

(defun nex-diagnose ()
  "Diagnose common issues with nex-mode setup."
  (interactive)
  (with-output-to-temp-buffer "*Nex Diagnostics*"
    (princ "=== Nex Mode Diagnostics ===\n\n")

    ;; Check current mode
    (princ (format "Current major mode: %s\n" major-mode))
    (princ (format "Expected: nex-mode\n\n"))

    ;; Check if nex-mode is loaded
    (princ (format "nex-mode loaded: %s\n" (featurep 'nex-mode)))

    ;; Check auto-mode-alist
    (princ "\nAuto-mode-alist entries for .nex:\n")
    (dolist (entry auto-mode-alist)
      (when (and (stringp (car entry))
                 (string-match-p "\\.nex" (car entry)))
        (princ (format "  %s -> %s\n" (car entry) (cdr entry)))))

    ;; Check keybinding
    (princ "\nKey binding for C-c C-c:\n")
    (princ (format "  Global: %s\n" (key-binding (kbd "C-c C-c"))))
    (princ (format "  Local: %s\n" (local-key-binding (kbd "C-c C-c"))))

    ;; Check REPL configuration
    (princ "\nREPL Configuration:\n")
    (princ (format "  Program: %s\n" nex-repl-program))
    (princ (format "  Arguments: %s\n" nex-repl-arguments))

    ;; Check if nex is in PATH
    (princ "\nNex executable:\n")
    (let ((nex-path (executable-find "nex")))
      (if nex-path
          (princ (format "  Found: %s\n" nex-path))
        (princ "  NOT FOUND - nex is not in your PATH!\n")))

    (princ "\n=== Recommendations ===\n\n")
    (unless (eq major-mode 'nex-mode)
      (princ "❌ Current mode is not nex-mode!\n")
      (princ "   Solution: Run M-x nex-mode to switch to nex-mode\n\n"))

    (unless (executable-find "nex")
      (princ "❌ nex executable not found in PATH!\n")
      (princ "   Solution: Install nex or add it to your PATH\n\n"))

    (when (eq major-mode 'nex-mode)
      (princ "✅ nex-mode is active\n"))

    (when (executable-find "nex")
      (princ "✅ nex executable found\n"))))

;;;; Language server

;; `nex lsp' gives eglot errors as you type, formatting (`eglot-format')
;; and an outline (imenu).  Start it with M-x eglot in a Nex buffer, or
;; every time with (add-hook 'nex-mode-hook #'eglot-ensure).
(with-eval-after-load 'eglot
  (when (boundp 'eglot-server-programs)
    (add-to-list 'eglot-server-programs
                 `(nex-mode . (,nex-repl-program "lsp")))))

;;;; Footer

(provide 'nex-mode)

;;; nex-mode.el ends here
