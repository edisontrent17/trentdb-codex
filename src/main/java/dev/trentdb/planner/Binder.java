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
import dev.trentdb.ast.InSubqueryExpression;
import dev.trentdb.ast.IntervalLiteralExpression;
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
import dev.trentdb.ast.SubqueryExpression;
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

import static dev.trentdb.planner.BoundExpressionTypes.bindBinaryType;
import static dev.trentdb.planner.BoundExpressionTypes.canCast;
import static dev.trentdb.planner.BoundExpressionTypes.commonCaseType;
import static dev.trentdb.planner.BoundExpressionTypes.isComparable;
import static dev.trentdb.planner.BoundExpressionTypes.isNull;
import static dev.trentdb.planner.BoundExpressionTypes.literalType;
import static dev.trentdb.planner.BoundExpressionTypes.logicalType;
import static dev.trentdb.planner.BoundExpressionTypes.typeName;

public final class Binder {
    private record BoundColumnBinding(String relationName, ColumnCatalogEntry column, int ordinal) {
    }

    private record BindingContext(Transaction transaction, List<BoundColumnBinding> columns) {
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
        BindingContext context = bindingContext(transaction, boundFrom);
        BoundExpression where = statement.where() == null ? null : bindWhereExpression(context, statement.where());
        List<BoundExpression> groupBy = bindGroupBy(context, statement.groupBy());
        ArrayList<BoundExpression> selectList = new ArrayList<>();
        ArrayList<String> selectNames = new ArrayList<>();
        ArrayList<HavingAliasResolver.SelectAlias> havingAliases = new ArrayList<>();

        for (SelectItem item : statement.selectItems()) {
            bindSelectExpression(context, item.expression(), item.alias(), selectList, selectNames, true);
            if (item.alias() != null) {
                havingAliases.add(new HavingAliasResolver.SelectAlias(item.alias(), item.expression()));
            }
        }

        BoundExpression having = statement.having() == null
                ? null
                : bindHavingExpression(context, statement.having(), havingAliases);
        validateAggregates(selectList, groupBy, having);
        boolean aggregateQuery = containsAggregate(selectList) || containsAggregate(having) || !groupBy.isEmpty();
        List<BoundOrderByItem> orderBy = bindOrderBy(context, statement.orderBy(), selectList, selectNames, aggregateQuery);
        return new BoundSelectStatement(boundFrom, selectList, selectNames, where, groupBy, having, orderBy, statement.limit());
    }

    private BoundFrom bindFrom(Transaction transaction, SelectStatement statement) {
        BoundFrom left = bindTableRef(transaction, statement.from().base());
        List<JoinClause> joins = statement.from().joins();
        for (JoinClause join : joins) {
            if (join.type() != JoinType.INNER) {
                throw new BinderException("Only INNER JOIN is supported");
            }
            BoundTableRef right = bindTableRef(transaction, join.right());
            BindingContext joinContext = bindingContext(transaction, left, right);
            BoundExpression condition = bindExpression(joinContext, join.condition(), false);
            if (!logicalType(condition).equals(LogicalType.BOOLEAN)) {
                throw new BinderException("JOIN condition must evaluate to BOOLEAN but got " + typeName(logicalType(condition)));
            }
            left = new BoundJoinRef(left, right, condition);
        }
        return left;
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

    private BindingContext bindingContext(Transaction transaction, BoundFrom from) {
        if (from instanceof BoundTableRef tableRef) {
            return bindingContext(transaction, tableRef);
        }
        if (from instanceof BoundJoinRef joinRef) {
            return bindingContext(transaction, joinRef.left(), joinRef.right());
        }
        throw new BinderException("Unsupported FROM source: " + from.getClass().getSimpleName());
    }

    private BindingContext bindingContext(Transaction transaction, BoundTableRef tableRef) {
        ArrayList<BoundColumnBinding> bindings = new ArrayList<>();
        List<ColumnCatalogEntry> tableColumns = columns(tableRef);
        for (int index = 0; index < tableColumns.size(); index++) {
            bindings.add(new BoundColumnBinding(relationName(tableRef), tableColumns.get(index), index));
        }
        return new BindingContext(transaction, bindings);
    }

    private BindingContext bindingContext(Transaction transaction, BoundFrom left, BoundTableRef right) {
        ArrayList<BoundColumnBinding> bindings = new ArrayList<>(bindingContext(transaction, left).columns());
        List<ColumnCatalogEntry> rightColumns = columns(right);
        int offset = bindings.size();
        for (int index = 0; index < rightColumns.size(); index++) {
            bindings.add(new BoundColumnBinding(relationName(right), rightColumns.get(index), offset + index));
        }
        return new BindingContext(transaction, bindings);
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

    private BoundExpression bindHavingExpression(
            BindingContext context,
            Expression expression,
            List<HavingAliasResolver.SelectAlias> aliases
    ) {
        BoundExpression bound = bindExpression(context, new HavingAliasResolver(aliases).resolve(expression), true);
        if (!logicalType(bound).equals(LogicalType.BOOLEAN)) {
            throw new BinderException("HAVING expression must evaluate to BOOLEAN but got " + typeName(logicalType(bound)));
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
        if (expression instanceof IntervalLiteralExpression interval) {
            return new BoundIntervalExpression(interval.amount(), interval.unit());
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
        if (expression instanceof InSubqueryExpression inSubquery) {
            return bindInSubqueryExpression(context, inSubquery, allowAggregates);
        }
        if (expression instanceof CastExpression cast) {
            return bindCastExpression(context, cast, allowAggregates);
        }
        if (expression instanceof CaseExpression caseExpression) {
            return bindCaseExpression(context, caseExpression, allowAggregates);
        }
        if (expression instanceof SubqueryExpression subquery) {
            return bindSubqueryExpression(context, subquery);
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

    private BoundInSubqueryExpression bindInSubqueryExpression(
            BindingContext context,
            InSubqueryExpression in,
            boolean allowAggregates
    ) {
        BoundExpression input = bindExpression(context, in.input(), allowAggregates);
        BoundSelectStatement subquery = bindSelect(context.transaction(), in.subquery());
        LogicalType inputType = logicalType(input);
        LogicalType subqueryType = singleColumnType(subquery, "IN subquery");
        if (!isNull(inputType) && !isNull(subqueryType) && !isComparable(inputType, subqueryType)) {
            throw new BinderException("IN subquery cannot compare "
                    + typeName(inputType) + " and " + typeName(subqueryType));
        }
        return new BoundInSubqueryExpression(input, subquery, in.negated());
    }

    private BoundSubqueryExpression bindSubqueryExpression(BindingContext context, SubqueryExpression subquery) {
        BoundSelectStatement boundSubquery = bindSelect(context.transaction(), subquery.select());
        return new BoundSubqueryExpression(boundSubquery, singleColumnType(boundSubquery, "Scalar subquery"));
    }

    private LogicalType singleColumnType(BoundSelectStatement subquery, String context) {
        if (subquery.selectList().size() != 1) {
            throw new BinderException(context + " must return exactly one column");
        }
        return logicalType(subquery.selectList().getFirst());
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
                arguments.stream().map(BoundExpressionTypes::logicalType).toList()
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
                arguments.stream().map(BoundExpressionTypes::logicalType).toList(),
                functionCall.starArgument()
        );
        return new BoundAggregateExpression(function, arguments, functionCall.starArgument());
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

    private void validateAggregates(
            List<BoundExpression> selectList,
            List<BoundExpression> groupBy,
            BoundExpression having
    ) {
        boolean aggregateQuery = containsAggregate(selectList)
                || containsAggregate(having)
                || !groupBy.isEmpty()
                || having != null;
        if (!aggregateQuery) {
            return;
        }
        for (BoundExpression expression : selectList) {
            validateAggregateSelectExpression(expression, groupBy);
        }
        if (having != null) {
            validateAggregateSelectExpression(having, groupBy);
        }
    }

    private void validateAggregateSelectExpression(BoundExpression expression, List<BoundExpression> groupBy) {
        if (expression instanceof BoundAggregateExpression) {
            return;
        }
        for (BoundExpression group : groupBy) {
            if (group.equals(expression)) {
                return;
            }
        }
        switch (expression) {
            case BoundBinaryExpression binary -> {
                validateAggregateSelectExpression(binary.left(), groupBy);
                validateAggregateSelectExpression(binary.right(), groupBy);
            }
            case BoundBetweenExpression between -> {
                validateAggregateSelectExpression(between.input(), groupBy);
                validateAggregateSelectExpression(between.lower(), groupBy);
                validateAggregateSelectExpression(between.upper(), groupBy);
            }
            case BoundCastExpression cast -> validateAggregateSelectExpression(cast.child(), groupBy);
            case BoundCaseExpression caseExpression -> {
                for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
                    validateAggregateSelectExpression(branch.condition(), groupBy);
                    validateAggregateSelectExpression(branch.result(), groupBy);
                }
                validateAggregateSelectExpression(caseExpression.elseExpression(), groupBy);
            }
            case BoundFunctionExpression function -> {
                for (BoundExpression argument : function.arguments()) {
                    validateAggregateSelectExpression(argument, groupBy);
                }
            }
            case BoundInExpression in -> {
                validateAggregateSelectExpression(in.input(), groupBy);
                for (BoundExpression candidate : in.candidates()) {
                    validateAggregateSelectExpression(candidate, groupBy);
                }
            }
            case BoundInSubqueryExpression in -> validateAggregateSelectExpression(in.input(), groupBy);
            case BoundSubqueryExpression ignored -> {
                return;
            }
            case BoundIntervalExpression ignored -> {
                return;
            }
            case BoundLiteralExpression ignored -> {
                return;
            }
            case BoundColumnRefExpression ignored -> throw new BinderException(
                    "Column must appear in GROUP BY or be used in an aggregate function");
            case BoundOutputColumnExpression ignored -> throw new BinderException(
                    "Column must appear in GROUP BY or be used in an aggregate function");
            case BoundAggregateExpression ignored -> {
                return;
            }
        }
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
        if (expression == null) {
            return false;
        }
        return containsAggregateExpression(expression);
    }

    private boolean containsAggregateExpression(BoundExpression expression) {
        return switch (expression) {
            case BoundAggregateExpression ignored -> true;
            case BoundBinaryExpression binary -> containsAggregateExpression(binary.left())
                    || containsAggregateExpression(binary.right());
            case BoundBetweenExpression between -> containsAggregateExpression(between.input())
                    || containsAggregateExpression(between.lower())
                    || containsAggregateExpression(between.upper());
            case BoundCastExpression cast -> containsAggregateExpression(cast.child());
            case BoundCaseExpression caseExpression -> {
                boolean result = containsAggregateExpression(caseExpression.elseExpression());
                for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
                    if (containsAggregateExpression(branch.condition()) || containsAggregateExpression(branch.result())) {
                        result = true;
                        break;
                    }
                }
                yield result;
            }
            case BoundColumnRefExpression ignored -> false;
            case BoundInExpression in -> {
                boolean result = containsAggregateExpression(in.input());
                for (BoundExpression candidate : in.candidates()) {
                    if (containsAggregateExpression(candidate)) {
                        result = true;
                        break;
                    }
                }
                yield result;
            }
            case BoundInSubqueryExpression in -> containsAggregate(in.input());
            case BoundSubqueryExpression ignored -> false;
            case BoundOutputColumnExpression ignored -> false;
            case BoundFunctionExpression function -> {
                boolean result = false;
                for (BoundExpression argument : function.arguments()) {
                    if (containsAggregateExpression(argument)) {
                        result = true;
                        break;
                    }
                }
                yield result;
            }
            case BoundLiteralExpression ignored -> false;
            case BoundIntervalExpression ignored -> false;
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
