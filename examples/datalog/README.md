# A Datalog Engine in Nex

Datalog is a small language of facts and rules: state that Alice is Bob's
parent and Bob is Carol's, give a rule for what an ancestor is, and ask who
Alice's descendants are. This project is a Datalog engine written in Nex. It
reads a program, checks it, works out every fact its rules imply, answers
questions, and explains its answers.

```
nex datalog.nex family.dl                     # answer its questions, then read more
nex datalog.nex family.dl "sibling(bob, Who)."
nex datalog.nex family.dl "why cousin(carol, dan)."
nex checks.nex                                # 29 checks of everything
```

```
$ nex datalog.nex family.dl "sibling(bob, Who)."
family.dl: 6 facts given, 19 derived in 3 rounds.
?- ancestor(alice, Who).
   Who = beth
   Who = bob
   Who = carol
   Who = chris
   Who = dan
   Who = emma
?- cousin(carol, Who).
   Who = dan
Who = beth
```

```
$ nex datalog.nex family.dl "why cousin(carol, dan)."
...
cousin(carol, dan)    by cousin(X, Y) :- parent(P, X), parent(Q, Y), sibling(P, Q).
  parent(bob, carol)    (given)
  parent(beth, dan)    (given)
  sibling(bob, beth)    by sibling(X, Y) :- parent(P, X), parent(P, Y), X != Y.
    parent(alice, bob)    (given)
    parent(alice, beth)    (given)
    bob != beth
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
parent(alice, bob).                              % a fact
ancestor(X, Z) :- parent(X, Y), ancestor(Y, Z).  % a rule
sibling(X, Y) :- parent(P, X), parent(P, Y), X != Y.
can_read(U, F) :- user(U), file(F), not banned(U).
?- ancestor(alice, Who).                         % a question
```

Names starting with a lower-case letter are constants and predicates; names
starting with a capital letter (or `_`) are variables. Rules may test
values (`=`, `!=`, `<`, `<=`, `>`, `>=`) and negate atoms with
`not`. A rule must be *safe* (every variable bound by a positive atom, before
any test or negation that uses it), and a program must be *stratified* (no
predicate may depend on its own negation).

At the prompt, type a question (`ancestor(alice, X)`), `why` and a fact,
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
| `family.dl`, `flights.dl`, `access.dl`, `chain.dl` | Sample programs |

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
