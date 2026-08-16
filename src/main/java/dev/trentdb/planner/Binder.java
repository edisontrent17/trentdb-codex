package dev.trentdb.planner;

import dev.trentdb.ast.BinaryExpression;
import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.ast.BetweenExpression;
import dev.trentdb.ast.CaseExpression;
import dev.trentdb.ast.CastExpression;
import dev.trentdb.ast.ColumnReferenceExpression;
import dev.trentdb.ast.CommonTableExpression;
import dev.trentdb.ast.ExplainStatement;
import dev.trentdb.ast.CreateTableStatement;
import dev.trentdb.ast.CreateIndexStatement;
import dev.trentdb.ast.DropTableStatement;
import dev.trentdb.ast.DropIndexStatement;
import dev.trentdb.ast.DeleteStatement;
import dev.trentdb.ast.UpdateStatement;
import dev.trentdb.ast.Expression;
import dev.trentdb.ast.ExistsExpression;
import dev.trentdb.ast.FunctionCallExpression;
import dev.trentdb.ast.InExpression;
import dev.trentdb.ast.InSubqueryExpression;
import dev.trentdb.ast.IntervalLiteralExpression;
import dev.trentdb.ast.InsertStatement;
import dev.trentdb.ast.JoinClause;
import dev.trentdb.ast.JoinType;
import dev.trentdb.ast.LiteralExpression;
import dev.trentdb.ast.LiteralKind;
import dev.trentdb.ast.NullCheckExpression;
import dev.trentdb.ast.OrderByItem;
import dev.trentdb.ast.SelectItem;
import dev.trentdb.ast.SelectStatement;
import dev.trentdb.ast.StarExpression;
import dev.trentdb.ast.Statement;
import dev.trentdb.ast.SubqueryExpression;
import dev.trentdb.ast.TableReference;
import dev.trentdb.ast.UnaryExpression;
import dev.trentdb.ast.UnaryOperator;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.catalog.CatalogException;
import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.catalog.TableCatalogEntry;
import dev.trentdb.function.FunctionRegistry;
import dev.trentdb.replacement.ReplacementScanRegistry;
import dev.trentdb.planner.BoundCreateTableStatement;
import dev.trentdb.planner.BoundDropTableStatement;
import dev.trentdb.planner.BoundCreateIndexStatement;
import dev.trentdb.planner.BoundDropIndexStatement;
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
        if (statement instanceof CreateTableStatement create) {
            return new BoundCreateTableStatement(create);
        }
        if (statement instanceof DropTableStatement drop) {
            return new BoundDropTableStatement(drop);
        }
        if (statement instanceof CreateIndexStatement create) {
            var table = catalog.lookupTable(transaction, create.tableName());
            var names = new java.util.HashSet<String>();
            for (var key : create.keys()) {
                if (!names.add(key.columnName())) throw new BinderException("Index key specified more than once: " + key.columnName());
                try {
                    table.lookupColumn(key.columnName());
                } catch (CatalogException failure) {
                    throw new BinderException("Column not found for index: " + key.columnName());
                }
            }
            try {
                catalog.lookupIndex(transaction, create.name());
                throw new BinderException("Index already exists: " + create.name().last());
            } catch (CatalogException notFound) {
                if (!notFound.getMessage().startsWith("Index not found:")) throw notFound;
            }
            return new BoundCreateIndexStatement(create);
        }
        if (statement instanceof DropIndexStatement drop) {
            try {
                catalog.lookupIndex(transaction, drop.name());
            } catch (CatalogException failure) {
                throw new BinderException(failure.getMessage());
        }
            return new BoundDropIndexStatement(drop);
        }
        if (statement instanceof DeleteStatement delete) {
            var table = catalog.lookupTable(transaction, delete.tableName());
            BoundExpression predicate = delete.where() == null ? null : bindExpression(bindingContext(new BindScope(transaction), new BoundTableRef(table, null)), delete.where(), false);
            if (predicate != null && !logicalType(predicate).equals(LogicalType.BOOLEAN)) throw new BinderException("DELETE WHERE must evaluate to BOOLEAN");
            if (predicate != null && !supportsDeletePredicate(predicate)) throw new BinderException("DELETE WHERE supports only row-local scalar expressions (columns, literals, casts, comparisons, AND/OR, BETWEEN, IN, and IS [NOT] NULL)");
            return new BoundDeleteStatement(table, predicate);
        }
        if (statement instanceof UpdateStatement update) {
            var table = catalog.lookupTable(transaction, update.tableName());
            var context = bindingContext(new BindScope(transaction), new BoundTableRef(table, null));
            var target = table.lookupColumn(update.columnName());
            var value = bindExpression(context, update.value(), false);
            value = coerceIntegerLiteral(update.value(), value, target.logicalType());
            if (!supportsUpdateValue(value)) throw new BinderException("UPDATE SET supports only row-local columns, literals, casts, and binary scalar expressions");
            if (!isInsertAssignable(logicalType(value), target.logicalType())) throw new BinderException("UPDATE value type " + typeName(logicalType(value)) + " cannot be assigned to " + typeName(target.logicalType()));
            BoundExpression predicate = update.where() == null ? null : bindExpression(context, update.where(), false);
            if (predicate != null && !logicalType(predicate).equals(LogicalType.BOOLEAN)) throw new BinderException("UPDATE WHERE must evaluate to BOOLEAN");
            if (predicate != null && !supportsDeletePredicate(predicate)) throw new BinderException("UPDATE WHERE supports only row-local scalar expressions (columns, literals, casts, comparisons, AND/OR, BETWEEN, IN, and IS [NOT] NULL)");
            return new BoundUpdateStatement(table, target.ordinal(), value, predicate);
        }
        if (statement instanceof InsertStatement insert) {
            return bindInsert(transaction, insert);
        }
        if (statement instanceof SelectStatement select) {
            return bindSelect(transaction, select);
        }
        throw new BinderException("Unsupported statement for binding: " + statement.getClass().getSimpleName());
    }

    private boolean supportsDeletePredicate(BoundExpression expression) { if (expression instanceof BoundColumnRefExpression || expression instanceof BoundLiteralExpression) return true; if (expression instanceof BoundCastExpression cast) return supportsDeletePredicate(cast.child()); if (expression instanceof BoundNullCheckExpression check) return supportsDeletePredicate(check.expression()); if (expression instanceof BoundBinaryExpression binary) return supportsDeletePredicate(binary.left()) && supportsDeletePredicate(binary.right()); if (expression instanceof BoundBetweenExpression between) return supportsDeletePredicate(between.input()) && supportsDeletePredicate(between.lower()) && supportsDeletePredicate(between.upper()); if (expression instanceof BoundInExpression in) return supportsDeletePredicate(in.input()) && in.candidates().stream().allMatch(this::supportsDeletePredicate); return false; }
    private boolean supportsUpdateValue(BoundExpression expression) { if (expression instanceof BoundColumnRefExpression || expression instanceof BoundLiteralExpression) return true; if (expression instanceof BoundCastExpression cast) return supportsUpdateValue(cast.child()); if (expression instanceof BoundBinaryExpression binary) return supportsUpdateValue(binary.left()) && supportsUpdateValue(binary.right()); return false; }
    private BoundInsertStatement bindInsert(Transaction transaction, InsertStatement statement) {
        var table = catalog.lookupTable(transaction, statement.tableName());
        var targetOrdinals = new ArrayList<Integer>();
        if (statement.columns().isEmpty()) { for (var column : table.columns()) targetOrdinals.add(column.ordinal()); } else { var seen = new java.util.HashSet<Integer>(); for (String name : statement.columns()) { var ordinal = table.lookupColumn(name).ordinal(); if (!seen.add(ordinal)) throw new BinderException("INSERT column specified more than once: " + name); targetOrdinals.add(ordinal); } }
        if (statement.rows().isEmpty()) throw new BinderException("INSERT requires at least one VALUES row");
        var context = new BindingContext(new BindScope(transaction), List.of(), 0); var rows = new ArrayList<List<BoundLiteralExpression>>(statement.rows().size());
        for (int rowIndex = 0; rowIndex < statement.rows().size(); rowIndex++) { var source = statement.rows().get(rowIndex); if (source.size() != targetOrdinals.size()) throw new BinderException("INSERT row " + (rowIndex + 1) + " value count does not match target column count"); var values = new ArrayList<BoundLiteralExpression>(source.size()); for (int index = 0; index < source.size(); index++) { var targetType = table.columns().get(targetOrdinals.get(index)).logicalType(); values.add(bindInsertLiteral(context, source.get(index), targetType)); } rows.add(List.copyOf(values)); }
        return new BoundInsertStatement(table, targetOrdinals, rows.getFirst(), rows);
    }
    private BoundLiteralExpression bindInsertLiteral(BindingContext context, Expression source, LogicalType target) {
        if (!(source instanceof LiteralExpression) && !isSignedIntegerLiteral(source)) throw new BinderException("Only literal VALUES expressions are supported for INSERT");
        BoundExpression bound = bindExpression(context, source, false);
        bound = coerceIntegerLiteral(source, bound, target);
        if (!(bound instanceof BoundLiteralExpression literal)) throw new BinderException("Only literal VALUES expressions are supported for INSERT");
        if (!isInsertAssignable(literal.logicalType(), target)) throw new BinderException("INSERT value type " + typeName(literal.logicalType()) + " cannot be assigned to " + typeName(target));
        return literal;
    }
    private boolean isSignedIntegerLiteral(Expression source) {
        return source instanceof UnaryExpression unary && unary.operator() == UnaryOperator.MINUS
                && unary.expression() instanceof LiteralExpression literal && literal.kind() == LiteralKind.INTEGER;
    }
    private BoundExpression coerceIntegerLiteral(Expression source, BoundExpression bound, LogicalType target) {
        if (bound instanceof BoundLiteralExpression literal) return coerceIntegerLiteral(literal, target);
        if (!isSignedIntegerLiteral(source)) return bound;
        var unary = (UnaryExpression) source;
        var literal = (LiteralExpression) unary.expression();
        return coerceIntegerLiteral(new BoundLiteralExpression(LogicalType.BIGINT, -((Long) literal.value())), target);
    }
    private BoundLiteralExpression coerceIntegerLiteral(BoundLiteralExpression literal, LogicalType target) {
        if (!literal.logicalType().equals(LogicalType.BIGINT) || !target.equals(LogicalType.INTEGER) || literal.value() == null) return literal;
        long value = ((Number) literal.value()).longValue();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new BinderException("BIGINT literal is out of range for INTEGER: " + value);
        return new BoundLiteralExpression(LogicalType.INTEGER, (int) value);
    }



    private boolean isInsertAssignable(LogicalType source, LogicalType target) {
        return isNull(source) || source.equals(target)
                || (source.equals(LogicalType.INTEGER) && (target.equals(LogicalType.BIGINT) || target.equals(LogicalType.DOUBLE)))
                || (source.equals(LogicalType.BIGINT) && target.equals(LogicalType.DOUBLE));
    }


    public BoundSelectStatement bindSelect(Transaction transaction, SelectStatement statement) {
        return bindSelect(new BindScope(transaction), statement);
    }

    private BoundSelectStatement bindSelect(BindScope scope, SelectStatement statement) {
        return bindSelect(scope, statement, List.of());
    }

    private BoundSelectStatement bindSelect(
            BindScope scope,
            SelectStatement statement,
            List<BoundColumnBinding> outerColumns
    ) {
        BindScope selectScope = scope.withCommonTableExpressions(statement.commonTableExpressions());
        if (statement.isCompound()) {
            if (!statement.orderBy().isEmpty() || statement.limit() != null) {
                throw new BinderException("ORDER BY and LIMIT on compound SELECT are not supported yet");
            }
            BoundSelectStatement left = bindSelect(selectScope, statement.left(), outerColumns);
            BoundSelectStatement right = bindSelect(selectScope, statement.right(), outerColumns);
            return bindSetOperation(statement.setOperation(), left, right);
        }
        BoundFrom boundFrom = bindFrom(selectScope, statement);
        BindingContext localContext = bindingContext(selectScope, boundFrom);
        BindingContext context = withOuterColumns(localContext, outerColumns);
        BoundExpression where = statement.where() == null ? null : bindWhereExpression(context, statement.where());
        List<HavingAliasResolver.SelectAlias> aliases = selectAliases(statement.selectItems());
        List<BoundExpression> groupBy = bindGroupBy(context, statement.groupBy(), aliases);
        ArrayList<BoundExpression> selectList = new ArrayList<>();
        ArrayList<String> selectNames = new ArrayList<>();

        for (SelectItem item : statement.selectItems()) {
            bindSelectExpression(context, item.expression(), item.alias(), selectList, selectNames, true);
        }

        BoundExpression having = statement.having() == null
                ? null
                : bindHavingExpression(context, statement.having(), aliases);
        AggregateBindingValidator.validate(selectList, groupBy, having);
        boolean aggregateQuery = BoundExpressionInspector.containsAggregate(selectList)
                || BoundExpressionInspector.containsAggregate(having)
                || !groupBy.isEmpty();
        List<BoundOrderByItem> orderBy = bindOrderBy(context, statement.orderBy(), selectList, selectNames, aggregateQuery);
        return new BoundSelectStatement(boundFrom, selectList, selectNames, where, groupBy, having, orderBy, statement.limit());
    }

    private BoundSelectStatement bindSetOperation(
            dev.trentdb.ast.SetOperation operation,
            BoundSelectStatement left,
            BoundSelectStatement right
    ) {
        if (left.selectList().size() != right.selectList().size()) {
            throw new BinderException("Set operation column count mismatch: left has " + left.selectList().size()
                    + " columns but right has " + right.selectList().size());
        }
        ArrayList<LogicalType> outputTypes = new ArrayList<>(left.selectList().size());
        for (int index = 0; index < left.selectList().size(); index++) {
            LogicalType leftType = logicalType(left.selectList().get(index));
            LogicalType rightType = logicalType(right.selectList().get(index));
            try {
                outputTypes.add(commonCaseType(List.of(leftType, rightType)));
            } catch (BinderException failure) {
                throw new BinderException("Set operation column " + (index + 1) + " has incompatible types: "
                        + typeName(leftType) + " and " + typeName(rightType));
            }
        }
        return BoundSelectStatement.setOperation(operation, coerceSetOutputs(left, outputTypes), coerceSetOutputs(right, outputTypes), outputTypes);
    }
    private BoundSelectStatement coerceSetOutputs(BoundSelectStatement statement, List<LogicalType> outputTypes) {
        ArrayList<BoundExpression> expressions = new ArrayList<>(outputTypes.size());
        for (int index = 0; index < outputTypes.size(); index++) {
            BoundExpression expression = statement.selectList().get(index);
            LogicalType sourceType = logicalType(expression);
            LogicalType targetType = outputTypes.get(index);
            if (!sourceType.equals(targetType)) {
                if (!canCast(sourceType, targetType)) {
                    throw new BinderException("Set operation cannot cast " + typeName(sourceType) + " to " + typeName(targetType));
                }
                expression = new BoundCastExpression(expression, targetType);
            }
            expressions.add(expression);
        }
        return statement.withSelectList(expressions);
    }

    private BindingContext withOuterColumns(BindingContext localContext, List<BoundColumnBinding> outerColumns) {
        if (outerColumns.isEmpty()) {
            return localContext;
        }
        ArrayList<BoundColumnBinding> columns = new ArrayList<>(localContext.columns());
        int localColumnCount = localContext.columns().size();
        for (int index = 0; index < outerColumns.size(); index++) {
            BoundColumnBinding outer = outerColumns.get(index);
            columns.add(new BoundColumnBinding(outer.relationName(), outer.column(), localColumnCount + index));
        }
        return new BindingContext(localContext.scope(), columns, localContext.starColumnCount());
    }

    private BoundFrom bindFrom(BindScope scope, SelectStatement statement) {
        BoundFrom left = bindRelationRef(scope, statement.from().base());
        List<JoinClause> joins = statement.from().joins();
        for (JoinClause join : joins) {
            if (join.type() != JoinType.INNER && join.type() != JoinType.LEFT) {
                throw new BinderException("Only INNER and LEFT JOIN are supported");
            }
            BoundTableRef right = bindTableRef(scope.transaction(), join.right());
            BindingContext joinContext = bindingContext(scope, left, right);
            BoundExpression condition = bindExpression(joinContext, join.condition(), false);
            if (!logicalType(condition).equals(LogicalType.BOOLEAN)) {
                throw new BinderException("JOIN condition must evaluate to BOOLEAN but got " + typeName(logicalType(condition)));
            }
            left = new BoundJoinRef(left, right, condition, join.type());
        }
        return left;
    }

    private BoundFrom bindRelationRef(BindScope scope, TableReference tableReference) {
        if (tableReference.isSubquery()) {
            return bindSubqueryRef(scope, tableReference);
        }
        CommonTableExpression commonTableExpression = scope.findCommonTableExpression(tableReference.name());
        if (commonTableExpression != null) {
            return bindCommonTableExpressionRef(scope, tableReference, commonTableExpression);
        }
        return bindTableRef(scope.transaction(), tableReference);
    }

    private BoundTableRef bindTableRef(Transaction transaction, TableReference tableReference) {
        if (tableReference.isSubquery()) {
            throw new BinderException("Derived tables are not supported on the right side of JOIN yet");
        }
        if (!tableReference.columnAliases().isEmpty()) {
            throw new BinderException("Column aliases for base table references are not supported yet");
        }
        if (tableReference.isPath()) {
            return BoundTableRef.replacement(replacementScanRegistry.replace(tableReference.path()), tableReference.alias());
        }
        try {
            return new BoundTableRef(catalog.lookupTable(transaction, tableReference.name()), tableReference.alias());
        } catch (CatalogException exception) {
            throw exception;
        }
    }

    private BoundSubqueryRef bindSubqueryRef(BindScope scope, TableReference tableReference) {
        BoundSelectStatement subquery = bindSelect(scope, tableReference.subquery());
        List<String> outputNames = tableReference.columnAliases().isEmpty()
                ? subquery.selectNames()
                : tableReference.columnAliases();
        return bindSubqueryRef(subquery, relationName(tableReference), outputNames);
    }

    private BoundSubqueryRef bindCommonTableExpressionRef(
            BindScope scope,
            TableReference tableReference,
            CommonTableExpression commonTableExpression
    ) {
        BoundSelectStatement subquery = bindSelect(
                scope.enterCommonTableExpression(commonTableExpression.name()),
                commonTableExpression.select()
        );
        List<String> outputNames = tableReference.columnAliases().isEmpty()
                ? commonTableExpression.columnAliases()
                : tableReference.columnAliases();
        if (outputNames.isEmpty()) {
            outputNames = subquery.selectNames();
        }
        String relationName = tableReference.alias() == null ? commonTableExpression.name() : tableReference.alias();
        return bindSubqueryRef(subquery, relationName, outputNames);
    }

    private BoundSubqueryRef bindSubqueryRef(
            BoundSelectStatement subquery,
            String relationName,
            List<String> outputNames
    ) {
        if (outputNames.size() != subquery.selectList().size()) {
            throw new BinderException("Derived table column alias count does not match output column count");
        }
        ArrayList<ColumnCatalogEntry> columns = new ArrayList<>(outputNames.size());
        for (int index = 0; index < outputNames.size(); index++) {
            columns.add(new ColumnCatalogEntry(outputNames.get(index), logicalType(subquery.selectList().get(index)), index));
        }
        return new BoundSubqueryRef(subquery, relationName, columns);
    }

    private BindingContext bindingContext(BindScope scope, BoundFrom from) {
        if (from instanceof BoundTableRef tableRef) {
            return bindingContext(scope, tableRef);
        }
        if (from instanceof BoundSubqueryRef subqueryRef) {
            return bindingContext(scope, subqueryRef);
        }
        if (from instanceof BoundJoinRef joinRef) {
            return bindingContext(scope, joinRef.left(), joinRef.right());
        }
        throw new BinderException("Unsupported FROM source: " + from.getClass().getSimpleName());
    }

    private BindingContext bindingContext(BindScope scope, BoundTableRef tableRef) {
        ArrayList<BoundColumnBinding> bindings = new ArrayList<>();
        List<ColumnCatalogEntry> tableColumns = columns(tableRef);
        for (int index = 0; index < tableColumns.size(); index++) {
            bindings.add(new BoundColumnBinding(relationName(tableRef), tableColumns.get(index), index));
        }
        return new BindingContext(scope, bindings, bindings.size());
    }

    private BindingContext bindingContext(BindScope scope, BoundSubqueryRef subqueryRef) {
        ArrayList<BoundColumnBinding> bindings = new ArrayList<>();
        for (ColumnCatalogEntry column : subqueryRef.columns()) {
            bindings.add(new BoundColumnBinding(relationName(subqueryRef), column, column.ordinal()));
        }
        return new BindingContext(scope, bindings, bindings.size());
    }

    private BindingContext bindingContext(BindScope scope, BoundFrom left, BoundTableRef right) {
        ArrayList<BoundColumnBinding> bindings = new ArrayList<>(bindingContext(scope, left).columns());
        List<ColumnCatalogEntry> rightColumns = columns(right);
        int offset = bindings.size();
        for (int index = 0; index < rightColumns.size(); index++) {
            bindings.add(new BoundColumnBinding(relationName(right), rightColumns.get(index), offset + index));
        }
        return new BindingContext(scope, bindings, bindings.size());
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
            for (int index = 0; index < context.starColumnCount(); index++) {
                BoundColumnBinding column = context.columns().get(index);
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

    private List<HavingAliasResolver.SelectAlias> selectAliases(List<SelectItem> selectItems) {
        ArrayList<HavingAliasResolver.SelectAlias> aliases = new ArrayList<>();
        for (SelectItem item : selectItems) {
            if (item.alias() != null) {
                aliases.add(new HavingAliasResolver.SelectAlias(item.alias(), item.expression()));
            }
        }
        return aliases;
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

    private List<BoundExpression> bindGroupBy(
            BindingContext context,
            List<Expression> groupBy,
            List<HavingAliasResolver.SelectAlias> aliases
    ) {
        ArrayList<BoundExpression> result = new ArrayList<>(groupBy.size());
        for (Expression expression : groupBy) {
            result.add(bindGroupByExpression(context, expression, aliases));
        }
        return result;
    }

    private BoundExpression bindGroupByExpression(
            BindingContext context,
            Expression expression,
            List<HavingAliasResolver.SelectAlias> aliases
    ) {
        try {
            return bindExpression(context, expression, false);
        } catch (CatalogException exception) {
            Expression aliasExpression = new GroupByAliasResolver(aliases).resolve(expression);
            if (aliasExpression == expression) {
                throw exception;
            }
            return bindExpression(context, aliasExpression, false);
        }
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

    private BoundExpression bindExpression(BindingContext context, Expression expression, boolean allowAggregates) {
        if (expression instanceof ColumnReferenceExpression columnReference) {
            return ColumnBinder.bind(context, columnReference.name());
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
        if (expression instanceof UnaryExpression unary) {
            return UnaryExpressionBinder.bind(unary.operator(), bindExpression(context, unary.expression(), allowAggregates));
        }
        if (expression instanceof NullCheckExpression nullCheck) {
            return new BoundNullCheckExpression(
                    bindExpression(context, nullCheck.expression(), allowAggregates),
                    nullCheck.negated()
            );
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
        if (expression instanceof ExistsExpression exists) {
            return bindExistsExpression(context, exists);
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
        return new BoundBetweenExpression(input, lower, upper, between.negated());
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
        BoundSelectStatement subquery = bindSelect(context.scope(), in.subquery());
        LogicalType inputType = logicalType(input);
        LogicalType subqueryType = singleColumnType(subquery, "IN subquery");
        if (!isNull(inputType) && !isNull(subqueryType) && !isComparable(inputType, subqueryType)) {
            throw new BinderException("IN subquery cannot compare "
                    + typeName(inputType) + " and " + typeName(subqueryType));
        }
        return new BoundInSubqueryExpression(input, subquery, in.negated());
    }

    private BoundSubqueryExpression bindSubqueryExpression(BindingContext context, SubqueryExpression subquery) {
        BoundSelectStatement boundSubquery = bindSelect(context.scope(), subquery.select(), context.columns());
        int localColumnCount = bindingContext(context.scope(), boundSubquery.from()).columns().size();
        List<BoundExistsSubqueryExpression.CorrelatedColumn> correlatedColumns = BoundExpressionInspector
                .containsColumnOrdinalAtLeast(boundSubquery, localColumnCount) ? correlatedColumns(context.columns()) : List.of();
        return new BoundSubqueryExpression(
                boundSubquery, singleColumnType(boundSubquery, "Scalar subquery"), localColumnCount, correlatedColumns);
    }

    private BoundExistsSubqueryExpression bindExistsExpression(BindingContext context, ExistsExpression exists) {
        BoundSelectStatement subquery = bindSelect(context.scope(), exists.select(), context.columns());
        int localColumnCount = bindingContext(context.scope(), subquery.from()).columns().size();
        List<BoundExistsSubqueryExpression.CorrelatedColumn> correlatedColumns =
                BoundExpressionInspector.containsColumnOrdinalAtLeastOutsideProjection(subquery, localColumnCount)
                        ? correlatedColumns(context.columns())
                        : List.of();
        return new BoundExistsSubqueryExpression(subquery, localColumnCount, correlatedColumns);
    }

    private List<BoundExistsSubqueryExpression.CorrelatedColumn> correlatedColumns(List<BoundColumnBinding> columns) {
        ArrayList<BoundExistsSubqueryExpression.CorrelatedColumn> result = new ArrayList<>(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            BoundColumnBinding column = columns.get(index);
            result.add(new BoundExistsSubqueryExpression.CorrelatedColumn(
                    column.column().name(),
                    column.column().logicalType(),
                    column.ordinal()
            ));
        }
        return result;
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
        BoundExpression baseExpression = caseExpression.baseExpression() == null
                ? null
                : bindExpression(context, caseExpression.baseExpression(), allowAggregates);
        ArrayList<BoundCaseExpression.WhenClause> branches = new ArrayList<>(caseExpression.branches().size());
        ArrayList<LogicalType> resultTypes = new ArrayList<>(caseExpression.branches().size() + 1);
        for (CaseExpression.WhenClause branch : caseExpression.branches()) {
            BoundExpression condition = bindExpression(context, branch.condition(), allowAggregates);
            if (baseExpression == null) {
                if (!logicalType(condition).equals(LogicalType.BOOLEAN) && !isNull(logicalType(condition))) {
                    throw new BinderException("CASE WHEN condition must evaluate to BOOLEAN but got "
                            + typeName(logicalType(condition)));
                }
            } else {
                // Simple CASE uses SQL equality semantics, including the normal numeric coercions.
                bindBinaryType(BinaryOperator.EQUAL, logicalType(baseExpression), logicalType(condition));
            }
            BoundExpression result = bindExpression(context, branch.result(), allowAggregates);
            resultTypes.add(logicalType(result));
            branches.add(new BoundCaseExpression.WhenClause(condition, result));
        }
        BoundExpression elseExpression = caseExpression.elseExpression() == null
                ? new BoundLiteralExpression(LogicalType.NULL, null)
                : bindExpression(context, caseExpression.elseExpression(), allowAggregates);
        resultTypes.add(logicalType(elseExpression));
        return new BoundCaseExpression(baseExpression, branches, elseExpression, commonCaseType(resultTypes));
    }

    private BoundExpression bindFunctionCall(BindingContext context, FunctionCallExpression functionCall, boolean allowAggregates) {
        if (functionRegistry.isAggregate(functionCall.name())) {
            if (!allowAggregates) {
                throw new BinderException("Aggregate functions are not allowed in this clause: " + functionCall.name());
            }
            return bindAggregateFunctionCall(context, functionCall);
        }
        if (functionCall.distinct()) {
            throw new BinderException("DISTINCT is only supported for aggregate functions");
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
        if (functionCall.distinct() && functionCall.starArgument()) {
            throw new BinderException("DISTINCT aggregate functions do not accept *");
        }
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
        return new BoundAggregateExpression(function, arguments, functionCall.starArgument(), functionCall.distinct());
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

    private String relationName(BoundSubqueryRef subquery) {
        return subquery.relationName();
    }

    private String relationName(TableReference tableReference) {
        return tableReference.alias() == null ? "subquery" : tableReference.alias();
    }
}
