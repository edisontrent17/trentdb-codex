package dev.trentdb.planner;

import dev.trentdb.ast.BinaryExpression;
import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.ast.BetweenExpression;
import dev.trentdb.ast.CaseExpression;
import dev.trentdb.ast.CastExpression;
import dev.trentdb.ast.ColumnReferenceExpression;
import dev.trentdb.ast.ExplainStatement;
import dev.trentdb.ast.Expression;
import dev.trentdb.ast.FunctionCallExpression;
import dev.trentdb.ast.InExpression;
import dev.trentdb.ast.JoinClause;
import dev.trentdb.ast.JoinType;
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
    private record BoundColumnBinding(String relationName, ColumnCatalogEntry column, int ordinal) {
    }

    private record BindingContext(List<BoundColumnBinding> columns) {
    }

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
        BoundFrom boundFrom = bindFrom(transaction, statement);
        BindingContext context = bindingContext(boundFrom);
        BoundExpression where = statement.where() == null ? null : bindWhereExpression(context, statement.where());
        List<BoundExpression> groupBy = bindGroupBy(context, statement.groupBy());
        ArrayList<BoundExpression> selectList = new ArrayList<>();
        ArrayList<String> selectNames = new ArrayList<>();

        for (SelectItem item : statement.selectItems()) {
            bindSelectExpression(context, item.expression(), item.alias(), selectList, selectNames, true);
        }

        validateAggregates(selectList, groupBy);
        boolean aggregateQuery = containsAggregate(selectList) || !groupBy.isEmpty();
        List<BoundOrderByItem> orderBy = bindOrderBy(context, statement.orderBy(), selectList, selectNames, aggregateQuery);
        return new BoundSelectStatement(boundFrom, selectList, selectNames, where, groupBy, orderBy, statement.limit());
    }

    private BoundFrom bindFrom(Transaction transaction, SelectStatement statement) {
        BoundTableRef left = bindTableRef(transaction, statement.from().base());
        List<JoinClause> joins = statement.from().joins();
        if (joins.isEmpty()) {
            return left;
        }
        if (joins.size() > 1) {
            throw new BinderException("Only a single INNER JOIN is supported");
        }
        JoinClause join = joins.getFirst();
        if (join.type() != JoinType.INNER) {
            throw new BinderException("Only INNER JOIN is supported");
        }
        BoundTableRef right = bindTableRef(transaction, join.right());
        BindingContext joinContext = bindingContext(left, right);
        BoundExpression condition = bindExpression(joinContext, join.condition(), false);
        if (!logicalType(condition).equals(LogicalType.BOOLEAN)) {
            throw new BinderException("JOIN condition must evaluate to BOOLEAN but got " + typeName(logicalType(condition)));
        }
        return new BoundJoinRef(left, right, condition);
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

    private BindingContext bindingContext(BoundFrom from) {
        if (from instanceof BoundTableRef tableRef) {
            return bindingContext(tableRef);
        }
        if (from instanceof BoundJoinRef joinRef) {
            return bindingContext(joinRef.left(), joinRef.right());
        }
        throw new BinderException("Unsupported FROM source: " + from.getClass().getSimpleName());
    }

    private BindingContext bindingContext(BoundTableRef tableRef) {
        ArrayList<BoundColumnBinding> bindings = new ArrayList<>();
        List<ColumnCatalogEntry> tableColumns = columns(tableRef);
        for (int index = 0; index < tableColumns.size(); index++) {
            bindings.add(new BoundColumnBinding(relationName(tableRef), tableColumns.get(index), index));
        }
        return new BindingContext(bindings);
    }

    private BindingContext bindingContext(BoundTableRef left, BoundTableRef right) {
        ArrayList<BoundColumnBinding> bindings = new ArrayList<>();
        List<ColumnCatalogEntry> leftColumns = columns(left);
        for (int index = 0; index < leftColumns.size(); index++) {
            bindings.add(new BoundColumnBinding(relationName(left), leftColumns.get(index), index));
        }
        List<ColumnCatalogEntry> rightColumns = columns(right);
        int offset = leftColumns.size();
        for (int index = 0; index < rightColumns.size(); index++) {
            bindings.add(new BoundColumnBinding(relationName(right), rightColumns.get(index), offset + index));
        }
        return new BindingContext(bindings);
    }

    private void bindSelectExpression(
            BindingContext context,
            Expression expression,
            String alias,
            ArrayList<BoundExpression> selectList,
            ArrayList<String> selectNames,
            boolean allowAggregates
    ) {
        if (expression instanceof StarExpression) {
            for (BoundColumnBinding column : context.columns()) {
                selectList.add(new BoundColumnRefExpression(column.column(), column.ordinal()));
                selectNames.add(column.column().name());
            }
            return;
        }
        if (expression instanceof ColumnReferenceExpression columnReference) {
            BoundExpression column = bindExpression(context, columnReference, allowAggregates);
            selectList.add(column);
            selectNames.add(alias == null ? ((BoundColumnRefExpression) column).name() : alias);
            return;
        }
        BoundExpression bound = bindExpression(context, expression, allowAggregates);
        selectList.add(bound);
        selectNames.add(alias == null ? defaultSelectName(bound) : alias);
    }

    private BoundExpression bindWhereExpression(BindingContext context, Expression expression) {
        BoundExpression bound = bindExpression(context, expression, false);
        if (!logicalType(bound).equals(LogicalType.BOOLEAN)) {
            throw new BinderException("WHERE expression must evaluate to BOOLEAN but got " + typeName(logicalType(bound)));
        }
        return bound;
    }

    private List<BoundExpression> bindGroupBy(BindingContext context, List<Expression> groupBy) {
        ArrayList<BoundExpression> result = new ArrayList<>(groupBy.size());
        for (Expression expression : groupBy) {
            result.add(bindExpression(context, expression, false));
        }
        return result;
    }

    private List<BoundOrderByItem> bindOrderBy(
            BindingContext context,
            List<OrderByItem> orderBy,
            List<BoundExpression> selectList,
            List<String> selectNames,
            boolean aggregateQuery
    ) {
        ArrayList<BoundOrderByItem> result = new ArrayList<>(orderBy.size());
        for (OrderByItem item : orderBy) {
            BoundExpression expression = bindOrderExpression(context, item.expression(), selectList, selectNames, aggregateQuery);
            result.add(new BoundOrderByItem(expression, item.direction()));
        }
        return result;
    }

    private BoundExpression bindOrderExpression(BindingContext context, Expression expression, List<BoundExpression> selectList,
                                                List<String> selectNames, boolean aggregateQuery) {
        if (expression instanceof LiteralExpression literal && literal.kind() == LiteralKind.INTEGER) {
            int ordinal = Math.toIntExact((Long) literal.value());
            if (ordinal < 1 || ordinal > selectList.size()) {
                throw new BinderException("ORDER BY position " + ordinal + " is not in select list");
            }
            if (aggregateQuery) {
                return outputColumn(selectNames, selectList, ordinal - 1);
            }
            return selectList.get(ordinal - 1);
        }
        if (expression instanceof ColumnReferenceExpression columnReference && columnReference.name().parts().size() == 1) {
            String alias = columnReference.name().last();
            int matchIndex = -1;
            for (int index = 0; index < selectNames.size(); index++) {
                if (selectNames.get(index).equals(alias)) {
                    if (matchIndex >= 0) {
                        throw new BinderException("ORDER BY reference is ambiguous: " + alias);
                    }
                    matchIndex = index;
                }
            }
            if (matchIndex >= 0) {
                if (aggregateQuery) {
                    return outputColumn(selectNames, selectList, matchIndex);
                }
                return selectList.get(matchIndex);
            }
        }
        BoundExpression bound = bindExpression(context, expression, false);
        if (aggregateQuery) {
            for (int index = 0; index < selectList.size(); index++) {
                if (selectList.get(index).equals(bound)) {
                    return outputColumn(selectNames, selectList, index);
                }
            }
            throw new BinderException("ORDER BY expression must appear in aggregate query select list");
        }
        return bound;
    }

    private BoundOutputColumnExpression outputColumn(List<String> selectNames, List<BoundExpression> selectList, int index) {
        return new BoundOutputColumnExpression(selectNames.get(index), index, logicalType(selectList.get(index)));
    }

    private BoundExpression bindExpression(BindingContext context, Expression expression) {
        return bindExpression(context, expression, false);
    }

    private BoundExpression bindExpression(BindingContext context, Expression expression, boolean allowAggregates) {
        if (expression instanceof ColumnReferenceExpression columnReference) {
            return bindColumn(context, columnReference.name());
        }
        if (expression instanceof LiteralExpression literal) {
            return new BoundLiteralExpression(literalType(literal.kind()), literal.value());
        }
        if (expression instanceof FunctionCallExpression functionCall) {
            return bindFunctionCall(context, functionCall, allowAggregates);
        }
        if (expression instanceof BinaryExpression binary) {
            return bindBinaryExpression(context, binary, allowAggregates);
        }
        if (expression instanceof BetweenExpression between) {
            return bindBetweenExpression(context, between, allowAggregates);
        }
        if (expression instanceof InExpression in) {
            return bindInExpression(context, in, allowAggregates);
        }
        if (expression instanceof CastExpression cast) {
            return bindCastExpression(context, cast, allowAggregates);
        }
        if (expression instanceof CaseExpression caseExpression) {
            return bindCaseExpression(context, caseExpression, allowAggregates);
        }
        throw new BinderException("Unsupported expression: " + expression.getClass().getSimpleName());
    }

    private BoundBinaryExpression bindBinaryExpression(BindingContext context, BinaryExpression binary, boolean allowAggregates) {
        BoundExpression left = bindExpression(context, binary.left(), allowAggregates);
        BoundExpression right = bindExpression(context, binary.right(), allowAggregates);
        return new BoundBinaryExpression(
                left,
                binary.operator(),
                right,
                bindBinaryType(binary.operator(), logicalType(left), logicalType(right))
        );
    }

    private BoundBetweenExpression bindBetweenExpression(
            BindingContext context,
            BetweenExpression between,
            boolean allowAggregates
    ) {
        BoundExpression input = bindExpression(context, between.input(), allowAggregates);
        BoundExpression lower = bindExpression(context, between.lower(), allowAggregates);
        BoundExpression upper = bindExpression(context, between.upper(), allowAggregates);
        LogicalType inputType = logicalType(input);
        LogicalType lowerType = logicalType(lower);
        LogicalType upperType = logicalType(upper);
        if (!isNull(inputType) && !isNull(lowerType) && !isComparable(inputType, lowerType)) {
            throw new BinderException("BETWEEN lower bound cannot compare "
                    + typeName(inputType) + " and " + typeName(lowerType));
        }
        if (!isNull(inputType) && !isNull(upperType) && !isComparable(inputType, upperType)) {
            throw new BinderException("BETWEEN upper bound cannot compare "
                    + typeName(inputType) + " and " + typeName(upperType));
        }
        return new BoundBetweenExpression(input, lower, upper);
    }

    private BoundInExpression bindInExpression(BindingContext context, InExpression in, boolean allowAggregates) {
        BoundExpression input = bindExpression(context, in.input(), allowAggregates);
        LogicalType inputType = logicalType(input);
        ArrayList<BoundExpression> candidates = new ArrayList<>(in.candidates().size());
        for (Expression candidateExpression : in.candidates()) {
            BoundExpression candidate = bindExpression(context, candidateExpression, allowAggregates);
            LogicalType candidateType = logicalType(candidate);
            if (!isNull(inputType) && !isNull(candidateType) && !isComparable(inputType, candidateType)) {
                throw new BinderException("IN candidate cannot compare "
                        + typeName(inputType) + " and " + typeName(candidateType));
            }
            candidates.add(candidate);
        }
        return new BoundInExpression(input, candidates, in.negated());
    }

    private BoundCastExpression bindCastExpression(BindingContext context, CastExpression cast, boolean allowAggregates) {
        BoundExpression child = bindExpression(context, cast.child(), allowAggregates);
        LogicalType targetType = LogicalType.from(cast.targetType());
        LogicalType sourceType = logicalType(child);
        if (!canCast(sourceType, targetType)) {
            throw new BinderException("Cannot cast " + typeName(sourceType) + " to " + typeName(targetType));
        }
        return new BoundCastExpression(child, targetType);
    }

    private BoundCaseExpression bindCaseExpression(
            BindingContext context,
            CaseExpression caseExpression,
            boolean allowAggregates
    ) {
        ArrayList<BoundCaseExpression.WhenClause> branches = new ArrayList<>(caseExpression.branches().size());
        ArrayList<LogicalType> resultTypes = new ArrayList<>(caseExpression.branches().size() + 1);
        for (CaseExpression.WhenClause branch : caseExpression.branches()) {
            BoundExpression condition = bindExpression(context, branch.condition(), allowAggregates);
            if (!logicalType(condition).equals(LogicalType.BOOLEAN) && !isNull(logicalType(condition))) {
                throw new BinderException("CASE WHEN condition must evaluate to BOOLEAN but got "
                        + typeName(logicalType(condition)));
            }
            BoundExpression result = bindExpression(context, branch.result(), allowAggregates);
            resultTypes.add(logicalType(result));
            branches.add(new BoundCaseExpression.WhenClause(condition, result));
        }
        BoundExpression elseExpression = caseExpression.elseExpression() == null
                ? new BoundLiteralExpression(LogicalType.NULL, null)
                : bindExpression(context, caseExpression.elseExpression(), allowAggregates);
        resultTypes.add(logicalType(elseExpression));
        return new BoundCaseExpression(branches, elseExpression, commonCaseType(resultTypes));
    }

    private BoundExpression bindFunctionCall(BindingContext context, FunctionCallExpression functionCall, boolean allowAggregates) {
        if (functionRegistry.isAggregate(functionCall.name())) {
            if (!allowAggregates) {
                throw new BinderException("Aggregate functions are not allowed in this clause: " + functionCall.name());
            }
            return bindAggregateFunctionCall(context, functionCall);
        }
        if (functionCall.starArgument()) {
            throw new BinderException("Star arguments are not supported for scalar functions yet");
        }

        ArrayList<BoundExpression> arguments = new ArrayList<>(functionCall.arguments().size());
        for (Expression argument : functionCall.arguments()) {
            if (argument instanceof StarExpression) {
                throw new BinderException("Star arguments are not supported for scalar functions yet");
            }
            arguments.add(bindExpression(context, argument, allowAggregates));
        }

        dev.trentdb.function.ScalarFunction function = functionRegistry.bindScalar(
                functionCall.name(),
                arguments.stream().map(this::logicalType).toList()
        );
        return new BoundFunctionExpression(function, arguments);
    }

    private BoundAggregateExpression bindAggregateFunctionCall(BindingContext context, FunctionCallExpression functionCall) {
        ArrayList<BoundExpression> arguments = new ArrayList<>(functionCall.arguments().size());
        if (!functionCall.starArgument()) {
            for (Expression argument : functionCall.arguments()) {
                if (argument instanceof StarExpression) {
                    throw new BinderException("Star arguments are only supported as count(*)");
                }
                arguments.add(bindExpression(context, argument, false));
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
            case BoundBetweenExpression between -> between.logicalType();
            case BoundCaseExpression caseExpression -> caseExpression.logicalType();
            case BoundInExpression in -> in.logicalType();
            case BoundCastExpression cast -> cast.logicalType();
            case BoundOutputColumnExpression output -> output.logicalType();
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
            case LIKE,
                 NOT_LIKE -> bindLikeType(operator, left, right);
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

    private LogicalType bindLikeType(BinaryOperator operator, LogicalType left, LogicalType right) {
        if ((left.equals(LogicalType.TEXT) || isNull(left)) && (right.equals(LogicalType.TEXT) || isNull(right))) {
            return LogicalType.BOOLEAN;
        }
        throw new BinderException("Operator " + operator + " requires TEXT operands but got "
                + typeName(left) + " and " + typeName(right));
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

    private boolean canCast(LogicalType source, LogicalType target) {
        if (source.equals(target) || source.equals(LogicalType.NULL)) {
            return true;
        }
        if (target.equals(LogicalType.TEXT)) {
            return true;
        }
        if (isNumeric(source) && isNumeric(target)) {
            return true;
        }
        return source.equals(LogicalType.TEXT) && target.equals(LogicalType.DATE);
    }

    private boolean isNumeric(LogicalType logicalType) {
        return logicalType.equals(LogicalType.INTEGER)
                || logicalType.equals(LogicalType.BIGINT)
                || logicalType.equals(LogicalType.DOUBLE);
    }

    private boolean isNull(LogicalType logicalType) {
        return logicalType.equals(LogicalType.NULL);
    }

    private LogicalType commonCaseType(List<LogicalType> types) {
        LogicalType result = LogicalType.NULL;
        boolean numeric = false;
        boolean hasDouble = false;
        for (LogicalType type : types) {
            if (isNull(type)) {
                continue;
            }
            if (isNull(result)) {
                result = type;
                numeric = isNumeric(type);
                hasDouble = type.equals(LogicalType.DOUBLE);
                continue;
            }
            if (numeric && isNumeric(type)) {
                hasDouble = hasDouble || type.equals(LogicalType.DOUBLE);
                result = hasDouble ? LogicalType.DOUBLE : LogicalType.BIGINT;
                continue;
            }
            if (!result.equals(type)) {
                throw new BinderException("CASE result types are incompatible: "
                        + typeName(result) + " and " + typeName(type));
            }
        }
        return result;
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
            case BoundBetweenExpression between -> containsAggregate(between.input())
                    || containsAggregate(between.lower())
                    || containsAggregate(between.upper());
            case BoundCastExpression cast -> containsAggregate(cast.child());
            case BoundCaseExpression caseExpression -> {
                boolean result = containsAggregate(caseExpression.elseExpression());
                for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
                    if (containsAggregate(branch.condition()) || containsAggregate(branch.result())) {
                        result = true;
                        break;
                    }
                }
                yield result;
            }
            case BoundColumnRefExpression ignored -> false;
            case BoundInExpression in -> {
                boolean result = containsAggregate(in.input());
                for (BoundExpression candidate : in.candidates()) {
                    if (containsAggregate(candidate)) {
                        result = true;
                        break;
                    }
                }
                yield result;
            }
            case BoundOutputColumnExpression ignored -> false;
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

    private BoundColumnRefExpression bindColumn(BindingContext context, QualifiedName name) {
        if (name.parts().size() == 1) {
            String columnName = name.last();
            BoundColumnBinding match = null;
            for (BoundColumnBinding binding : context.columns()) {
                if (binding.column().name().equals(columnName)) {
                    if (match != null) {
                        throw new BinderException("Column reference is ambiguous: " + columnName);
                    }
                    match = binding;
                }
            }
            if (match == null) {
                throw new CatalogException("Column not found: " + columnName);
            }
            return new BoundColumnRefExpression(match.column(), match.ordinal());
        }
        if (name.parts().size() == 2) {
            String relationName = name.parts().getFirst();
            String columnName = name.parts().get(1);
            for (BoundColumnBinding binding : context.columns()) {
                if (binding.relationName().equals(relationName) && binding.column().name().equals(columnName)) {
                    return new BoundColumnRefExpression(binding.column(), binding.ordinal());
                }
            }
            throw new CatalogException("Column not found: " + String.join(".", name.parts()));
        }
        throw new BinderException("Unsupported qualified column reference: " + String.join(".", name.parts()));
    }

    private List<ColumnCatalogEntry> columns(BoundTableRef table) {
        if (table.isReplacementScan()) {
            return table.replacementScan().columns();
        }
        return table.table().columns();
    }

    private String relationName(BoundTableRef table) {
        if (table.alias() != null) {
            return table.alias();
        }
        if (table.isReplacementScan()) {
            return table.replacementScan().path();
        }
        return table.table().name();
    }
}
