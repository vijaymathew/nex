# A Datalog Engine in Nex

Datalog is a small language of facts and rules: state who follows whom on a
social network, give rules for what follows from that (whose posts reach
whom, who might like to follow whom), and ask questions. This project is a
Datalog engine written in Nex. It reads a program, checks it, works out
every fact its rules imply, answers questions, and explains its answers.

```
nex datalog.nex social.dl                     # answer its questions, then read more
nex datalog.nex social.dl "friends(ann, Who)."
nex datalog.nex social.dl "why suggest(ann, cy)."
nex checks.nex                                # 29 checks of everything
```

```
$ nex datalog.nex social.dl "friends(ann, Who)."
social.dl: 7 facts given, 27 derived in 6 rounds.
?- sees(ann, Who).
   Who = ann
   Who = bo
   Who = cy
   Who = dee
   Who = eli
   Who = fay
?- suggest(ann, Who).
   Who = cy
Who = bo
```

```
$ nex datalog.nex social.dl "why suggest(ann, cy)."
...
suggest(ann, cy)    by suggest(X, Z) :- follows(X, Y), follows(Y, Z), X != Z, not follows(X, Z).
  follows(ann, bo)    (given)
  follows(bo, cy)    (given)
  ann != cy
  not follows(ann, cy)    (no such fact)
```

No library is used beyond Nex's own. Everything is written out: the lexer and
a recursive-descent parser, matching and joins, naive and semi-naive
evaluation to a fixpoint, the safety check on rules, negation evaluated one
stratum at a time, an index, and explanations. Contracts check the engine as
it runs: both evaluators are held to the same fixpoint postcondition, an
index must offer every fact a pattern could match, and every explanation is
checked against the rules before it is printed.

The tutorial `tutorial/datalog_tutorial.pdf` builds the whole engine step by
step.

## The language

```
follows(ann, bo).                                 % a fact
sees(X, Z) :- follows(X, Y), sees(Y, Z).          % a rule
in_common(X, Y) :- follows(X, Z), follows(Y, Z), X != Y.
can_read(U, F) :- user(U), file(F), not banned(U).
?- sees(ann, Who).                                % a question
```

Names starting with a lower-case letter are constants and predicates; names
starting with a capital letter (or `_`) are variables. Rules may test
values (`=`, `!=`, `<`, `<=`, `>`, `>=`) and negate atoms with
`not`. A rule must be *safe* (every variable bound by a positive atom, before
any test or negation that uses it), and a program must be *stratified* (no
predicate may depend on its own negation).

At the prompt, type a question (`sees(ann, X)`), `why` and a fact,
`:facts`, `:stats`, or `:quit`.

## Files

| File | What it does |
|---|---|
| `terms.nex` | Terms, atoms, literals, rules and programs; the safety check |
| `lexer.nex` | Cutting Datalog text into tokens |
| `parser.nex` | Building a program from the tokens, with errors that give the line |
| `bindings.nex` | Giving variables values; matching atoms against facts; tests |
| `database.nex` | Relations and the database, and their indexed versions |
| `evaluate.nex` | Joins, and naive and semi-naive evaluation to a fixpoint |
| `query.nex` | Answering questions |
| `analysis.nex` | Checking a program; stratification; evaluating a program in strata |
| `explain.nex` | Remembering how each fact was derived, and printing why it holds |
| `datalog.nex` | The command line |
| `checks.nex` | Executable checks of all of the above |
| `social.dl`, `flights.dl`, `access.dl`, `chain.dl` | Sample programs |

## The tutorial

The tutorial's LaTeX source, `tutorial/tutorial.tex` and the chapters it
inputs, is also the source of the code: every line of every `.nex` file
appears in it, in the order a reader adds it.

```bash
cd tutorial
python3 build.py            # rebuild the code from the tutorial, run every
                            # program, compare with ../*.nex, typeset the PDF
python3 build.py --no-pdf   # everything except typesetting
python3 build.py --pdf-only # only typeset, using the last build's outputs
python3 build.py --sync     # also copy the tutorial's code into the project
```

The programs run with the `nex` on the PATH, or with the command in the
`NEX` environment variable (`NEX=../../../bin/nex` for this checkout's own).
Building the PDF needs `pdflatex` (TeX Live). Every output in it comes from
running the code as printed.
