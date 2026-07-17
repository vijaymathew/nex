grammar nexlang;

/*
 * =========================
 *        PARSER RULES
 * =========================
 */

program
    : (importStmt | internStmt | classDecl | unionDecl | declareTypeDecl | declareFunctionDecl | functionDecl | statement)* EOF
    ;

importStmt
    : IMPORT IDENTIFIER ('.' IDENTIFIER)* (FROM STRING)?
    ;

internStmt
    : INTERN IDENTIFIER ('/' IDENTIFIER)* (AS IDENTIFIER)?
    ;

classDecl
    : SEALED? DEFERRED? CLASS IDENTIFIER genericParams?
      noteClause?
      inheritClause?
      classBody
      invariantClause?
      END
    ;

// Concise sum-type declaration. Desugars in the walker to a
// `sealed deferred class` parent plus one `class ... inherit Parent`
// per variant (see src/nex/walker.clj :unionDecl). Payloads become
// feature fields plus an auto-generated `make` constructor.
//
// The optional leading `enum` keyword — allowed only when every variant is
// payload-free — enriches the enumeration with interned members, declaration-
// order Comparable, and a `values` array (see enum-parent-class in the walker).
unionDecl
    : ENUM? UNION IDENTIFIER genericParams?
      noteClause?
      unionVariant+
      END
    ;

unionVariant
    : IDENTIFIER ('(' paramList? ')')?
    ;

functionDecl
    : FUNCTION IDENTIFIER genericParams? '(' paramList? ')' (':' type)? noteClause? requireClause? DO block ensureClause? rescueClause? END
    ;

declareFunctionDecl
    : DECLARE FUNCTION IDENTIFIER genericParams? '(' paramList? ')' (':' type)? noteClause?
    ;

// `declare type X = Base` is a structural alias. With an optional `where`
// predicate it becomes a refinement type: Base narrowed by a boolean predicate,
// checked at narrowing boundaries (see the refinement pass in walker.clj).
declareTypeDecl
    : DECLARE TYPE_KW IDENTIFIER EQUAL type (WHERE IDENTIFIER ':' expression)?
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
    : typeName typeArgs?
    ;

typeName
    : IDENTIFIER
    | FUNCTION_TYPE
    ;

featureSection
    : visibilityModifier? FEATURE featureMember+
    ;

visibilityModifier
    : PRIVATE
    ;

constructorSection
    : CREATE constructorDecl+
    ;

featureMember
    : fieldDecl
    | methodDecl
    ;

fieldDecl
    : ONCE? IDENTIFIER ':' type (EQUAL expression)? noteClause?
    | IDENTIFIER EQUAL expression noteClause?
    ;

constructorDecl
    : IDENTIFIER ('(' paramList? ')')? requireClause? DO block ensureClause? rescueClause? END
    ;

methodDecl
    : IDENTIFIER ('(' paramList? ')')? (':' type)? aliasClause? noteClause? requireClause? DO block ensureClause? rescueClause? END
    | IDENTIFIER '(' paramList? ')' (':' type)? aliasClause? noteClause? DEFERRED?
    ;

// Binds an operator symbol to this feature, e.g. `alias "-"`. The operator is
// then sugar for the call, contracts and all. Only the fixed operator set may be
// aliased; no new symbols can be invented.
//
// `alias` is a SOFT keyword: it is matched here as a plain IDENTIFIER (nothing
// else may appear at this position, so there is no ambiguity) and the walker
// checks the spelling. That keeps `alias` usable as an ordinary name — a
// variable, field, parameter, or routine — which a reserved word would forbid.
aliasClause
    : IDENTIFIER STRING
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
    | REAL_TYPE
    | CHAR_TYPE
    | BOOLEAN_TYPE
    | STRING_TYPE
    | functionType
    | IDENTIFIER typeArgs?
    ;

functionType
    : FUNCTION_TYPE ('(' functionTypeParams? ')' (':' type)?)?
    ;

functionTypeParams
    : functionTypeParam (',' functionTypeParam)*
    ;

functionTypeParam
    : IDENTIFIER ':' type
    | type
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
    | matchStatement
    | selectStatement
    | expression
    ;

caseStatement
    : CASE expression OF caseClause+ (ELSE statement)? END
    ;

caseClause
    : literal (',' literal)* THEN statement
    ;

matchStatement
    : MATCH expression OF matchClause+ (ELSE block)? END
    ;

// A match clause may destructure the matched variant's payload fields by name
// (`when Placed(id, total)`), bind the whole value (`as v`), both, or neither
// (`when Draft`). `when _` is a catch-all. Destructuring/wildcard desugar in the
// walker to the plain type-dispatch form, so the backends are unchanged.
matchClause
    : WHEN typeName typeArgs? ('(' fieldPattern (',' fieldPattern)* ')')? (AS IDENTIFIER)? (IF expression)? THEN block
    ;

// The identifier *before* the colon always names a field of the variant; `:`
// means "this field has this type", and `as` renames the field's binding.
//
// `IDENTIFIER ':' literal` is kept only to diagnose it: literal field patterns
// were removed in favour of the guard they desugared to, and the walker rejects
// this alternative with the guard spelling. Deleting it here instead would make
// `when Move(dx: 0)` an opaque "no viable alternative" parse error.
fieldPattern
    : IDENTIFIER ':' literal          // removed — rejected in the walker
    | IDENTIFIER ':' patternType ('(' (fieldPattern (',' fieldPattern)*)? ')')?   // field must be a patternType, optionally matching its payload
    | IDENTIFIER AS IDENTIFIER        // bind field to a differently-named local
    | IDENTIFIER                      // bind field to a local of the same name
    ;

// The types nameable in a pattern: a runtime type test, so no `?T` (a test for
// "possibly nil" is not one) and no structural function type.
patternType
    : INTEGER_TYPE
    | REAL_TYPE
    | CHAR_TYPE
    | BOOLEAN_TYPE
    | STRING_TYPE
    | typeName typeArgs?
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
    | primary '.' (IDENTIFIER | UNION | WHERE) ASSIGN expression
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
    : comparison ((IDENTEQUAL | IDENTITYNOTEQUAL | EQUAL | NOTEQUAL) comparison)*
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

// `union`/`where` are soft keywords: reserved only in their declaration
// positions (unionDecl, the `where` clause of declareTypeDecl). Everywhere a
// member name is expected they stay usable as ordinary identifiers — notably
// Set's `union` method (`s.union(...)`).
memberAccess
    : QMARK? '.' (IDENTIFIER | UNION | WHERE) ('(' argumentList? ')')?
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
    : WHEN expression THEN expression ELSE expression END
    ;

anonymousFunction
    : FN genericParams? '(' paramList? ')' (':' type)? DO block END
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
UNION        : 'union';
ENUM         : 'enum';
WHERE        : 'where';
SEALED       : 'sealed';
DEFERRED     : 'deferred';
ONCE         : 'once';
MATCH        : 'match';
DECLARE      : 'declare';
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

TYPE_KW        : 'type';
FUNCTION_TYPE  : 'Function';

// Type keywords
INTEGER_TYPE   : 'Integer';
REAL_TYPE      : 'Real';
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

IDENTEQUAL   : '==';
IDENTITYNOTEQUAL : '!=';
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
