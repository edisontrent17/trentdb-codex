package dev.trentdb.planner;

import dev.trentdb.ast.BinaryExpression;
import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.ast.ColumnReferenceExpression;
import dev.trentdb.ast.ExplainStatement;
import dev.trentdb.ast.Expression;
import dev.trentdb.ast.FunctionCallExpression;
import dev.trentdb.ast.LiteralExpression;
import dev.trentdb.ast.LiteralKind;
import dev.trentdb.ast.OrderByItem;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.ast.SelectItem;
import dev.trentdb.ast.SelectStatement;
import dev.trentdb.ast.StarExpression;
import dev.trentdb.ast.Statement;
import dev.trentdb.ast.TableReference;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.catalog.CatalogException;
import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.catalog.TableCatalogEntry;
import dev.trentdb.function.FunctionRegistry;
import dev.trentdb.replacement.ReplacementScanRegistry;
import dev.trentdb.transaction.Transaction;
import dev.trentdb.types.LogicalType;

import java.util.ArrayList;
import java.util.List;

public final class Binder {
    private final Catalog catalog;
    private final FunctionRegistry functionRegistry;
    private final ReplacementScanRegistry replacementScanRegistry;

    public Binder(Catalog catalog) {
        this(catalog, FunctionRegistry.withBuiltIns(), ReplacementScanRegistry.withBuiltIns());
    }

    public Binder(Catalog catalog, FunctionRegistry functionRegistry) {
        this(catalog, functionRegistry, ReplacementScanRegistry.withBuiltIns());
    }

    public Binder(Catalog catalog, FunctionRegistry functionRegistry, ReplacementScanRegistry replacementScanRegistry) {
        this.catalog = catalog;
        this.functionRegistry = functionRegistry;
        this.replacementScanRegistry = replacementScanRegistry;
    }

    public BoundStatement bind(Transaction transaction, Statement statement) {
        if (statement instanceof ExplainStatement explain) {
            return new BoundExplainStatement(bind(transaction, explain.statement()));
        }
        if (statement instanceof SelectStatement select) {
            return bindSelect(transaction, select);
        }
        throw new BinderException("Unsupported statement for binding: " + statement.getClass().getSimpleName());
    }

    public BoundSelectStatement bindSelect(Transaction transaction, SelectStatement statement) {
        if (!statement.from().joins().isEmpty()) {
            throw new BinderException("JOIN is not supported yet");
        }

        TableReference tableReference = statement.from().base();
        BoundTableRef boundFrom = bindTableRef(transaction, tableReference);
        BoundExpression where = statement.where() == null ? null : bindWhereExpression(boundFrom, statement.where());
        List<BoundExpression> groupBy = bindGroupBy(boundFrom, statement.groupBy());
        ArrayList<BoundExpression> selectList = new ArrayList<>();
        ArrayList<String> selectNames = new ArrayList<>();

        for (SelectItem item : statement.selectItems()) {
            bindSelectExpression(boundFrom, item.expression(), item.alias(), selectList, selectNames, true);
        }

        validateAggregates(selectList, groupBy);
        if ((containsAggregate(selectList) || !groupBy.isEmpty()) && !statement.orderBy().isEmpty()) {
            throw new BinderException("ORDER BY with aggregates is not supported yet");
        }
        List<BoundOrderByItem> orderBy = bindOrderBy(boundFrom, statement.orderBy(), selectList, selectNames);
        return new BoundSelectStatement(boundFrom, selectList, selectNames, where, groupBy, orderBy, statement.limit());
    }

    private BoundTableRef bindTableRef(Transaction transaction, TableReference tableReference) {
        if (tableReference.isPath()) {
            return BoundTableRef.replacement(replacementScanRegistry.replace(tableReference.path()), tableReference.alias());
        }
        try {
            return new BoundTableRef(catalog.lookupTable(transaction, tableReference.name()), tableReference.alias());
        } catch (CatalogException exception) {
            throw exception;
        }
    }

    private void bindSelectExpression(
            BoundTableRef table,
            Expression expression,
            String alias,
            ArrayList<BoundExpression> selectList,
            ArrayList<String> selectNames,
            boolean allowAggregates
    ) {
        if (expression instanceof StarExpression) {
            for (ColumnCatalogEntry column : columns(table)) {
                selectList.add(new BoundColumnRefExpression(column));
                selectNames.add(column.name());
            }
            return;
        }
        if (expression instanceof ColumnReferenceExpression columnReference) {
            BoundExpression column = bindExpression(table, columnReference, allowAggregates);
            selectList.add(column);
            selectNames.add(alias == null ? ((BoundColumnRefExpression) column).name() : alias);
            return;
        }
        BoundExpression bound = bindExpression(table, expression, allowAggregates);
        selectList.add(bound);
        selectNames.add(alias == null ? defaultSelectName(bound) : alias);
    }

    private BoundExpression bindWhereExpression(BoundTableRef table, Expression expression) {
        BoundExpression bound = bindExpression(table, expression, false);
        if (!logicalType(bound).equals(LogicalType.BOOLEAN)) {
            throw new BinderException("WHERE expression must evaluate to BOOLEAN but got " + typeName(logicalType(bound)));
        }
        return bound;
    }

    private List<BoundExpression> bindGroupBy(BoundTableRef table, List<Expression> groupBy) {
        ArrayList<BoundExpression> result = new ArrayList<>(groupBy.size());
        for (Expression expression : groupBy) {
            result.add(bindExpression(table, expression, false));
        }
        return result;
    }

    private List<BoundOrderByItem> bindOrderBy(
            BoundTableRef table,
            List<OrderByItem> orderBy,
            List<BoundExpression> selectList,
            List<String> selectNames
    ) {
        ArrayList<BoundOrderByItem> result = new ArrayList<>(orderBy.size());
        for (OrderByItem item : orderBy) {
            result.add(new BoundOrderByItem(bindOrderExpression(table, item.expression(), selectList, selectNames), item.direction()));
        }
        return result;
    }

    private BoundExpression bindOrderExpression(
            BoundTableRef table,
            Expression expression,
            List<BoundExpression> selectList,
            List<String> selectNames
    ) {
        if (expression instanceof LiteralExpression literal && literal.kind() == LiteralKind.INTEGER) {
            int ordinal = Math.toIntExact((Long) literal.value());
            if (ordinal < 1 || ordinal > selectList.size()) {
                throw new BinderException("ORDER BY position " + ordinal + " is not in select list");
            }
            return selectList.get(ordinal - 1);
        }
        if (expression instanceof ColumnReferenceExpression columnReference && columnReference.name().parts().size() == 1) {
            String alias = columnReference.name().last();
            BoundExpression match = null;
            for (int index = 0; index < selectNames.size(); index++) {
                if (selectNames.get(index).equals(alias)) {
                    if (match != null) {
                        throw new BinderException("ORDER BY reference is ambiguous: " + alias);
                    }
                    match = selectList.get(index);
                }
            }
            if (match != null) {
                return match;
            }
        }
        return bindExpression(table, expression, false);
    }

    private BoundExpression bindExpression(BoundTableRef table, Expression expression) {
        return bindExpression(table, expression, false);
    }

    private BoundExpression bindExpression(BoundTableRef table, Expression expression, boolean allowAggregates) {
        if (expression instanceof ColumnReferenceExpression columnReference) {
            return new BoundColumnRefExpression(bindColumn(table, columnReference.name()));
        }
        if (expression instanceof LiteralExpression literal) {
            return new BoundLiteralExpression(literalType(literal.kind()), literal.value());
        }
        if (expression instanceof FunctionCallExpression functionCall) {
            return bindFunctionCall(table, functionCall, allowAggregates);
        }
        if (expression instanceof BinaryExpression binary) {
            return bindBinaryExpression(table, binary, allowAggregates);
        }
        throw new BinderException("Unsupported expression: " + expression.getClass().getSimpleName());
    }

    private BoundBinaryExpression bindBinaryExpression(BoundTableRef table, BinaryExpression binary, boolean allowAggregates) {
        BoundExpression left = bindExpression(table, binary.left(), allowAggregates);
        BoundExpression right = bindExpression(table, binary.right(), allowAggregates);
        return new BoundBinaryExpression(
                left,
                binary.operator(),
                right,
                bindBinaryType(binary.operator(), logicalType(left), logicalType(right))
        );
    }

    private BoundExpression bindFunctionCall(BoundTableRef table, FunctionCallExpression functionCall, boolean allowAggregates) {
        if (functionRegistry.isAggregate(functionCall.name())) {
            if (!allowAggregates) {
                throw new BinderException("Aggregate functions are not allowed in this clause: " + functionCall.name());
            }
            return bindAggregateFunctionCall(table, functionCall);
        }
        if (functionCall.starArgument()) {
            throw new BinderException("Star arguments are not supported for scalar functions yet");
        }

        ArrayList<BoundExpression> arguments = new ArrayList<>(functionCall.arguments().size());
        for (Expression argument : functionCall.arguments()) {
            if (argument instanceof StarExpression) {
                throw new BinderException("Star arguments are not supported for scalar functions yet");
            }
            arguments.add(bindExpression(table, argument, allowAggregates));
        }

        dev.trentdb.function.ScalarFunction function = functionRegistry.bindScalar(
                functionCall.name(),
                arguments.stream().map(this::logicalType).toList()
        );
        return new BoundFunctionExpression(function, arguments);
    }

    private BoundAggregateExpression bindAggregateFunctionCall(BoundTableRef table, FunctionCallExpression functionCall) {
        ArrayList<BoundExpression> arguments = new ArrayList<>(functionCall.arguments().size());
        if (!functionCall.starArgument()) {
            for (Expression argument : functionCall.arguments()) {
                if (argument instanceof StarExpression) {
                    throw new BinderException("Star arguments are only supported as count(*)");
                }
                arguments.add(bindExpression(table, argument, false));
            }
        }
        dev.trentdb.function.AggregateFunction function = functionRegistry.bindAggregate(
                functionCall.name(),
                arguments.stream().map(this::logicalType).toList(),
                functionCall.starArgument()
        );
        return new BoundAggregateExpression(function, arguments, functionCall.starArgument());
    }

    private LogicalType logicalType(BoundExpression expression) {
        return switch (expression) {
            case BoundColumnRefExpression column -> column.logicalType();
            case BoundLiteralExpression literal -> literal.logicalType();
            case BoundFunctionExpression function -> function.logicalType();
            case BoundBinaryExpression binary -> binary.logicalType();
            case BoundAggregateExpression aggregate -> aggregate.logicalType();
        };
    }

    private LogicalType bindBinaryType(BinaryOperator operator, LogicalType left, LogicalType right) {
        return switch (operator) {
            case EQUAL,
                 NOT_EQUAL,
                 LESS_THAN,
                 LESS_THAN_OR_EQUAL,
                 GREATER_THAN,
                 GREATER_THAN_OR_EQUAL -> bindComparisonType(operator, left, right);
            case AND,
                 OR -> bindBooleanType(operator, left, right);
            case ADD,
                 SUBTRACT,
                 MULTIPLY,
                 DIVIDE -> bindArithmeticType(operator, left, right);
        };
    }

    private LogicalType bindComparisonType(BinaryOperator operator, LogicalType left, LogicalType right) {
        if (isNull(left) || isNull(right) || isComparable(left, right)) {
            return LogicalType.BOOLEAN;
        }
        throw new BinderException("Operator " + operator + " cannot compare " + typeName(left) + " and " + typeName(right));
    }

    private LogicalType bindBooleanType(BinaryOperator operator, LogicalType left, LogicalType right) {
        if ((left.equals(LogicalType.BOOLEAN) || isNull(left)) && (right.equals(LogicalType.BOOLEAN) || isNull(right))) {
            return LogicalType.BOOLEAN;
        }
        throw new BinderException("Operator " + operator + " requires BOOLEAN operands but got " + typeName(left) + " and " + typeName(right));
    }

    private LogicalType bindArithmeticType(BinaryOperator operator, LogicalType left, LogicalType right) {
        if ((isNumeric(left) || isNull(left)) && (isNumeric(right) || isNull(right))) {
            if (operator == BinaryOperator.DIVIDE || left.equals(LogicalType.DOUBLE) || right.equals(LogicalType.DOUBLE)) {
                return LogicalType.DOUBLE;
            }
            return LogicalType.BIGINT;
        }
        throw new BinderException("Operator " + operator + " requires numeric operands but got " + typeName(left) + " and " + typeName(right));
    }

    private boolean isComparable(LogicalType left, LogicalType right) {
        if (isNumeric(left) && isNumeric(right)) {
            return true;
        }
        return left.equals(right);
    }

    private boolean isNumeric(LogicalType logicalType) {
        return logicalType.equals(LogicalType.INTEGER)
                || logicalType.equals(LogicalType.BIGINT)
                || logicalType.equals(LogicalType.DOUBLE);
    }

    private boolean isNull(LogicalType logicalType) {
        return logicalType.equals(LogicalType.NULL);
    }

    private String defaultSelectName(BoundExpression expression) {
        if (expression instanceof BoundColumnRefExpression column) {
            return column.name();
        }
        if (expression instanceof BoundFunctionExpression function) {
            return function.name();
        }
        if (expression instanceof BoundAggregateExpression aggregate) {
            return aggregate.name();
        }
        return "?column?";
    }

    private void validateAggregates(List<BoundExpression> selectList, List<BoundExpression> groupBy) {
        boolean hasAggregates = containsAggregate(selectList);
        if (!hasAggregates && groupBy.isEmpty()) {
            return;
        }
        for (BoundExpression expression : selectList) {
            validateAggregateSelectExpression(expression, groupBy);
        }
    }

    private void validateAggregateSelectExpression(BoundExpression expression, List<BoundExpression> groupBy) {
        if (containsAggregate(expression)) {
            if (!(expression instanceof BoundAggregateExpression)) {
                throw new BinderException("Aggregate expressions inside scalar expressions are not supported yet");
            }
            return;
        }
        for (BoundExpression group : groupBy) {
            if (group.equals(expression)) {
                return;
            }
        }
        throw new BinderException("Column must appear in GROUP BY or be used in an aggregate function");
    }

    private boolean containsAggregate(List<BoundExpression> expressions) {
        for (BoundExpression expression : expressions) {
            if (containsAggregate(expression)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAggregate(BoundExpression expression) {
        return switch (expression) {
            case BoundAggregateExpression ignored -> true;
            case BoundBinaryExpression binary -> containsAggregate(binary.left()) || containsAggregate(binary.right());
            case BoundColumnRefExpression ignored -> false;
            case BoundFunctionExpression function -> {
                boolean result = false;
                for (BoundExpression argument : function.arguments()) {
                    if (containsAggregate(argument)) {
                        result = true;
                        break;
                    }
                }
                yield result;
            }
            case BoundLiteralExpression ignored -> false;
        };
    }

    private String typeName(LogicalType logicalType) {
        return logicalType.id().name();
    }

    private LogicalType literalType(LiteralKind kind) {
        return switch (kind) {
            case INTEGER -> LogicalType.BIGINT;
            case DECIMAL -> LogicalType.DOUBLE;
            case STRING -> LogicalType.TEXT;
            case BOOLEAN -> LogicalType.BOOLEAN;
            case NULL -> LogicalType.NULL;
        };
    }

    private ColumnCatalogEntry bindColumn(BoundTableRef table, QualifiedName name) {
        if (name.parts().size() != 1) {
            throw new BinderException("Qualified column references are not supported yet: " + String.join(".", name.parts()));
        }
        for (ColumnCatalogEntry column : columns(table)) {
            if (column.name().equals(name.last())) {
                return column;
            }
        }
        throw new CatalogException("Column not found: " + name.last());
    }

    private List<ColumnCatalogEntry> columns(BoundTableRef table) {
        if (table.isReplacementScan()) {
            return table.replacementScan().columns();
        }
        return table.table().columns();
    }
}
