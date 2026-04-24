package dev.trentdb.parser;

import dev.trentdb.ast.BinaryExpression;
import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.ast.ColumnDefinition;
import dev.trentdb.ast.ColumnReferenceExpression;
import dev.trentdb.ast.CreateTableStatement;
import dev.trentdb.ast.ExplainStatement;
import dev.trentdb.ast.Expression;
import dev.trentdb.ast.FromItem;
import dev.trentdb.ast.FunctionCallExpression;
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
import dev.trentdb.parser.sql.PostgresSubsetSqlBaseVisitor;
import dev.trentdb.parser.sql.PostgresSubsetSqlParser;

import java.util.ArrayList;
import java.util.List;

final class AstBuilder extends PostgresSubsetSqlBaseVisitor<Object> {
    Statement build(PostgresSubsetSqlParser.SqlScriptContext context) {
        return (Statement) visit(context.statement());
    }

    @Override
    public Object visitExplain(PostgresSubsetSqlParser.ExplainContext ctx) {
        return new ExplainStatement((Statement) visit(ctx.statement()));
    }

    @Override
    public Object visitCreateTable(PostgresSubsetSqlParser.CreateTableContext ctx) {
        var columns = new ArrayList<ColumnDefinition>(ctx.columnDef().size());
        for (var columnContext : ctx.columnDef()) {
            columns.add((ColumnDefinition) visit(columnContext));
        }
        return new CreateTableStatement(qualifiedName(ctx.qualifiedName()), columns);
    }

    @Override
    public Object visitColumnDef(PostgresSubsetSqlParser.ColumnDefContext ctx) {
        return new ColumnDefinition(unquote(ctx.identifier().getText()), typeName(ctx.typeName()));
    }

    @Override
    public Object visitInsert(PostgresSubsetSqlParser.InsertContext ctx) {
        List<String> columns = List.of();
        if (ctx.identifierList() != null) {
            columns = ctx.identifierList().identifier().stream().map(id -> unquote(id.getText())).toList();
        }
        return new InsertStatement(
                qualifiedName(ctx.qualifiedName()),
                columns,
                expressionList(ctx.expressionList())
        );
    }

    @Override
    public Object visitSelect(PostgresSubsetSqlParser.SelectContext ctx) {
        var selectItems = new ArrayList<SelectItem>(ctx.selectItemList().selectItem().size());
        for (var itemContext : ctx.selectItemList().selectItem()) {
            selectItems.add((SelectItem) visit(itemContext));
        }

        Expression where = ctx.whereClause() == null ? null : expression(ctx.whereClause().expression());
        List<Expression> groupBy = ctx.groupByClause() == null ? List.of() : expressionList(ctx.groupByClause().expressionList());

        List<OrderByItem> orderBy = List.of();
        if (ctx.orderByClause() != null) {
            var items = new ArrayList<OrderByItem>(ctx.orderByClause().orderByItem().size());
            for (var itemContext : ctx.orderByClause().orderByItem()) {
                items.add((OrderByItem) visit(itemContext));
            }
            orderBy = items;
        }

        Long limit = ctx.limitClause() == null ? null : Long.parseLong(ctx.limitClause().integerLiteral().getText());
        return new SelectStatement(selectItems, (FromItem) visit(ctx.fromItem()), where, groupBy, orderBy, limit);
    }

    @Override
    public Object visitSelectItem(PostgresSubsetSqlParser.SelectItemContext ctx) {
        if (ctx.STAR() != null) {
            return new SelectItem(new StarExpression(), null);
        }
        String alias = ctx.identifier() == null ? null : unquote(ctx.identifier().getText());
        return new SelectItem(expression(ctx.expression()), alias);
    }

    @Override
    public Object visitFromItem(PostgresSubsetSqlParser.FromItemContext ctx) {
        var joins = new ArrayList<JoinClause>(ctx.joinClause().size());
        for (var joinContext : ctx.joinClause()) {
            joins.add((JoinClause) visit(joinContext));
        }
        return new FromItem((TableReference) visit(ctx.relationPrimary()), joins);
    }

    @Override
    public Object visitRelationPrimary(PostgresSubsetSqlParser.RelationPrimaryContext ctx) {
        String alias = ctx.identifier() == null ? null : unquote(ctx.identifier().getText());
        return new TableReference(qualifiedName(ctx.qualifiedName()), alias);
    }

    @Override
    public Object visitJoinClause(PostgresSubsetSqlParser.JoinClauseContext ctx) {
        return new JoinClause(JoinType.INNER, (TableReference) visit(ctx.relationPrimary()), expression(ctx.expression()));
    }

    @Override
    public Object visitOrderByItem(PostgresSubsetSqlParser.OrderByItemContext ctx) {
        SortDirection direction = ctx.DESC() != null ? SortDirection.DESC : SortDirection.ASC;
        return new OrderByItem(expression(ctx.expression()), direction);
    }

    @Override
    public Object visitOrExpression(PostgresSubsetSqlParser.OrExpressionContext ctx) {
        return new BinaryExpression(expression(ctx.booleanExpression()), BinaryOperator.OR, expression(ctx.booleanTerm()));
    }

    @Override
    public Object visitBooleanTermExpression(PostgresSubsetSqlParser.BooleanTermExpressionContext ctx) {
        return visit(ctx.booleanTerm());
    }

    @Override
    public Object visitAndExpression(PostgresSubsetSqlParser.AndExpressionContext ctx) {
        return new BinaryExpression(expression(ctx.booleanTerm()), BinaryOperator.AND, expression(ctx.booleanFactor()));
    }

    @Override
    public Object visitBooleanFactorExpression(PostgresSubsetSqlParser.BooleanFactorExpressionContext ctx) {
        return visit(ctx.booleanFactor());
    }

    @Override
    public Object visitNotExpression(PostgresSubsetSqlParser.NotExpressionContext ctx) {
        return new UnaryExpression(UnaryOperator.NOT, expression(ctx.booleanFactor()));
    }

    @Override
    public Object visitPredicateExpression(PostgresSubsetSqlParser.PredicateExpressionContext ctx) {
        return visit(ctx.predicate());
    }

    @Override
    public Object visitIsNullPredicate(PostgresSubsetSqlParser.IsNullPredicateContext ctx) {
        return new NullCheckExpression(expression(ctx.valueExpression()), false);
    }

    @Override
    public Object visitIsNotNullPredicate(PostgresSubsetSqlParser.IsNotNullPredicateContext ctx) {
        return new NullCheckExpression(expression(ctx.valueExpression()), true);
    }

    @Override
    public Object visitComparisonPredicate(PostgresSubsetSqlParser.ComparisonPredicateContext ctx) {
        return new BinaryExpression(
                expression(ctx.valueExpression(0)),
                comparisonOperator(ctx.comparisonOperator()),
                expression(ctx.valueExpression(1))
        );
    }

    @Override
    public Object visitValuePredicate(PostgresSubsetSqlParser.ValuePredicateContext ctx) {
        return visit(ctx.valueExpression());
    }

    @Override
    public Object visitAddExpression(PostgresSubsetSqlParser.AddExpressionContext ctx) {
        return new BinaryExpression(expression(ctx.valueExpression()), BinaryOperator.ADD, expression(ctx.multiplicativeExpression()));
    }

    @Override
    public Object visitSubtractExpression(PostgresSubsetSqlParser.SubtractExpressionContext ctx) {
        return new BinaryExpression(expression(ctx.valueExpression()), BinaryOperator.SUBTRACT, expression(ctx.multiplicativeExpression()));
    }

    @Override
    public Object visitMultiplicativeRoot(PostgresSubsetSqlParser.MultiplicativeRootContext ctx) {
        return visit(ctx.multiplicativeExpression());
    }

    @Override
    public Object visitMultiplyExpression(PostgresSubsetSqlParser.MultiplyExpressionContext ctx) {
        return new BinaryExpression(expression(ctx.multiplicativeExpression()), BinaryOperator.MULTIPLY, expression(ctx.unaryExpression()));
    }

    @Override
    public Object visitDivideExpression(PostgresSubsetSqlParser.DivideExpressionContext ctx) {
        return new BinaryExpression(expression(ctx.multiplicativeExpression()), BinaryOperator.DIVIDE, expression(ctx.unaryExpression()));
    }

    @Override
    public Object visitUnaryRoot(PostgresSubsetSqlParser.UnaryRootContext ctx) {
        return visit(ctx.unaryExpression());
    }

    @Override
    public Object visitUnaryPlusExpression(PostgresSubsetSqlParser.UnaryPlusExpressionContext ctx) {
        return new UnaryExpression(UnaryOperator.PLUS, expression(ctx.unaryExpression()));
    }

    @Override
    public Object visitUnaryMinusExpression(PostgresSubsetSqlParser.UnaryMinusExpressionContext ctx) {
        return new UnaryExpression(UnaryOperator.MINUS, expression(ctx.unaryExpression()));
    }

    @Override
    public Object visitPrimaryRoot(PostgresSubsetSqlParser.PrimaryRootContext ctx) {
        return visit(ctx.primaryExpression());
    }

    @Override
    public Object visitLiteralPrimary(PostgresSubsetSqlParser.LiteralPrimaryContext ctx) {
        return visit(ctx.literal());
    }

    @Override
    public Object visitColumnReferencePrimary(PostgresSubsetSqlParser.ColumnReferencePrimaryContext ctx) {
        return new ColumnReferenceExpression(qualifiedName(ctx.qualifiedName()));
    }

    @Override
    public Object visitFunctionCallPrimary(PostgresSubsetSqlParser.FunctionCallPrimaryContext ctx) {
        return visit(ctx.functionCall());
    }

    @Override
    public Object visitParenthesizedExpression(PostgresSubsetSqlParser.ParenthesizedExpressionContext ctx) {
        return visit(ctx.expression());
    }

    @Override
    public Object visitFunctionCall(PostgresSubsetSqlParser.FunctionCallContext ctx) {
        boolean starArgument = ctx.STAR() != null;
        List<Expression> arguments = ctx.expressionList() == null ? List.of() : expressionList(ctx.expressionList());
        return new FunctionCallExpression(unquote(ctx.identifier().getText()), arguments, starArgument);
    }

    @Override
    public Object visitLiteral(PostgresSubsetSqlParser.LiteralContext ctx) {
        if (ctx.integerLiteral() != null) {
            return new LiteralExpression(LiteralKind.INTEGER, Long.parseLong(ctx.integerLiteral().getText()));
        }
        if (ctx.decimalLiteral() != null) {
            return new LiteralExpression(LiteralKind.DECIMAL, Double.parseDouble(ctx.decimalLiteral().getText()));
        }
        if (ctx.stringLiteral() != null) {
            return new LiteralExpression(LiteralKind.STRING, unquoteString(ctx.stringLiteral().getText()));
        }
        if (ctx.TRUE() != null) {
            return new LiteralExpression(LiteralKind.BOOLEAN, true);
        }
        if (ctx.FALSE() != null) {
            return new LiteralExpression(LiteralKind.BOOLEAN, false);
        }
        return new LiteralExpression(LiteralKind.NULL, null);
    }

    private List<Expression> expressionList(PostgresSubsetSqlParser.ExpressionListContext ctx) {
        return ctx.expression().stream().map(this::expression).toList();
    }

    private Expression expression(PostgresSubsetSqlParser.BooleanTermContext ctx) {
        return (Expression) visit(ctx);
    }

    private Expression expression(PostgresSubsetSqlParser.BooleanFactorContext ctx) {
        return (Expression) visit(ctx);
    }

    private Expression expression(PostgresSubsetSqlParser.ExpressionContext ctx) {
        return (Expression) visit(ctx);
    }

    private Expression expression(PostgresSubsetSqlParser.BooleanExpressionContext ctx) {
        return (Expression) visit(ctx);
    }

    private Expression expression(PostgresSubsetSqlParser.ValueExpressionContext ctx) {
        return (Expression) visit(ctx);
    }

    private Expression expression(PostgresSubsetSqlParser.MultiplicativeExpressionContext ctx) {
        return (Expression) visit(ctx);
    }

    private Expression expression(PostgresSubsetSqlParser.UnaryExpressionContext ctx) {
        return (Expression) visit(ctx);
    }

    private QualifiedName qualifiedName(PostgresSubsetSqlParser.QualifiedNameContext ctx) {
        return new QualifiedName(ctx.identifier().stream().map(id -> unquote(id.getText())).toList());
    }

    private TypeName typeName(PostgresSubsetSqlParser.TypeNameContext ctx) {
        if (ctx.BIGINT_T() != null) {
            return TypeName.BIGINT;
        }
        if (ctx.DOUBLE_T() != null) {
            return TypeName.DOUBLE;
        }
        if (ctx.BOOLEAN_T() != null) {
            return TypeName.BOOLEAN;
        }
        if (ctx.TEXT_T() != null) {
            return TypeName.TEXT;
        }
        return TypeName.INT;
    }

    private BinaryOperator comparisonOperator(PostgresSubsetSqlParser.ComparisonOperatorContext ctx) {
        if (ctx.EQ() != null) {
            return BinaryOperator.EQUAL;
        }
        if (ctx.NEQ() != null) {
            return BinaryOperator.NOT_EQUAL;
        }
        if (ctx.LT() != null) {
            return BinaryOperator.LESS_THAN;
        }
        if (ctx.LTE() != null) {
            return BinaryOperator.LESS_THAN_OR_EQUAL;
        }
        if (ctx.GT() != null) {
            return BinaryOperator.GREATER_THAN;
        }
        return BinaryOperator.GREATER_THAN_OR_EQUAL;
    }

    private String unquote(String text) {
        if (text.startsWith("\"")) {
            return text.substring(1, text.length() - 1).replace("\"\"", "\"");
        }
        return text;
    }

    private String unquoteString(String text) {
        return text.substring(1, text.length() - 1).replace("''", "'");
    }
}
