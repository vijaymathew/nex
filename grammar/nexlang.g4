grammar nexlang;

options {
  language = Go;
}

@parser::header {
package parser
}

@lexer::header {
package lexer
}


start
  : ( statement+ )? EOF
  ;

statement
  : fnExpression
  ;

primaryExpression
  : fnExpression
  ;

fnExpression
  : 'fn' '(' (paramList)? ')' 'do' block 'end'
  ;

paramList
  : param (',' param)*
  ;

param
  : Identifier ':' Type
  ;

block
  : '{' statement* '}'
  ;

Identifier
  : [a-zA-Z_] [a-zA-Z_0-9]*
  ;

Type
  : Identifier
  ;

WS
  : [ \t\r\n]+ -> skip
  ;

