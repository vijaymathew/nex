# Lending library

A small lending library: books are lent for a fixed number of days, and a
book returned late owes a fine for every day it is late, up to a maximum.

- `loans.nex`: a `Loan` (which book, who has it, the day it was lent) and
  when it is due back
- `fines.nex`: the fine policy
- `checks.nex`: executable checks. Run them with `nex checks.nex`.

Days are numbered: day 1 is the day the library opened.
