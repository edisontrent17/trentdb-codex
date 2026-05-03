grammar TrentDbSql;

options {
    caseInsensitive = true;
}

sqlScript
    : statement SEMICOLON? EOF
    ;

statement
    : createTable
    | insert
    | select
    | explain
    ;

explain
    : EXPLAIN statement
    ;

createTable
    : CREATE TABLE qualifiedName LPAREN columnDef (COMMA columnDef)* RPAREN
    ;

columnDef
    : identifier typeName
    ;

insert
    : INSERT INTO qualifiedName (LPAREN identifierList RPAREN)? VALUES LPAREN expressionList RPAREN
    ;

select
    : SELECT selectItemList FROM fromItem (whereClause)? (groupByClause)? (orderByClause)? (limitClause)?
    ;

selectItemList
    : selectItem (COMMA selectItem)*
    ;

selectItem
    : expression (AS? identifier)?
    | STAR
    ;

fromItem
    : relationPrimary (joinClause)*
    ;

relationPrimary
    : qualifiedName (AS? identifier)?      #namedRelationPrimary
    | stringLiteral (AS? identifier)?      #pathRelationPrimary
    ;

joinClause
    : INNER? JOIN relationPrimary ON expression
    ;

whereClause
    : WHERE expression
    ;

groupByClause
    : GROUP BY expressionList
    ;

orderByClause
    : ORDER BY orderByItem (COMMA orderByItem)*
    ;

orderByItem
    : expression (ASC | DESC)?
    ;

limitClause
    : LIMIT integerLiteral
    ;

expressionList
    : expression (COMMA expression)*
    ;

identifierList
    : identifier (COMMA identifier)*
    ;

expression
    : booleanExpression
    ;

booleanExpression
    : booleanExpression OR booleanTerm                       #orExpression
    | booleanTerm                                           #booleanTermExpression
    ;

booleanTerm
    : booleanTerm AND booleanFactor                         #andExpression
    | booleanFactor                                         #booleanFactorExpression
    ;

booleanFactor
    : NOT booleanFactor                                     #notExpression
    | predicate                                             #predicateExpression
    ;

predicate
    : valueExpression IS NOT NULL_T                         #isNotNullPredicate
    | valueExpression IS NULL_T                             #isNullPredicate
    | valueExpression NOT? IN LPAREN expressionList RPAREN  #inPredicate
    | valueExpression BETWEEN valueExpression AND valueExpression #betweenPredicate
    | valueExpression comparisonOperator valueExpression    #comparisonPredicate
    | valueExpression                                       #valuePredicate
    ;

comparisonOperator
    : EQ
    | NEQ
    | LT
    | LTE
    | GT
    | GTE
    ;

valueExpression
    : valueExpression PLUS multiplicativeExpression         #addExpression
    | valueExpression MINUS multiplicativeExpression        #subtractExpression
    | multiplicativeExpression                              #multiplicativeRoot
    ;

multiplicativeExpression
    : multiplicativeExpression STAR unaryExpression         #multiplyExpression
    | multiplicativeExpression SLASH unaryExpression        #divideExpression
    | unaryExpression                                       #unaryRoot
    ;

unaryExpression
    : PLUS unaryExpression                                  #unaryPlusExpression
    | MINUS unaryExpression                                 #unaryMinusExpression
    | primaryExpression                                     #primaryRoot
    ;

primaryExpression
    : literal                                               #literalPrimary
    | qualifiedName                                         #columnReferencePrimary
    | functionCall                                          #functionCallPrimary
    | castExpression                                        #castPrimary
    | LPAREN expression RPAREN                              #parenthesizedExpression
    ;

functionCall
    : identifier LPAREN (STAR | expressionList)? RPAREN
    ;

castExpression
    : CAST LPAREN expression AS typeName RPAREN
    ;

qualifiedName
    : identifier (DOT identifier)*
    ;

typeName
    : INT_T
    | BIGINT_T
    | DOUBLE_T PRECISION_T?
    | BOOLEAN_T
    | TEXT_T
    | DATE_T
    ;

literal
    : integerLiteral
    | decimalLiteral
    | stringLiteral
    | TRUE
    | FALSE
    | NULL_T
    ;

integerLiteral
    : INTEGER_LITERAL
    ;

decimalLiteral
    : DECIMAL_LITERAL
    ;

stringLiteral
    : STRING_LITERAL
    ;

identifier
    : IDENTIFIER
    | QUOTED_IDENTIFIER
    ;

CREATE: 'CREATE';
TABLE: 'TABLE';
INSERT: 'INSERT';
INTO: 'INTO';
VALUES: 'VALUES';
SELECT: 'SELECT';
FROM: 'FROM';
WHERE: 'WHERE';
GROUP: 'GROUP';
BY: 'BY';
ORDER: 'ORDER';
LIMIT: 'LIMIT';
EXPLAIN: 'EXPLAIN';
CAST: 'CAST';
JOIN: 'JOIN';
INNER: 'INNER';
ON: 'ON';
AS: 'AS';
IS: 'IS';
NOT: 'NOT';
IN: 'IN';
BETWEEN: 'BETWEEN';
NULL_T: 'NULL';
TRUE: 'TRUE';
FALSE: 'FALSE';
AND: 'AND';
OR: 'OR';
ASC: 'ASC';
DESC: 'DESC';
INT_T: 'INT';
BIGINT_T: 'BIGINT';
DOUBLE_T: 'DOUBLE';
PRECISION_T: 'PRECISION';
BOOLEAN_T: 'BOOLEAN';
TEXT_T: 'TEXT';
DATE_T: 'DATE';

EQ: '=';
NEQ: '<>' | '!=';
LTE: '<=';
GTE: '>=';
LT: '<';
GT: '>';
PLUS: '+';
MINUS: '-';
STAR: '*';
SLASH: '/';
LPAREN: '(';
RPAREN: ')';
COMMA: ',';
DOT: '.';
SEMICOLON: ';';

DECIMAL_LITERAL
    : [0-9]+ '.' [0-9]+
    ;

INTEGER_LITERAL
    : [0-9]+
    ;

STRING_LITERAL
    : '\'' ( '\'\'' | ~'\'' )* '\''
    ;

QUOTED_IDENTIFIER
    : '"' ('""' | ~'"')+ '"'
    ;

IDENTIFIER
    : [a-z_] [a-z0-9_$]*
    ;

WS
    : [ \t\r\n]+ -> skip
    ;

LINE_COMMENT
    : '--' ~[\r\n]* -> skip
    ;
