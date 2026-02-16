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
    : CLASS IDENTIFIER genericParams?
      inheritClause?
      classBody
      invariantClause?
      END
    ;

genericParams
    : '[' genericParam (',' genericParam)* ']'
    ;

genericParam
    : IDENTIFIER (ARROW IDENTIFIER)?
    ;

classBody
    : (featureSection | constructorSection)*
    ;

inheritClause
    : INHERIT inheritEntry (',' inheritEntry)*
    ;

inheritEntry
    : IDENTIFIER renameClause? redefineClause? END
    ;

renameClause
    : RENAME renameMapping+
    ;

renameMapping
    : IDENTIFIER AS IDENTIFIER
    ;

redefineClause
    : REDEFINE IDENTIFIER+
    ;

featureSection
    : visibilityModifier? FEATURE featureMember+
    ;

visibilityModifier
    : PRIVATE
    | '[' IDENTIFIER (',' IDENTIFIER)* ']'
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
    : IDENTIFIER '(' paramList? ')' requireClause? DO block ensureClause? END
    ;

methodDecl
    : IDENTIFIER ('(' paramList? ')')? (':' type)? requireClause? DO block ensureClause? END
    ;

paramList
    : param (',' param)*
    ;

param
    : IDENTIFIER (',' IDENTIFIER)* ':' type
    ;

type
    : INTEGER_TYPE
    | INTEGER64_TYPE
    | REAL_TYPE
    | DECIMAL_TYPE
    | CHAR_TYPE
    | BOOLEAN_TYPE
    | STRING_TYPE
    | IDENTIFIER typeArgs?
    ;

typeArgs
    : '[' type (',' type)* ']'
    ;

requireClause
    : REQUIRE assertion+
    ;

ensureClause
    : ENSURE assertion+
    ;

assertion
    : IDENTIFIER ':' expression
    ;

invariantClause
    : INVARIANT assertion+
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
    | localVarDecl
    | scopedBlock
    | ifStatement
    | loopStatement
    ;

scopedBlock
    : DO block END
    ;

ifStatement
    : IF expression THEN block ELSE block END
    ;

loopStatement
    : FROM block invariantClause? variantClause? UNTIL expression DO block END
    ;

variantClause
    : VARIANT expression
    ;

assignment
    : IDENTIFIER ASSIGN expression
    ;

localVarDecl
    : LET IDENTIFIER (':' type)? ASSIGN expression
    ;

methodCall
    : (IDENTIFIER '.')? IDENTIFIER ('(' argumentList? ')')?
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
    | postfix              # postfixExpr
    ;

postfix
    : primary ('[' expression ']')*
    ;

primary
    : literal
    | createExpression
    | IDENTIFIER
    | methodCall
    | '(' expression ')'
    ;

createExpression
    : CREATE IDENTIFIER ('.' IDENTIFIER '(' argumentList? ')')?
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
PRIVATE      : 'private';
CONSTRUCTORS : 'constructors';
INHERIT      : 'inherit';
RENAME       : 'rename';
REDEFINE     : 'redefine';
AS           : 'as';
DO           : 'do';
END          : 'end';
LET          : 'let';
CREATE       : 'create';
IF           : 'if';
THEN         : 'then';
ELSE         : 'else';
FROM         : 'from';
UNTIL        : 'until';
VARIANT      : 'variant';
REQUIRE      : 'require';
ENSURE       : 'ensure';
INVARIANT    : 'invariant';
AND          : 'and';
OR           : 'or';

// Type keywords
INTEGER_TYPE   : 'Integer';
INTEGER64_TYPE : 'Integer64';
REAL_TYPE      : 'Real';
DECIMAL_TYPE   : 'Decimal';
CHAR_TYPE      : 'Char';
BOOLEAN_TYPE   : 'Boolean';
STRING_TYPE    : 'String';

TRUE         : 'true';
FALSE        : 'false';

ASSIGN       : ':=';
ARROW        : '->';

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
