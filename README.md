# nex

A software modelling and implementation language.

Test the lexer in Chicken Scheme:

$ csi -R r7rs src/lexer.scm
> (nex-tokenize "x = 2#101 + 3.14e2 - $A \"hello \\\"world\"")