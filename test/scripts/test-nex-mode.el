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

(defun nex-test--reindent (text)
  "Return TEXT after stripping indentation and reindenting in nex-mode."
  (with-temp-buffer
    (nex-mode)
    ;; Insert with leading whitespace removed so indentation is computed fresh.
    (dolist (line (split-string text "\n"))
      (insert (replace-regexp-in-string "^[ \t]+" "" line) "\n"))
    (indent-region (point-min) (point-max))
    (string-trim-right (buffer-string))))

(ert-deftest nex-indent-from-loop-variant-invariant ()
  "Loop 'variant'/'invariant' align with 'from'/'until', not class level."
  (should (string=
           (nex-test--reindent
            "function f() do\nfrom\nlet i := 0\nuntil\ni >= steps\nvariant\ni - 1\ninvariant\nin_range: i >= 0\ndo\nprint(i)\nend\nend")
           (string-join
            '("function f() do"
              "  from"
              "    let i := 0"
              "  until"
              "    i >= steps"
              "  variant"
              "    i - 1"
              "  invariant"
              "    in_range: i >= 0"
              "  do"
              "    print(i)"
              "  end"
              "end")
            "\n"))))

(ert-deftest nex-indent-from-loop-inline-until ()
  "Inline loop-header clauses ('variant expr', 'until cond') align with 'from'.
The clause keywords and the loop 'do'/'end' all sit at the loop level even when
their content is inline (regression: 'do'/'end' used to over-decrease to 0, and
the inline clauses landed at the wrong column)."
  (should (string=
           (nex-test--reindent
            "function factorial_iter(product, counter, max_count: Integer): Integer\ndo\nfrom\nvariant max_count - counter\nuntil counter > max_count\ndo\nproduct := counter * product\ncounter := counter + 1\nend\nresult := product\nend")
           (string-join
            '("function factorial_iter(product, counter, max_count: Integer): Integer"
              "do"
              "  from"
              "  variant max_count - counter"
              "  until counter > max_count"
              "  do"
              "    product := counter * product"
              "    counter := counter + 1"
              "  end"
              "  result := product"
              "end")
            "\n"))))

(ert-deftest nex-indent-from-loop-inline-invariant-before-until ()
  "A loop 'invariant'/'variant' written before 'until' aligns with 'from', not
class level.  Bare-'from' init statements still indent one level under 'from'."
  (should (string=
           (nex-test--reindent
            "function f(): Integer\ndo\nfrom\nlet i := 0\ninvariant a: a > 1\nvariant max_count - counter\nuntil counter > max_count\ndo\nresult := result + 1\nend\nresult := i\nend")
           (string-join
            '("function f(): Integer"
              "do"
              "  from"
              "    let i := 0"
              "  invariant a: a > 1"
              "  variant max_count - counter"
              "  until counter > max_count"
              "  do"
              "    result := result + 1"
              "  end"
              "  result := i"
              "end")
            "\n"))))

(ert-deftest nex-indent-class-invariant-stays-class-level ()
  "A class 'invariant' still aligns with the class, not a loop."
  (should (string=
           (nex-test--reindent
            "class Account\nfeature\nbalance: Integer\ninvariant\nnon_negative: balance >= 0\nend")
           (string-join
            '("class Account"
              "feature"
              "  balance: Integer"
              "invariant"
              "  non_negative: balance >= 0"
              "end")
            "\n"))))

(ert-deftest nex-indent-nested-do-rescue-block ()
  "A nested do..rescue..end block indents one level deeper than the enclosing body."
  (should (string=
           (nex-test--reindent
            "function connect_with_retry(): String\ndo\nlet attempts := 0\ndo\nattempts := attempts + 1\nif attempts < 3 then\nraise \"temporary connection error\"\nend\nresult := \"connected\"\nrescue\nif attempts < 3 then\nretry\nelse\nraise exception\nend\nend\nend")
           (string-join
            '("function connect_with_retry(): String"
              "do"
              "  let attempts := 0"
              "  do"
              "    attempts := attempts + 1"
              "    if attempts < 3 then"
              "      raise \"temporary connection error\""
              "    end"
              "    result := \"connected\""
              "  rescue"
              "    if attempts < 3 then"
              "      retry"
              "    else"
              "      raise exception"
              "    end"
              "  end"
              "end")
            "\n"))))

(ert-deftest nex-indent-match-clauses ()
  "Bare match clauses (no leading keyword — see `nexlang.g4' `matchClause')
indent one level inside their 'match', and the closing 'end' aligns with the
'match' (regression: without special handling, a clause header was treated as
a plain continuation of the previous line — the preceding clause's body —
which flattened every clause to the 'match' column and dragged the 'end' with
it; this is the same flattening bug the old 'when' keyword used to guard
against before match clauses lost it)."
  (should (string=
           (nex-test--reindent
            "function describe(o: Order): String\ndo\nmatch o of\nDraft as d then\nresult := \"draft\"\nPlaced as p then\nresult := p.total.to_string\nend\nend")
           (string-join
            '("function describe(o: Order): String"
              "do"
              "  match o of"
              "    Draft as d then"
              "      result := \"draft\""
              "    Placed as p then"
              "      result := p.total.to_string"
              "  end"
              "end")
            "\n"))))

(ert-deftest nex-indent-match-else-and-nested-block ()
  "A match 'else' aligns with the bare clauses, and a clause body wrapped in
'do ... end' (required once it is more than one statement — a match clause's
body is exactly one statement, see `nexlang.g4' `matchClause') keeps its own
'end' — the clause that follows still anchors to the 'match', not to the
nested block."
  (should (string=
           (nex-test--reindent
            "function f(r: Result): Integer\ndo\nmatch r of\nOk as ok then\ndo\nif ok.value > 0 then\nprint(ok.value)\nend\nprint(\"done\")\nend\nelse\nprint(\"not ok\")\nend\nend")
           (string-join
            '("function f(r: Result): Integer"
              "do"
              "  match r of"
              "    Ok as ok then"
              "      do"
              "        if ok.value > 0 then"
              "          print(ok.value)"
              "        end"
              "        print(\"done\")"
              "      end"
              "    else"
              "      print(\"not ok\")"
              "  end"
              "end")
            "\n"))))

(ert-deftest nex-indent-nested-match ()
  "A 'match' inside a clause body (wrapped in 'do ... end' alongside the
statement that follows it, since a clause holds exactly one statement) owns
its own clauses and 'end'; the outer clause list resumes at the outer
'match' level afterwards."
  (should (string=
           (nex-test--reindent
            "function outer(o: Order): String\ndo\nmatch o of\nOk as k then\ndo\nmatch k.v of\nDraft as d then\nprint(\"d\")\nend\nprint(\"after\")\nend\nErr as e then\nprint(\"e\")\nend\nend")
           (string-join
            '("function outer(o: Order): String"
              "do"
              "  match o of"
              "    Ok as k then"
              "      do"
              "        match k.v of"
              "          Draft as d then"
              "            print(\"d\")"
              "        end"
              "        print(\"after\")"
              "      end"
              "    Err as e then"
              "      print(\"e\")"
              "  end"
              "end")
            "\n"))))

(ert-deftest nex-indent-select-clauses ()
  "'select' takes the same clause skeleton as 'match': 'when' and 'timeout'
indent one level in, the 'end' aligns with 'select'."
  (should (string=
           (nex-test--reindent
            "function f(): Integer\ndo\nselect\nwhen ch_a.receive as msg then\nprint(msg)\ntimeout 100 then\nprint(\"timed out\")\nend\nend")
           (string-join
            '("function f(): Integer"
              "do"
              "  select"
              "    when ch_a.receive as msg then"
              "      print(msg)"
              "    timeout 100 then"
              "      print(\"timed out\")"
              "  end"
              "end")
            "\n"))))

(ert-deftest nex-indent-case-else ()
  "A 'case' 'else' aligns with the literal clauses rather than de-denting to the
'case' itself."
  (should (string=
           (nex-test--reindent
            "function f(d: String): Integer\ndo\ncase d of\n\"up\" then print(\"going up\")\n\"down\" then print(\"going down\")\nelse print(\"standing still\")\nend\nend")
           (string-join
            '("function f(d: String): Integer"
              "do"
              "  case d of"
              "    \"up\" then print(\"going up\")"
              "    \"down\" then print(\"going down\")"
              "    else print(\"standing still\")"
              "  end"
              "end")
            "\n"))))

(ert-deftest nex-indent-standalone-do-block-with-create ()
  "A standalone do..rescue..end block indents its body, even when the first
statement is a 'create' creation instruction (regression: 'create X.f(...)'
was mistaken for the class-level 'create' clause and pinned to column 0)."
  (should (string=
           (nex-test--reindent
            "do\ncreate Account.open(neg)\nrescue\nprint(\"Expected error: \" + exception)\nend")
           (string-join
            '("do"
              "  create Account.open(neg)"
              "rescue"
              "  print(\"Expected error: \" + exception)"
              "end")
            "\n"))))

(ert-deftest nex-indent-create-clause-vs-instruction ()
  "The class-level 'create' clause aligns with the class and indents its feature
list, while a 'create' creation instruction in a method body indents with the
body."
  (should (string=
           (nex-test--reindent
            "class Account\ncreate\nmake\nfeature\nopen()\ndo\ncreate Path.make(\"x\")\nend\nend")
           (string-join
            '("class Account"
              "create"
              "  make"
              "feature"
              "  open()"
              "  do"
              "    create Path.make(\"x\")"
              "  end"
              "end")
            "\n"))))

(ert-deftest nex-indent-method-body-do-with-contracts ()
  "A method body do/require/ensure still align with the method, not deeper."
  (should (string=
           (nex-test--reindent
            "class Account\nfeature\nwithdraw(amount: Real)\nrequire\nenough: amount <= balance\ndo\nbalance := balance - amount\nensure\ndecreased: balance = old balance - amount\nend\nend")
           (string-join
            '("class Account"
              "feature"
              "  withdraw(amount: Real)"
              "  require"
              "    enough: amount <= balance"
              "  do"
              "    balance := balance - amount"
              "  ensure"
              "    decreased: balance = old balance - amount"
              "  end"
              "end")
            "\n"))))

(ert-deftest nex-indent-ensure-after-nested-end ()
  "An 'ensure' that follows nested block 'end's still aligns with the method,
so its closing 'end' lines up with the routine 'do'."
  (should (string=
           (nex-test--reindent
            "class Delivery\nfeature\nnext_stop(destination: String): String\nrequire\ndestination_non_empty: destination /= \"\"\ndo\nif current_location.title = destination then\nresult := current_location.title\nelse\nif loc /= nil then\nresult := current_location.title\nelse\nresult := \"UNREACHABLE\"\nend\nend\nensure\ndecision_returned: result /= \"\"\nend\nend")
           (string-join
            '("class Delivery"
              "feature"
              "  next_stop(destination: String): String"
              "  require"
              "    destination_non_empty: destination /= \"\""
              "  do"
              "    if current_location.title = destination then"
              "      result := current_location.title"
              "    else"
              "      if loc /= nil then"
              "        result := current_location.title"
              "      else"
              "        result := \"UNREACHABLE\""
              "      end"
              "    end"
              "  ensure"
              "    decision_returned: result /= \"\""
              "  end"
              "end")
            "\n"))))

(ert-deftest nex-indent-ensure-after-bare-call-statement ()
  "A body statement shaped like a receiverless call with no arguments --
`print()' -- must not be mistaken for the method's own signature line when
scanning backward to align a following 'ensure'.  Regression: both share the
shape matched by `nex-method-signature-re' (identifier, optional parens,
optional return type, end of line), which used to stop the backward scan at
`print()' and indent 'ensure' one level too deep."
  (should (string=
           (nex-test--reindent
            "class A\nfeature\nsome_method(a: Integer): Integer\ndo\nresult := a + 1\na := 100\nprint()\nensure\nincrement: result = a + 1\nend\nend")
           (string-join
            '("class A"
              "feature"
              "  some_method(a: Integer): Integer"
              "  do"
              "    result := a + 1"
              "    a := 100"
              "    print()"
              "  ensure"
              "    increment: result = a + 1"
              "  end"
              "end")
            "\n"))))

(ert-deftest nex-comment-quotes-are-not-strings ()
  "A \" or ' inside a -- comment must be parsed as comment text, not as the
start of a string.  Regression: re-setting ?- to plain punctuation stripped the
comment-start flags, so the string scanner mis-read quotes in comments."
  (with-temp-buffer
    (nex-mode)
    (insert "-- find a task's \"status\" by id\n")
    (insert "let s: String := \"real string\"\n")
    (font-lock-ensure)
    ;; Just past the apostrophe, inside the comment.
    (goto-char (point-min))
    (search-forward "task's")
    (should (nth 4 (syntax-ppss (point))))        ; in comment
    (should-not (nth 3 (syntax-ppss (point))))    ; not in string
    ;; Just past the embedded double quote, still inside the comment.
    (goto-char (point-min))
    (search-forward "\"status")
    (should (nth 4 (syntax-ppss (point))))        ; in comment
    (should-not (nth 3 (syntax-ppss (point))))    ; not in string
    ;; A genuine string literal still parses as a string.
    (goto-char (point-min))
    (search-forward "real str")
    (should (nth 3 (syntax-ppss (point))))        ; in string
    (should-not (nth 4 (syntax-ppss (point))))))  ; not in comment

(ert-deftest nex-indent-multiline-array-literal ()
  "Elements of a multi-line array literal indent one level past the line that
opens \"[\"; the closing \"]\" aligns with that line; lines after return to the
block level."
  (should (string=
           (nex-test--reindent
            "function main() do\nlet xs: Array [Integer] := [\n1,\n2,\n3\n]\nprint(xs)\nend")
           (string-join
            '("function main() do"
              "  let xs: Array [Integer] := ["
              "    1,"
              "    2,"
              "    3"
              "  ]"
              "  print(xs)"
              "end")
            "\n"))))

(ert-deftest nex-indent-multiline-map-literal ()
  "Entries of a multi-line map literal indent one level past the line that
opens \"{\"; the closing \"}\" aligns with that line."
  (should (string=
           (nex-test--reindent
            "function main() do\nlet m: Map [String, Integer] := {\n\"a\": 1,\n\"b\": 2\n}\nprint(m)\nend")
           (string-join
            '("function main() do"
              "  let m: Map [String, Integer] := {"
              "    \"a\": 1,"
              "    \"b\": 2"
              "  }"
              "  print(m)"
              "end")
            "\n"))))

(ert-deftest nex-indent-multiline-nested-array ()
  "A nested array literal inside a multi-line call indents relative to its own
opening \"[\"."
  (should (string=
           (nex-test--reindent
            "function build() do\nresult := create Graph.from_edges([\n[\"A\",\"B\"],\n[\"B\",\"C\"]\n])\nend")
           (string-join
            '("function build() do"
              "  result := create Graph.from_edges(["
              "    [\"A\",\"B\"],"
              "    [\"B\",\"C\"]"
              "  ])"
              "end")
            "\n"))))

(ert-deftest nex-indent-multiline-set-literal ()
  "Members of a multi-line set literal (#{ ... }) indent one level past the line
that opens it; the closing \"}\" aligns with that line. The \"#\" prefix does not
interfere with brace detection."
  (should (string=
           (nex-test--reindent
            "function main() do\nlet s: Set [String] := #{\n\"a\",\n\"b\"\n}\nprint(s)\nend")
           (string-join
            '("function main() do"
              "  let s: Set [String] := #{"
              "    \"a\","
              "    \"b\""
              "  }"
              "  print(s)"
              "end")
            "\n"))))

;;; test-nex-mode.el ends here
