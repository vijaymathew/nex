grammar nexlang;

/*
 * =========================
 *        PARSER RULES
 * =========================
 */

program
    : (importStmt | internStmt | classDecl | functionDecl | statement)* EOF
    ;

importStmt
    : IMPORT IDENTIFIER ('.' IDENTIFIER)* (FROM STRING)?
    ;

internStmt
    : INTERN IDENTIFIER ('/' IDENTIFIER)* (AS IDENTIFIER)?
    ;

classDecl
    : DEFERRED? CLASS IDENTIFIER genericParams?
      noteClause?
      inheritClause?
      classBody
      invariantClause?
      END
    ;

functionDecl
    : FUNCTION IDENTIFIER '(' paramList? ')' (':' type)? noteClause? (requireClause? DO block ensureClause? rescueClause? END)?
    ;

genericParams
    : '[' genericParam (',' genericParam)* ']'
    ;

genericParam
    : QMARK? IDENTIFIER (ARROW IDENTIFIER)?
    ;

classBody
    : (featureSection | constructorSection)*
    ;

inheritClause
    : INHERIT inheritEntry (',' inheritEntry)*
    ;

inheritEntry
    : IDENTIFIER
    ;

featureSection
    : visibilityModifier? FEATURE featureMember+
    ;

visibilityModifier
    : PRIVATE
    | ARROW IDENTIFIER (',' IDENTIFIER)*
    ;

constructorSection
    : CREATE constructorDecl+
    ;

featureMember
    : fieldDecl
    | methodDecl
    ;

fieldDecl
    : IDENTIFIER ':' type (EQUAL expression)? noteClause?
    | IDENTIFIER EQUAL expression noteClause?
    ;

constructorDecl
    : IDENTIFIER ('(' paramList? ')')? requireClause? DO block ensureClause? rescueClause? END
    ;

methodDecl
    : IDENTIFIER ('(' paramList? ')')? (':' type)? noteClause? requireClause? DO block ensureClause? rescueClause? END
    ;

paramList
    : param (',' param)*
    ;

param
    : IDENTIFIER (',' IDENTIFIER)* (':' type)?
    ;

type
    : QMARK type
    | INTEGER_TYPE
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

rescueClause
    : RESCUE block
    ;

assertion
    : IDENTIFIER ':' expression
    ;

invariantClause
    : INVARIANT assertion+
    ;

noteClause
    : NOTE STRING
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
    | repeatStatement
    | acrossStatement
    | withStatement
    | raiseStatement
    | retryStatement
    | caseStatement
    | selectStatement
    | expression
    ;

caseStatement
    : CASE expression OF caseClause+ (ELSE statement)? END
    ;

caseClause
    : literal (',' literal)* THEN statement
    ;

selectStatement
    : SELECT selectClause+ timeoutClause? (ELSE block)? END
    ;

selectClause
    : WHEN expression (AS IDENTIFIER)? THEN block
    ;

timeoutClause
    : TIMEOUT expression THEN block
    ;

scopedBlock
    : DO block rescueClause? END
    ;

ifStatement
    : IF expression THEN block (ELSEIF expression THEN block)* (ELSE block)? END
    ;

loopStatement
    : FROM block invariantClause? variantClause? UNTIL expression DO block END
    ;

repeatStatement
    : REPEAT expression DO block END
    ;

acrossStatement
    : ACROSS expression AS IDENTIFIER DO block END
    ;

withStatement
    : WITH STRING DO block END
    ;

raiseStatement
    : RAISE expression
    ;

retryStatement
    : RETRY
    ;

spawnExpression
    : SPAWN DO block END
    ;

variantClause
    : VARIANT expression
    ;

assignment
    : IDENTIFIER ASSIGN expression
    | primary '.' IDENTIFIER ASSIGN expression
    ;

localVarDecl
    : LET IDENTIFIER (':' type)? ASSIGN expression
    ;

methodCall
    : primary callChain
    | IDENTIFIER
    ;

callChain
    : (memberAccess | callSuffix) postfixPart*
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
    : unary ((STAR | DIV | MOD | POW) unary)*
    ;

unary
    : MINUS unary          # unaryMinus
    | NOT unary            # unaryNot
    | postfix              # postfixExpr
    ;

postfix
    : primary postfixPart*
    ;

postfixPart
    : memberAccess
    | callSuffix
    ;

memberAccess
    : '.' IDENTIFIER ('(' argumentList? ')')?
    ;

callSuffix
    : '(' argumentList? ')'
    ;

primary
    : literal
    | spawnExpression
    | createExpression
    | convertExpression
    | anonymousFunction
    | whenExpression
    | oldExpression
    | THIS
    | IDENTIFIER
    | '(' expression ')'
    ;

convertExpression
    : CONVERT expression TO IDENTIFIER ':' type
    ;

whenExpression
    : WHEN expression expression ELSE expression END
    ;

anonymousFunction
    : FN '(' paramList? ')' (':' type)? DO block END
    ;

oldExpression
    : OLD primary
    ;

createExpression
    : CREATE IDENTIFIER genericArgs? ('.' IDENTIFIER ('(' argumentList? ')')?)?
    ;

genericArgs
    : '[' genericArg (',' genericArg)* ']'
    ;

genericArg
    : IDENTIFIER | type
    ;

literal
    : integerLiteral
    | realLiteral
    | charLiteral
    | booleanLiteral
    | nilLiteral
    | STRING
    | arrayLiteral
    | setLiteral
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

nilLiteral
    : NIL
    ;

arrayLiteral
    : '[' (expression (',' expression)*)? ']'
    ;

mapLiteral
    : '{' '}'
    | '{' mapEntry (',' mapEntry)* '}'
    ;

mapEntry
    : expression ':' expression
    ;

setLiteral
    : SET_START (expression (',' expression)*)? '}'
    ;

/*
 * =========================
 *         LEXER RULES
 * =========================
 */

CLASS        : 'class';
DEFERRED     : 'deferred';
FUNCTION     : 'function';
FN           : 'fn';
FEATURE      : 'feature';
PRIVATE      : 'private';
INHERIT      : 'inherit';
AS           : 'as';
DO           : 'do';
END          : 'end';
LET          : 'let';
CREATE       : 'create';
INTERN       : 'intern';
IMPORT       : 'import';
IF           : 'if';
THEN         : 'then';
ELSE         : 'else';
ELSEIF       : 'elseif';
WHEN         : 'when';
FROM         : 'from';
UNTIL        : 'until';
VARIANT      : 'variant';
REQUIRE      : 'require';
ENSURE       : 'ensure';
INVARIANT    : 'invariant';
OLD          : 'old';
THIS         : 'this';
NOTE         : 'note';
WITH         : 'with';
CONVERT      : 'convert';
TO           : 'to';
SPAWN        : 'spawn';
RAISE        : 'raise';
RESCUE       : 'rescue';
RETRY        : 'retry';
REPEAT       : 'repeat';
ACROSS       : 'across';
CASE         : 'case';
OF           : 'of';
SELECT       : 'select';
TIMEOUT      : 'timeout';
AND          : 'and';
OR           : 'or';
NOT          : 'not';

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
NIL          : 'nil';

ASSIGN       : ':=';
ARROW        : '->';

PLUS         : '+';
MINUS        : '-';
STAR         : '*';
DIV          : '/';
POW          : '^';
MOD          : '%';

EQUAL        : '=';
NOTEQUAL     : '/=';
QMARK        : '?';
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
 *  - required fractional part
 *  - optional exponent
 *
 * Examples:
 *  3.1415
 *  0.6
 *  .5
 */
REAL
    : DIGITS? '.' DIGITS EXPONENT?
    ;

/*
 * Integer:
 *  Sequence of digits.
 *  Sign handled in parser as unary minus.
 */
INTEGER
    : '0' 'b' BIN_DIGITS
    | '0' 'o' OCT_DIGITS
    | '0' 'x' HEX_DIGITS
    | DIGITS
    ;

fragment DIGITS
    : [0-9] ('_'? [0-9])*
    ;

fragment BIN_DIGITS
    : [0-1] ('_'? [0-1])*
    ;

fragment OCT_DIGITS
    : [0-7] ('_'? [0-7])*
    ;

fragment HEX_DIGITS
    : [0-9a-fA-F] ('_'? [0-9a-fA-F])*
    ;

fragment EXPONENT
    : [eE] [+\-]? DIGITS
    ;

fragment SPECIAL_CHAR_NAME
    : 'nul' | 'space' | 'newline' | 'tab' | 'return'
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
SET_START
    : '#{'
    ;

CHAR_LITERAL
    : '#' ( ~[0-9 \t\r\n] | SPECIAL_CHAR_NAME | DIGITS )
    ;

/*
 * -------------------------
 * String
 * -------------------------
 */
STRING
    : '"' (~["\\] | '\\' .)* '"'
    | '\'' (~['\\] | '\\' .)* '\''
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
