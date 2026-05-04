package dev.trentdb.parser;

import dev.trentdb.ast.BinaryExpression;
import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.ast.BetweenExpression;
import dev.trentdb.ast.CaseExpression;
import dev.trentdb.ast.CastExpression;
import dev.trentdb.ast.ColumnDefinition;
import dev.trentdb.ast.ColumnReferenceExpression;
import dev.trentdb.ast.CreateTableStatement;
import dev.trentdb.ast.ExplainStatement;
import dev.trentdb.ast.Expression;
import dev.trentdb.ast.FromItem;
import dev.trentdb.ast.FunctionCallExpression;
import dev.trentdb.ast.InExpression;
import dev.trentdb.ast.InsertStatement;
import dev.trentdb.ast.JoinClause;
import dev.trentdb.ast.JoinType;
import dev.trentdb.ast.LiteralExpression;
import dev.trentdb.ast.LiteralKind;
import dev.trentdb.ast.NullCheckExpression;
import dev.trentdb.ast.OrderByItem;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.ast.SelectItem;
import dev.trentdb.ast.SelectStatement;
import dev.trentdb.ast.SortDirection;
import dev.trentdb.ast.StarExpression;
import dev.trentdb.ast.Statement;
import dev.trentdb.ast.TableReference;
import dev.trentdb.ast.TypeName;
import dev.trentdb.ast.UnaryExpression;
import dev.trentdb.ast.UnaryOperator;
import dev.trentdb.parser.sql.TrentDbSqlParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class AstBuilder {
    Statement build(TrentDbSqlParser.SqlScriptContext context) {
        return statement(context.statement());
    }

    private Statement statement(TrentDbSqlParser.StatementContext context) {
        if (context.explain() != null) {
            return explain(context.explain());
        }
        if (context.createTable() != null) {
            return createTable(context.createTable());
        }
        if (context.insert() != null) {
            return insert(context.insert());
        }
        if (context.select() != null) {
            return select(context.select());
        }
        throw new ParsingException("Unsupported statement");
    }

    private ExplainStatement explain(TrentDbSqlParser.ExplainContext context) {
        return new ExplainStatement(statement(context.statement()));
    }

    private CreateTableStatement createTable(TrentDbSqlParser.CreateTableContext context) {
        ArrayList<ColumnDefinition> columns = new ArrayList<>(context.columnDef().size());
        for (TrentDbSqlParser.ColumnDefContext columnContext : context.columnDef()) {
            columns.add(columnDef(columnContext));
        }
        return new CreateTableStatement(qualifiedName(context.qualifiedName()), columns);
    }

    private ColumnDefinition columnDef(TrentDbSqlParser.ColumnDefContext context) {
        return new ColumnDefinition(unquote(context.identifier().getText()), typeName(context.typeName()));
    }

    private InsertStatement insert(TrentDbSqlParser.InsertContext context) {
        List<String> columns = List.of();
        if (context.identifierList() != null) {
            columns = context.identifierList().identifier().stream().map(identifier -> unquote(identifier.getText())).toList();
        }
        return new InsertStatement(
                qualifiedName(context.qualifiedName()),
                columns,
                expressionList(context.expressionList())
        );
    }

    private SelectStatement select(TrentDbSqlParser.SelectContext context) {
        ArrayList<SelectItem> selectItems = new ArrayList<>(context.selectItemList().selectItem().size());
        for (TrentDbSqlParser.SelectItemContext itemContext : context.selectItemList().selectItem()) {
            selectItems.add(selectItem(itemContext));
        }

        Expression where = context.whereClause() == null ? null : expression(context.whereClause().expression());
        List<Expression> groupBy = context.groupByClause() == null
                ? List.of()
                : expressionList(context.groupByClause().expressionList());
        List<OrderByItem> orderBy = context.orderByClause() == null ? List.of() : orderByItems(context.orderByClause());
        Long limit = context.limitClause() == null ? null : Long.parseLong(context.limitClause().integerLiteral().getText());
        return new SelectStatement(selectItems, fromItem(context.fromItem()), where, groupBy, orderBy, limit);
    }

    private SelectItem selectItem(TrentDbSqlParser.SelectItemContext context) {
        if (context.STAR() != null) {
            return new SelectItem(new StarExpression(), null);
        }
        String alias = context.identifier() == null ? null : unquote(context.identifier().getText());
        return new SelectItem(expression(context.expression()), alias);
    }

    private FromItem fromItem(TrentDbSqlParser.FromItemContext context) {
        ArrayList<JoinClause> joins = new ArrayList<>(context.joinClause().size());
        for (TrentDbSqlParser.JoinClauseContext joinContext : context.joinClause()) {
            joins.add(joinClause(joinContext));
        }
        return new FromItem(relationPrimary(context.relationPrimary()), joins);
    }

    private TableReference relationPrimary(TrentDbSqlParser.RelationPrimaryContext context) {
        if (context instanceof TrentDbSqlParser.NamedRelationPrimaryContext namedRelation) {
            String alias = namedRelation.identifier() == null ? null : unquote(namedRelation.identifier().getText());
            return new TableReference(qualifiedName(namedRelation.qualifiedName()), alias);
        }
        if (context instanceof TrentDbSqlParser.PathRelationPrimaryContext pathRelation) {
            String alias = pathRelation.identifier() == null ? null : unquote(pathRelation.identifier().getText());
            return TableReference.path(unquoteString(pathRelation.stringLiteral().getText()), alias);
        }
        throw new ParsingException("Unsupported relation primary");
    }

    private JoinClause joinClause(TrentDbSqlParser.JoinClauseContext context) {
        return new JoinClause(JoinType.INNER, relationPrimary(context.relationPrimary()), expression(context.expression()));
    }

    private List<OrderByItem> orderByItems(TrentDbSqlParser.OrderByClauseContext context) {
        ArrayList<OrderByItem> items = new ArrayList<>(context.orderByItem().size());
        for (TrentDbSqlParser.OrderByItemContext itemContext : context.orderByItem()) {
            items.add(orderByItem(itemContext));
        }
        return items;
    }

    private OrderByItem orderByItem(TrentDbSqlParser.OrderByItemContext context) {
        SortDirection direction = context.DESC() != null ? SortDirection.DESC : SortDirection.ASC;
        return new OrderByItem(expression(context.expression()), direction);
    }

    private List<Expression> expressionList(TrentDbSqlParser.ExpressionListContext context) {
        return context.expression().stream().map(this::expression).toList();
    }

    private Expression expression(TrentDbSqlParser.ExpressionContext context) {
        return booleanExpression(context.booleanExpression());
    }

    private Expression booleanExpression(TrentDbSqlParser.BooleanExpressionContext context) {
        if (context instanceof TrentDbSqlParser.OrExpressionContext orExpression) {
            return new BinaryExpression(
                    booleanExpression(orExpression.booleanExpression()),
                    BinaryOperator.OR,
                    booleanTerm(orExpression.booleanTerm())
            );
        }
        if (context instanceof TrentDbSqlParser.BooleanTermExpressionContext termExpression) {
            return booleanTerm(termExpression.booleanTerm());
        }
        throw new ParsingException("Unsupported boolean expression");
    }

    private Expression booleanTerm(TrentDbSqlParser.BooleanTermContext context) {
        if (context instanceof TrentDbSqlParser.AndExpressionContext andExpression) {
            return new BinaryExpression(
                    booleanTerm(andExpression.booleanTerm()),
                    BinaryOperator.AND,
                    booleanFactor(andExpression.booleanFactor())
            );
        }
        if (context instanceof TrentDbSqlParser.BooleanFactorExpressionContext factorExpression) {
            return booleanFactor(factorExpression.booleanFactor());
        }
        throw new ParsingException("Unsupported boolean term");
    }

    private Expression booleanFactor(TrentDbSqlParser.BooleanFactorContext context) {
        if (context instanceof TrentDbSqlParser.NotExpressionContext notExpression) {
            return new UnaryExpression(UnaryOperator.NOT, booleanFactor(notExpression.booleanFactor()));
        }
        if (context instanceof TrentDbSqlParser.PredicateExpressionContext predicateExpression) {
            return predicate(predicateExpression.predicate());
        }
        throw new ParsingException("Unsupported boolean factor");
    }

    private Expression predicate(TrentDbSqlParser.PredicateContext context) {
        if (context instanceof TrentDbSqlParser.IsNullPredicateContext isNullPredicate) {
            return new NullCheckExpression(valueExpression(isNullPredicate.valueExpression()), false);
        }
        if (context instanceof TrentDbSqlParser.IsNotNullPredicateContext isNotNullPredicate) {
            return new NullCheckExpression(valueExpression(isNotNullPredicate.valueExpression()), true);
        }
        if (context instanceof TrentDbSqlParser.BetweenPredicateContext betweenPredicate) {
            return new BetweenExpression(
                    valueExpression(betweenPredicate.valueExpression(0)),
                    valueExpression(betweenPredicate.valueExpression(1)),
                    valueExpression(betweenPredicate.valueExpression(2))
            );
        }
        if (context instanceof TrentDbSqlParser.InPredicateContext inPredicate) {
            return new InExpression(
                    valueExpression(inPredicate.valueExpression()),
                    expressionList(inPredicate.expressionList()),
                    inPredicate.NOT() != null
            );
        }
        if (context instanceof TrentDbSqlParser.LikePredicateContext likePredicate) {
            return new BinaryExpression(
                    valueExpression(likePredicate.valueExpression(0)),
                    likePredicate.NOT() == null ? BinaryOperator.LIKE : BinaryOperator.NOT_LIKE,
                    valueExpression(likePredicate.valueExpression(1))
            );
        }
        if (context instanceof TrentDbSqlParser.ComparisonPredicateContext comparisonPredicate) {
            return new BinaryExpression(
                    valueExpression(comparisonPredicate.valueExpression(0)),
                    comparisonOperator(comparisonPredicate.comparisonOperator()),
                    valueExpression(comparisonPredicate.valueExpression(1))
            );
        }
        if (context instanceof TrentDbSqlParser.ValuePredicateContext valuePredicate) {
            return valueExpression(valuePredicate.valueExpression());
        }
        throw new ParsingException("Unsupported predicate");
    }

    private Expression valueExpression(TrentDbSqlParser.ValueExpressionContext context) {
        if (context instanceof TrentDbSqlParser.AddExpressionContext addExpression) {
            return new BinaryExpression(
                    valueExpression(addExpression.valueExpression()),
                    BinaryOperator.ADD,
                    multiplicativeExpression(addExpression.multiplicativeExpression())
            );
        }
        if (context instanceof TrentDbSqlParser.SubtractExpressionContext subtractExpression) {
            return new BinaryExpression(
                    valueExpression(subtractExpression.valueExpression()),
                    BinaryOperator.SUBTRACT,
                    multiplicativeExpression(subtractExpression.multiplicativeExpression())
            );
        }
        if (context instanceof TrentDbSqlParser.MultiplicativeRootContext multiplicativeRoot) {
            return multiplicativeExpression(multiplicativeRoot.multiplicativeExpression());
        }
        throw new ParsingException("Unsupported value expression");
    }

    private Expression multiplicativeExpression(TrentDbSqlParser.MultiplicativeExpressionContext context) {
        if (context instanceof TrentDbSqlParser.MultiplyExpressionContext multiplyExpression) {
            return new BinaryExpression(
                    multiplicativeExpression(multiplyExpression.multiplicativeExpression()),
                    BinaryOperator.MULTIPLY,
                    unaryExpression(multiplyExpression.unaryExpression())
            );
        }
        if (context instanceof TrentDbSqlParser.DivideExpressionContext divideExpression) {
            return new BinaryExpression(
                    multiplicativeExpression(divideExpression.multiplicativeExpression()),
                    BinaryOperator.DIVIDE,
                    unaryExpression(divideExpression.unaryExpression())
            );
        }
        if (context instanceof TrentDbSqlParser.UnaryRootContext unaryRoot) {
            return unaryExpression(unaryRoot.unaryExpression());
        }
        throw new ParsingException("Unsupported multiplicative expression");
    }

    private Expression unaryExpression(TrentDbSqlParser.UnaryExpressionContext context) {
        if (context instanceof TrentDbSqlParser.UnaryPlusExpressionContext unaryPlus) {
            return new UnaryExpression(UnaryOperator.PLUS, unaryExpression(unaryPlus.unaryExpression()));
        }
        if (context instanceof TrentDbSqlParser.UnaryMinusExpressionContext unaryMinus) {
            return new UnaryExpression(UnaryOperator.MINUS, unaryExpression(unaryMinus.unaryExpression()));
        }
        if (context instanceof TrentDbSqlParser.PrimaryRootContext primaryRoot) {
            return primaryExpression(primaryRoot.primaryExpression());
        }
        throw new ParsingException("Unsupported unary expression");
    }

    private Expression primaryExpression(TrentDbSqlParser.PrimaryExpressionContext context) {
        if (context instanceof TrentDbSqlParser.LiteralPrimaryContext literalPrimary) {
            return literal(literalPrimary.literal());
        }
        if (context instanceof TrentDbSqlParser.ColumnReferencePrimaryContext columnReference) {
            return new ColumnReferenceExpression(qualifiedName(columnReference.qualifiedName()));
        }
        if (context instanceof TrentDbSqlParser.FunctionCallPrimaryContext functionCall) {
            return functionCall(functionCall.functionCall());
        }
        if (context instanceof TrentDbSqlParser.CastPrimaryContext castPrimary) {
            return castExpression(castPrimary.castExpression());
        }
        if (context instanceof TrentDbSqlParser.CasePrimaryContext casePrimary) {
            return caseExpression(casePrimary.caseExpression());
        }
        if (context instanceof TrentDbSqlParser.ParenthesizedExpressionContext parenthesizedExpression) {
            return expression(parenthesizedExpression.expression());
        }
        throw new ParsingException("Unsupported primary expression");
    }

    private FunctionCallExpression functionCall(TrentDbSqlParser.FunctionCallContext context) {
        boolean starArgument = context.STAR() != null;
        List<Expression> arguments = context.expressionList() == null ? List.of() : expressionList(context.expressionList());
        return new FunctionCallExpression(unquote(context.identifier().getText()), arguments, starArgument);
    }

    private CastExpression castExpression(TrentDbSqlParser.CastExpressionContext context) {
        return new CastExpression(expression(context.expression()), typeName(context.typeName()));
    }

    private CaseExpression caseExpression(TrentDbSqlParser.CaseExpressionContext context) {
        ArrayList<CaseExpression.WhenClause> branches = new ArrayList<>(context.caseWhenClause().size());
        for (TrentDbSqlParser.CaseWhenClauseContext branchContext : context.caseWhenClause()) {
            branches.add(new CaseExpression.WhenClause(
                    expression(branchContext.expression(0)),
                    expression(branchContext.expression(1))
            ));
        }
        Expression elseExpression = context.ELSE() == null ? null : expression(context.expression());
        return new CaseExpression(branches, elseExpression);
    }

    private LiteralExpression literal(TrentDbSqlParser.LiteralContext context) {
        if (context.integerLiteral() != null) {
            return new LiteralExpression(LiteralKind.INTEGER, Long.parseLong(context.integerLiteral().getText()));
        }
        if (context.decimalLiteral() != null) {
            return new LiteralExpression(LiteralKind.DECIMAL, Double.parseDouble(context.decimalLiteral().getText()));
        }
        if (context.stringLiteral() != null) {
            return new LiteralExpression(LiteralKind.STRING, unquoteString(context.stringLiteral().getText()));
        }
        if (context.TRUE() != null) {
            return new LiteralExpression(LiteralKind.BOOLEAN, true);
        }
        if (context.FALSE() != null) {
            return new LiteralExpression(LiteralKind.BOOLEAN, false);
        }
        return new LiteralExpression(LiteralKind.NULL, null);
    }

    private QualifiedName qualifiedName(TrentDbSqlParser.QualifiedNameContext context) {
        return new QualifiedName(context.identifier().stream().map(identifier -> unquote(identifier.getText())).toList());
    }

    private TypeName typeName(TrentDbSqlParser.TypeNameContext context) {
        if (context.BIGINT_T() != null) {
            return TypeName.BIGINT;
        }
        if (context.DOUBLE_T() != null) {
            return TypeName.DOUBLE;
        }
        if (context.BOOLEAN_T() != null) {
            return TypeName.BOOLEAN;
        }
        if (context.TEXT_T() != null) {
            return TypeName.TEXT;
        }
        if (context.DATE_T() != null) {
            return TypeName.DATE;
        }
        return TypeName.INT;
    }

    private BinaryOperator comparisonOperator(TrentDbSqlParser.ComparisonOperatorContext context) {
        if (context.EQ() != null) {
            return BinaryOperator.EQUAL;
        }
        if (context.NEQ() != null) {
            return BinaryOperator.NOT_EQUAL;
        }
        if (context.LT() != null) {
            return BinaryOperator.LESS_THAN;
        }
        if (context.LTE() != null) {
            return BinaryOperator.LESS_THAN_OR_EQUAL;
        }
        if (context.GT() != null) {
            return BinaryOperator.GREATER_THAN;
        }
        return BinaryOperator.GREATER_THAN_OR_EQUAL;
    }

    private String unquote(String text) {
        if (text.startsWith("\"")) {
            return text.substring(1, text.length() - 1).replace("\"\"", "\"");
        }
        return text.toLowerCase(Locale.ROOT);
    }

    private String unquoteString(String text) {
        return text.substring(1, text.length() - 1).replace("''", "'");
    }
}
