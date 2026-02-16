grammar nexlang;

/*
 * =========================
 *        PARSER RULES
 * =========================
 */

program
    : classDecl+ EOF
    | methodCall+ EOF
    ;

classDecl
    : CLASS IDENTIFIER
      classBody
      END
    ;

classBody
    : (featureSection | constructorSection)*
    ;

featureSection
    : FEATURE featureMember+
    ;

constructorSection
    : CONSTRUCTORS constructorDecl+
    ;

featureMember
    : fieldDecl
    | methodDecl
    ;

fieldDecl
    : IDENTIFIER ':' type
    ;

constructorDecl
    : IDENTIFIER '(' paramList? ')' DO block END
    ;

methodDecl
    : IDENTIFIER '(' paramList? ')' DO block END
    ;

paramList
    : param (',' param)*
    ;

param
    : IDENTIFIER ':' type
    ;

type
    : IDENTIFIER
    | BOOLEAN
    ;

/*
 * =========================
 *         STATEMENTS
 * =========================
 */

block
    : statement*
    ;

statement
    : assignment
    | methodCall
    ;

assignment
    : IDENTIFIER ASSIGN expression
    ;

methodCall
    : (IDENTIFIER '.')? IDENTIFIER '(' argumentList? ')'
    ;

argumentList
    : expression (',' expression)*
    ;

/*
 * =========================
 *        EXPRESSIONS
 * =========================
 */

expression
    : logicalOr
    ;

logicalOr
    : logicalAnd (OR logicalAnd)*
    ;

logicalAnd
    : equality (AND equality)*
    ;

equality
    : comparison ((EQUAL | NOTEQUAL) comparison)*
    ;

comparison
    : addition ((LT | LTE | GT | GTE) addition)*
    ;

addition
    : multiplication ((PLUS | MINUS) multiplication)*
    ;

multiplication
    : unary ((STAR | DIV) unary)*
    ;

unary
    : MINUS unary          # unaryMinus
    | primary              # primaryExpr
    ;

primary
    : literal
    | IDENTIFIER
    | methodCall
    | '(' expression ')'
    ;

literal
    : integerLiteral
    | realLiteral
    | charLiteral
    | booleanLiteral
    | STRING
    | arrayLiteral
    | mapLiteral
    ;

/*
 * =========================
 *       LITERALS
 * =========================
 */

integerLiteral
    : INTEGER
    ;

realLiteral
    : REAL
    ;

charLiteral
    : CHAR_LITERAL
    ;

booleanLiteral
    : TRUE
    | FALSE
    ;

arrayLiteral
    : '[' (expression (',' expression)*)? ']'
    ;

mapLiteral
    : '{' (mapEntry (',' mapEntry)*)? '}'
    ;

mapEntry
    : (STRING | IDENTIFIER) ':' expression
    ;

/*
 * =========================
 *         LEXER RULES
 * =========================
 */

CLASS        : 'class';
FEATURE      : 'feature';
CONSTRUCTORS : 'constructors';
DO           : 'do';
END          : 'end';
AND          : 'and';
OR           : 'or';

BOOLEAN      : 'Boolean';

TRUE         : 'true';
FALSE        : 'false';

ASSIGN       : ':=';

PLUS         : '+';
MINUS        : '-';
STAR         : '*';
DIV          : '/';

EQUAL        : '=';
NOTEQUAL     : '/=';
LT           : '<';
LTE          : '<=';
GT           : '>';
GTE          : '>=';

/*
 * -------------------------
 * Numeric Literals
 * -------------------------
 */

/*
 * Real numbers:
 *  - optional integer part
 *  - required dot
 *  - optional fractional part
 *  - optional exponent
 *
 * Examples:
 *  3.1415
 *  0.6
 *  12.e-3
 *  .5
 *  10.
 */
REAL
    : DIGITS? '.' DIGITS? EXPONENT?
    ;

/*
 * Integer:
 *  Sequence of digits.
 *  Sign handled in parser as unary minus.
 */
INTEGER
    : DIGITS
    ;

fragment DIGITS
    : [0-9]+
    ;

fragment EXPONENT
    : [eE] [+\-]? DIGITS
    ;

/*
 * -------------------------
 * Character Literals
 * -------------------------
 *
 * #A
 * #b
 * #65  (unicode code point)
 */
CHAR_LITERAL
    : '#' ( [a-zA-Z] | DIGITS )
    ;

/*
 * -------------------------
 * String
 * -------------------------
 */
STRING
    : '"' (~["\\] | '\\' .)* '"'
    ;

/*
 * -------------------------
 * Identifiers
 * -------------------------
 */
IDENTIFIER
    : [a-zA-Z_][a-zA-Z_0-9]*
    ;

/*
 * -------------------------
 * Comments & Whitespace
 * -------------------------
 */
COMMENT
    : '--' ~[\r\n]* -> skip
    ;

WS
    : [ \t\r\n]+ -> skip
    ;
