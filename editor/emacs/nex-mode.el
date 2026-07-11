;;; nex-mode.el --- Major mode for editing Nex programming language files -*- lexical-binding: t; -*-

;; Copyright (C) 2026 Nex Contributors

;; Author: Nex Contributors
;; Keywords: languages, nex, design-by-contract
;; Version: 0.2.0
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
    "let" "as" "and" "or" "not" "fn" "deferred" "convert" "to"
    "old" "create" "private" "note" "with" "import" "intern" "function"
    "raise" "rescue" "retry" "repeat" "across" "case" "of"
    "spawn" "select" "timeout" "declare" "sealed" "once" "match" "type"
    "union" "where")
  "Nex language keywords.")

(defconst nex-types
  '("Integer" "Integer64" "Real" "Decimal" "Char" "Boolean" "String"
    "Array" "Map" "Set" "Task" "Channel" "Any" "Void" "Function" "Cursor")
  "Nex built-in types.")

(defconst nex-constants
  '("true" "false" "nil")
  "Nex language constants.")

(defconst nex-builtins
  '("print" "println" "result" "exception" "sleep"
    "type_of" "type_is" "await_any" "await_all")
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

    ;; Assignment operator
    (":=" . font-lock-keyword-face)

    ;; Numbers
    ("\\<[0-9]+\\(\\.[0-9]+\\)?\\>" . font-lock-constant-face)
    )
  "Font lock keywords for Nex mode.")

;;;; Indentation

(defconst nex-method-signature-re
  "\\b[a-z_][a-z0-9_]*\\s-*\\(([^)]*)\\)?\\s-*\\(?::\\s-*[A-Z][a-zA-Z0-9_]*\\)?\\s-*$"
  "Regexp matching a Nex method/feature declaration line (name, optional args, optional return type).")

(defconst nex-section-keyword-re
  (regexp-opt '("class" "union" "feature" "create" "inherit" "invariant"
                "do" "end" "require" "ensure" "rescue" "from" "until"
                "if" "then" "else" "elseif" "when" "select" "timeout"
                "repeat" "across" "case" "of" "deferred" "sealed" "once"
                "private" "note" "import" "intern" "spawn" "function" "fn")
              'words)
  "Regexp matching Nex keywords that cannot be method names.")

(defun nex-bracket-continuation-indent ()
  "Indentation column when point's line continues a multi-line bracket literal.
Return the column to indent to when the start of the current line is inside an
unclosed \"[\", \"{\", or \"(\" (array/map literals, multi-line argument lists),
otherwise nil.  Contents indent one level past the line that opened the bracket;
a line that itself begins with the matching close bracket aligns with that
opening line.  Uses the syntax parser, so brackets inside strings and comments
are correctly ignored."
  (save-excursion
    (beginning-of-line)
    (let ((open (nth 1 (syntax-ppss (point)))))
      (when open
        (let ((open-line-indent
               (save-excursion (goto-char open) (current-indentation))))
          (if (looking-at "[ \t]*[]})]")
              open-line-indent
            (+ open-line-indent nex-indent-offset)))))))

(defun nex-indent-line ()
  "Indent current line as Nex code."
  (interactive)
  (let ((indent-col 0)
        (current-indent (current-indentation)))
    (save-excursion
      (beginning-of-line)
      (let ((bracket-indent (and (not (bobp)) (nex-bracket-continuation-indent))))
        (cond
         ((bobp)
          (setq indent-col 0))
         ;; Inside a multi-line [ ], { }, or ( ): indent relative to the line
         ;; that opened the bracket. Takes precedence over keyword rules so
         ;; array/map literal elements line up correctly.
         (bracket-indent
          (setq indent-col bracket-indent))
         (t
          (let ((prev-indent (nex-previous-line-indent))
                (should-increase (nex-should-increase-indent))
                (should-decrease (nex-should-decrease-indent))
                (is-class-level (nex-is-class-level-keyword))
                (is-note-after-method (nex-is-note-after-method))
                (is-contract-after-method (nex-is-contract-after-method))
                (is-loop-do (nex-is-loop-do))
                (is-loop-clause (nex-loop-clause-keyword-p)))
            (setq indent-col
                  (cond
                   ;; Class-level keywords: align with class (usually 0)
                   (is-class-level
                    (nex-find-class-indent))
                   ;; 'note' after method name: indent extra level
                   (is-note-after-method
                    (+ prev-indent nex-indent-offset))
                   ;; require/ensure/do after method: align with method
                   (is-contract-after-method
                    (nex-find-method-indent))
                   ;; Loop header clauses ('until'/'variant'/loop 'invariant')
                   ;; and the loop 'do' all align with their 'from'.  Anchoring
                   ;; to the matching 'from' (rather than the previous line)
                   ;; makes this form-independent: the clause keyword sits at
                   ;; the loop level whether its content is on its own line
                   ;; (block form) or inline ("until cond", "variant expr").
                   ((or is-loop-clause is-loop-do)
                    (or (nex-loop-from-indent) prev-indent))
                   ;; Closing keywords: decrease indent
                   (should-decrease
                    (max 0 (- prev-indent nex-indent-offset)))
                   ;; Opening keywords on previous line: increase indent
                   (should-increase
                    (+ prev-indent nex-indent-offset))
                   ;; Default: same as previous line
                   (t prev-indent))))))))

    ;; Indent the line
    (indent-line-to indent-col)

    ;; Move point to indentation if before it
    (when (< (current-column) indent-col)
      (move-to-column indent-col))))

(defun nex-previous-line-indent ()
  "Return the indentation of the previous non-blank line."
  (save-excursion
    (forward-line -1)
    (while (and (not (bobp))
                (looking-at "^\\s-*$"))
      (forward-line -1))
    (current-indentation)))

(defun nex-should-increase-indent ()
  "Return t if the previous line should increase indentation."
  (save-excursion
    (forward-line -1)
    ;; A 'union' declaration opener indents its variant list one level, the way
    ;; 'feature' indents a class body.  The line ends with the union's name
    ;; rather than a keyword, so it is matched at the start of the line.
    (if (save-excursion (beginning-of-line) (looking-at "[ \t]*union\\b"))
        t
      (end-of-line)
      (skip-chars-backward " \t")
      (or
       ;; Keywords that start indented blocks
       (looking-back
        (regexp-opt '("class" "do" "then" "else" "elseif" "require" "ensure"
                      "from" "until" "inherit" "invariant" "variant"
                      "rescue" "of" "select" "when" "timeout")
                    'words)
        (line-beginning-position))
     ;; Section keywords that contain items (feature, create)
     (looking-back
      (regexp-opt '("feature" "create") 'words)
      (line-beginning-position))
     ;; Handle "private feature" specially
     (looking-back "\\bprivate\\s-+feature\\b" (line-beginning-position))))))

(defun nex-in-contract-block-p ()
  "Return t if point is in a contract block (after require/ensure)."
  (save-excursion
    (beginning-of-line)
    ;; Search backward for require/ensure or do/end
    (let ((start-pos (point)))
      (catch 'result
        (while (re-search-backward "\\b\\(require\\|ensure\\|do\\|end\\)\\b" nil t)
          (let ((keyword (match-string 1)))
            (cond
             ;; Found require or ensure before do - we're in contract block
             ((or (string= keyword "require") (string= keyword "ensure"))
              (throw 'result t))
             ;; Found do or end first - not in contract block
             ((or (string= keyword "do") (string= keyword "end"))
              (throw 'result nil)))))
        ;; Didn't find anything
        nil))))

(defun nex-in-from-block-p ()
  "Return t if point is between 'until' and 'do' in a from-until-do block."
  (save-excursion
    (beginning-of-line)
    (catch 'result
      (while (re-search-backward "\\b\\(until\\|do\\|end\\)\\b" nil t)
        (let ((keyword (match-string 1)))
          (cond
           ((string= keyword "until")
            (throw 'result t))
           (t
            (throw 'result nil)))))
      nil)))

(defun nex-is-loop-do ()
  "Return t if the current line is the loop 'do' of a from-until-do block.
That is, a 'do' reached from the loop's 'until' rather than a method body or a
contract block."
  (save-excursion
    (beginning-of-line)
    (skip-chars-forward " \t")
    (and (looking-at "\\bdo\\b")
         (nex-in-from-block-p))))

(defun nex-loop-from-indent ()
  "Return the indentation of the 'from' of the loop enclosing point, or nil.
Scans backward balancing 'from'/'end' so the matching opener is found even
across nested or preceding sibling loops; a class 'invariant' or any code not
inside a loop yields nil.  Matches inside strings/comments are ignored.  Works
for any loop-header line ('invariant'/'variant'/'until'/loop 'do') because none
of those are preceded by the loop's own 'end'."
  (save-excursion
    (beginning-of-line)
    (let ((depth 0))
      (catch 'found
        (while (re-search-backward "\\b\\(from\\|end\\)\\b" nil t)
          (unless (nth 8 (syntax-ppss))  ; skip 'from'/'end' in strings or comments
            (let ((kw (match-string 1)))
              (cond
               ((string= kw "end")
                (setq depth (1+ depth)))
               ((string= kw "from")
                (if (= depth 0)
                    (throw 'found (current-indentation))
                  (setq depth (1- depth))))))))
        nil))))

(defun nex-loop-clause-keyword-p ()
  "Return t if the current line starts a from-loop header clause that should
align with the loop's 'from': 'until', 'variant', or a loop (non-class)
'invariant'.  A class-level 'invariant' returns nil."
  (save-excursion
    (beginning-of-line)
    (skip-chars-forward " \t")
    (cond
     ((looking-at (regexp-opt '("until" "variant") 'words)) t)
     ;; 'invariant' is a loop clause only when enclosed by a 'from'; otherwise
     ;; it is a class invariant (handled by `nex-is-class-level-keyword').
     ((looking-at "\\binvariant\\b")
      (and (nex-loop-from-indent) t))
     (t nil))))

(defun nex-should-decrease-indent ()
  "Return t if the current line should decrease indentation."
  (save-excursion
    (beginning-of-line)
    (skip-chars-forward " \t")
    (or
     ;; 'end', 'else', 'elseif', 'rescue', 'when', 'timeout' close/open siblings
     (looking-at (regexp-opt '("end" "else" "elseif" "rescue" "when" "timeout") 'words))
     ;; 'do' aligns with require/ensure if inside contract block
     (and (looking-at "\\bdo\\b")
          (nex-in-contract-block-p)))
     ;; NB: loop header clauses ('until'/'variant'/loop 'invariant') and the
     ;; loop 'do' are NOT decreases.  They are handled separately in
     ;; `nex-indent-line' via `nex-loop-clause-keyword-p'/`nex-is-loop-do',
     ;; which align them with their 'from' rather than decreasing from the
     ;; previous line (which may be an inline clause already at the loop
     ;; level, in which case decreasing would overshoot).
     ))

(defun nex-is-class-level-keyword ()
  "Return t if the current line starts with a class-level keyword.
Class-level keywords should align with 'class' at column 0."
  (save-excursion
    (beginning-of-line)
    (skip-chars-forward " \t")
    ;; Skip 'private' if present
    (when (looking-at "\\bprivate\\s-+")
      (goto-char (match-end 0)))
    (and (looking-at (regexp-opt '("feature" "create" "inherit" "invariant") 'words))
         ;; A loop 'invariant' (enclosed by a 'from') is not class-level.  Use
         ;; the 'from'-balancing scan rather than `nex-in-from-block-p', which
         ;; only sees a loop 'invariant' written after 'until'.
         (not (and (looking-at "\\binvariant\\b")
                   (nex-loop-from-indent))))))

(defun nex-find-class-indent ()
  "Find the indentation level of the enclosing class.
Returns 0 if at top level or inside a class."
  (save-excursion
    (beginning-of-line)
    (if (re-search-backward "^\\s-*class\\s-+" nil t)
        ;; Found a class, return its indentation
        (current-indentation)
      ;; No class found, return 0 (top level)
      0)))

(defun nex-is-note-after-method ()
  "Return t if current line is 'note' after a method name."
  (save-excursion
    (beginning-of-line)
    (skip-chars-forward " \t")
    (when (looking-at "\\bnote\\b")
      ;; Check if previous non-empty line is a method name
      (forward-line -1)
      (while (and (not (bobp)) (looking-at "^\\s-*$"))
        (forward-line -1))
      (end-of-line)
      (skip-chars-backward " \t")
      ;; Method name: lowercase identifier with optional parens and optional return type
      (looking-back nex-method-signature-re
                    (line-beginning-position)))))

(defun nex-is-contract-after-method ()
  "Return t if current line is require/ensure/do after a method name."
  (save-excursion
    (beginning-of-line)
    (skip-chars-forward " \t")
    (when (looking-at "\\b\\(require\\|ensure\\|do\\)\\b")
      (let ((cur-kw (match-string 1)))
        (if (string= cur-kw "ensure")
            ;; 'ensure' is only ever a routine postcondition. It follows the
            ;; routine body and aligns with the method (its matching 'do'),
            ;; even when the preceding line is a nested block's 'end' (which
            ;; the backward scan below would otherwise stop on, mis-anchoring
            ;; the clause to the body indentation).
            t
          (nex-is-contract-after-method-1 cur-kw))))))

(defun nex-is-contract-after-method-1 (cur-kw)
  "Helper for `nex-is-contract-after-method' for the require/body-do case.
CUR-KW is the contract keyword on the current line."
  (save-excursion
    (beginning-of-line)
    (skip-chars-forward " \t")
    ;; Search backward for method name, skipping note, empty lines, and body statements
    (forward-line -1)
    (while (and (not (bobp))
                (progn
                  (beginning-of-line)
                  (skip-chars-forward " \t")
                  (or (looking-at "^\\s-*$")
                      (looking-at "\\bnote\\b")
                      ;; Skip body statements (assignments, calls, etc) when looking for method
                      ;; Stop at loop-starting keywords that own their own do..end
                      (and (not (looking-at "\\b\\(do\\|require\\|ensure\\|feature\\|create\\|class\\|end\\|across\\|from\\|repeat\\|with\\)\\b"))
                           (not (looking-at nex-method-signature-re))))))
      (forward-line -1))
    ;; Check if we found a method/constructor name or another contract keyword
    (beginning-of-line)
    (skip-chars-forward " \t")
    (or (and (looking-at nex-method-signature-re)
             (not (looking-at nex-section-keyword-re)))
        (looking-at "\\brequire\\b")
        ;; Landing on a 'do' anchors a method body: it lines up an 'ensure'
        ;; (or a body 'do' that follows 'require') with the method. But a 'do'
        ;; that opens a nested scoped block follows the enclosing body 'do',
        ;; and must indent one level deeper instead of aligning to the method.
        (and (looking-at "\\bdo\\b")
             (not (string= cur-kw "do"))))))

(defun nex-find-method-indent ()
  "Find the indentation of the method that owns the current contract."
  (save-excursion
    (beginning-of-line)
    ;; Search backward for method name, skipping note, empty lines, and body statements
    (forward-line -1)
    (while (and (not (bobp))
                (progn
                  (beginning-of-line)
                  (skip-chars-forward " \t")
                  (or (looking-at "^\\s-*$")
                      (looking-at "\\bnote\\b")
                      ;; Skip body statements and section keywords when looking for method indent
                      (or (looking-at nex-section-keyword-re)
                          (and (not (looking-at nex-method-signature-re))
                               (not (looking-at "\\b\\(do\\|require\\|ensure\\)\\b")))))))
      (forward-line -1))
    ;; Should be at method name or contract keyword line now
    (current-indentation)))

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

;;;; Footer

(provide 'nex-mode)

;;; nex-mode.el ends here
