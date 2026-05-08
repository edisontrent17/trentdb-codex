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
    : withClause? SELECT selectItemList FROM fromItem (whereClause)? (groupByClause)? (havingClause)? (orderByClause)? (limitClause)?
    ;

withClause
    : WITH commonTableExpression (COMMA commonTableExpression)*
    ;

commonTableExpression
    : identifier (LPAREN identifierList RPAREN)? AS LPAREN select RPAREN
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
    : qualifiedName tableAlias?            #namedRelationPrimary
    | stringLiteral tableAlias?            #pathRelationPrimary
    | LPAREN select RPAREN tableAlias?     #subqueryRelationPrimary
    ;

joinClause
    : joinType? JOIN relationPrimary ON expression
    ;

joinType
    : INNER
    | LEFT OUTER?
    ;

tableAlias
    : AS? identifier (LPAREN identifierList RPAREN)?
    ;

whereClause
    : WHERE expression
    ;

groupByClause
    : GROUP BY expressionList
    ;

havingClause
    : HAVING expression
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
    | valueExpression NOT? LIKE valueExpression             #likePredicate
    | valueExpression NOT? IN LPAREN expressionList RPAREN  #inListPredicate
    | valueExpression NOT? IN LPAREN select RPAREN          #inSubqueryPredicate
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
    | intervalLiteral                                       #intervalPrimary
    | qualifiedName                                         #columnReferencePrimary
    | functionCall                                          #functionCallPrimary
    | extractExpression                                     #extractPrimary
    | castExpression                                        #castPrimary
    | caseExpression                                        #casePrimary
    | LPAREN select RPAREN                                  #subqueryPrimary
    | LPAREN expression RPAREN                              #parenthesizedExpression
    ;

functionCall
    : identifier LPAREN (DISTINCT expressionList | STAR | expressionList)? RPAREN
    ;

extractExpression
    : EXTRACT LPAREN extractArgument FROM expression RPAREN
    ;

extractArgument
    : YEAR
    | MONTH
    | DAY
    | identifier
    | stringLiteral
    ;

castExpression
    : CAST LPAREN expression AS typeName RPAREN
    ;

caseExpression
    : CASE caseWhenClause+ (ELSE expression)? END
    ;

caseWhenClause
    : WHEN expression THEN expression
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
    : dateLiteral
    | integerLiteral
    | decimalLiteral
    | stringLiteral
    | TRUE
    | FALSE
    | NULL_T
    ;

dateLiteral
    : DATE_T stringLiteral
    ;

intervalLiteral
    : INTERVAL stringLiteral intervalUnit
    ;

intervalUnit
    : DAY
    | MONTH
    | YEAR
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
WITH: 'WITH';
FROM: 'FROM';
WHERE: 'WHERE';
GROUP: 'GROUP';
BY: 'BY';
HAVING: 'HAVING';
ORDER: 'ORDER';
LIMIT: 'LIMIT';
EXPLAIN: 'EXPLAIN';
CAST: 'CAST';
EXTRACT: 'EXTRACT';
CASE: 'CASE';
WHEN: 'WHEN';
THEN: 'THEN';
ELSE: 'ELSE';
END: 'END';
JOIN: 'JOIN';
INNER: 'INNER';
LEFT: 'LEFT';
OUTER: 'OUTER';
ON: 'ON';
AS: 'AS';
IS: 'IS';
NOT: 'NOT';
IN: 'IN';
LIKE: 'LIKE';
BETWEEN: 'BETWEEN';
INTERVAL: 'INTERVAL';
DAY: 'DAY';
MONTH: 'MONTH';
YEAR: 'YEAR';
NULL_T: 'NULL';
TRUE: 'TRUE';
FALSE: 'FALSE';
AND: 'AND';
OR: 'OR';
ASC: 'ASC';
DESC: 'DESC';
DISTINCT: 'DISTINCT';
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
