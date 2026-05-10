package dev.trentdb.execution.physical;

import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.ast.JoinType;
import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.execution.ExecutionException;
import dev.trentdb.execution.ExpressionExecutor;
import dev.trentdb.planner.BoundAggregateExpression;
import dev.trentdb.planner.BoundBetweenExpression;
import dev.trentdb.planner.BoundBinaryExpression;
import dev.trentdb.planner.BoundCaseExpression;
import dev.trentdb.planner.BoundCastExpression;
import dev.trentdb.planner.BoundColumnRefExpression;
import dev.trentdb.planner.BoundExistsSubqueryExpression;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundFunctionExpression;
import dev.trentdb.planner.BoundInExpression;
import dev.trentdb.planner.BoundInSubqueryExpression;
import dev.trentdb.planner.BoundIntervalExpression;
import dev.trentdb.planner.BoundLiteralExpression;
import dev.trentdb.planner.BoundOutputColumnExpression;
import dev.trentdb.planner.BoundExpressionTypes;
import dev.trentdb.planner.BoundSubqueryExpression;
import dev.trentdb.planner.logical.LogicalAggregate;
import dev.trentdb.planner.logical.LogicalDependentJoin;
import dev.trentdb.planner.logical.LogicalExplain;
import dev.trentdb.planner.logical.LogicalFilter;
import dev.trentdb.planner.logical.LogicalGet;
import dev.trentdb.planner.logical.LogicalJoin;
import dev.trentdb.planner.logical.LogicalLimit;
import dev.trentdb.planner.logical.LogicalOperator;
import dev.trentdb.planner.logical.LogicalOrder;
import dev.trentdb.planner.logical.LogicalProjection;
import dev.trentdb.planner.BoundTableRef;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.types.LogicalType;

import java.util.ArrayList;
import java.util.List;

public final class PhysicalPlanner {
    private record HashJoinKeys(int leftKeyOrdinal, int rightKeyOrdinal) {
    }
    private record HashJoinPlan(HashJoinKeys keys, BoundExpression residualPredicate) {
    }
    private record JoinFilterSplit(
            BoundExpression leftPredicate,
            BoundExpression rightPredicate,
            BoundExpression residualPredicate
    ) {
    }

    private final StorageManager storageManager;
    private final ExpressionExecutor expressionExecutor;

    public PhysicalPlanner(StorageManager storageManager) {
        this.storageManager = storageManager;
        this.expressionExecutor = new ExpressionExecutor(storageManager);
    }

    public Pipeline plan(LogicalOperator logical) {
        PhysicalResultCollector sink = new PhysicalResultCollector();
        if (logical instanceof LogicalExplain explain) {
            Pipeline physicalPlan = planQuery(explain.child());
            return new Pipeline(new PhysicalExplain(explain.child(), physicalPlan), java.util.List.of(), sink);
        }
        return planQuery(logical);
    }

    private Pipeline planQuery(LogicalOperator logical) {
        PhysicalResultCollector sink = new PhysicalResultCollector();
        ArrayList<PhysicalOperator> operators = new ArrayList<>();
        PhysicalSource source = buildPipeline(logical, operators);
        return new Pipeline(source, operators, sink);
    }

    private PhysicalSource buildPipeline(LogicalOperator logical, ArrayList<PhysicalOperator> operators) {
        if (logical instanceof LogicalProjection projection) {
            PhysicalSource source = buildPipeline(projection.child(), operators);
            operators.add(new PhysicalProjection(projection.expressions(), projection.names(), expressionExecutor));
            return source;
        }
        if (logical instanceof LogicalAggregate aggregate) {
            PhysicalSource source = buildPipeline(aggregate.child(), operators);
            operators.add(new PhysicalHashAggregate(
                    aggregate.groups(),
                    aggregate.selectList(),
                    aggregate.selectNames(),
                    expressionExecutor
            ));
            return source;
        }
        if (logical instanceof LogicalFilter filter) {
            if (filter.child() instanceof LogicalJoin join) {
                if (join.joinType() != JoinType.INNER) {
                    PhysicalSource source = buildPipeline(filter.child(), operators);
                    operators.add(new PhysicalFilter(filter.predicate(), expressionExecutor));
                    return source;
                }
                JoinFilterSplit split = splitJoinFilter(join, filter.predicate());
                LogicalOperator left = join.left();
                if (split.leftPredicate() != null) {
                    left = new LogicalFilter(split.leftPredicate(), left);
                }
                PhysicalSource source = buildPipeline(left, operators);
                addJoinOperator(join, operators, split.rightPredicate(), split.residualPredicate());
                return source;
            }
            PhysicalSource source = buildPipeline(filter.child(), operators);
            operators.add(new PhysicalFilter(filter.predicate(), expressionExecutor));
            return source;
        }
        if (logical instanceof LogicalDependentJoin join) {
            PhysicalSource source = buildPipeline(join.child(), operators);
            List<ColumnCatalogEntry> leftColumns = columns(join.child());
            if (join.kind() == LogicalDependentJoin.Kind.MARK) {
                operators.add(new PhysicalCorrelatedExistsMarkJoin(
                        storageManager,
                        names(leftColumns),
                        types(leftColumns),
                        join.subquery(),
                        join.marker(),
                        expressionExecutor
                ));
            } else {
                operators.add(new PhysicalCorrelatedScalarAggregateJoin(
                        storageManager,
                        names(leftColumns),
                        types(leftColumns),
                        join.scalarSubquery(),
                        join.marker(),
                        expressionExecutor
                ));
            }
            return source;
        }
        if (logical instanceof LogicalLimit limit) {
            PhysicalSource source = buildPipeline(limit.child(), operators);
            operators.add(new PhysicalLimit(limit.limit()));
            return source;
        }
        if (logical instanceof LogicalOrder order) {
            PhysicalSource source = buildPipeline(order.child(), operators);
            operators.add(new PhysicalOrder(order.orders(), expressionExecutor));
            return source;
        }
        if (logical instanceof LogicalGet get) {
            return new PhysicalTableScan(storageManager, get.tableRef());
        }
        if (logical instanceof LogicalJoin join) {
            PhysicalSource source = buildPipeline(join.left(), operators);
            addJoinOperator(join, operators, null, null);
            return source;
        }
        throw new ExecutionException("Unsupported logical operator for physical planning: " + logical.getClass().getSimpleName());
    }

    private void addJoinOperator(
            LogicalJoin join,
            ArrayList<PhysicalOperator> operators,
            BoundExpression rightPredicate,
            BoundExpression residualPredicate
    ) {
        BoundTableRef right = rightTable(join.right());
        List<ColumnCatalogEntry> leftColumns = columns(join.left());
        List<String> leftNames = names(leftColumns);
        List<LogicalType> leftTypes = types(leftColumns);
        BoundExpression joinPredicate = combineNullable(join.condition(), residualPredicate);
        HashJoinPlan hashJoinPlan = hashJoinPlan(join.left(), right, joinPredicate);
        if (hashJoinPlan != null) {
            operators.add(new PhysicalHashJoin(
                    storageManager,
                    leftNames,
                    leftTypes,
                    right,
                    join.joinType(),
                    hashJoinPlan.keys().leftKeyOrdinal(),
                    hashJoinPlan.keys().rightKeyOrdinal(),
                    rightPredicate,
                    hashJoinPlan.residualPredicate(),
                    expressionExecutor
            ));
            return;
        }
        operators.add(new PhysicalNestedLoopJoin(
                storageManager,
                leftNames,
                leftTypes,
                right,
                join.joinType(),
                joinPredicate,
                rightPredicate,
                expressionExecutor
        ));
    }

    private HashJoinPlan hashJoinPlan(LogicalOperator left, BoundTableRef right, BoundExpression condition) {
        ArrayList<BoundExpression> conjuncts = new ArrayList<>();
        flattenConjuncts(condition, conjuncts);
        for (int index = 0; index < conjuncts.size(); index++) {
            HashJoinKeys keys = hashJoinKeys(left, right, conjuncts.get(index));
            if (keys == null) {
                continue;
            }
            ArrayList<BoundExpression> residuals = new ArrayList<>(conjuncts);
            residuals.remove(index);
            return new HashJoinPlan(keys, combineConjuncts(residuals));
        }
        return null;
    }

    private HashJoinKeys hashJoinKeys(LogicalOperator left, BoundTableRef right, BoundExpression condition) {
        if (!(condition instanceof BoundBinaryExpression binary)) {
            return null;
        }
        if (binary.operator() != BinaryOperator.EQUAL) {
            return null;
        }
        if (!(binary.left() instanceof BoundColumnRefExpression leftExpression)
                || !(binary.right() instanceof BoundColumnRefExpression rightExpression)) {
            return null;
        }

        List<ColumnCatalogEntry> leftColumns = columns(left);
        List<ColumnCatalogEntry> rightColumns = columns(right);
        int leftColumnCount = leftColumns.size();
        int rightColumnCount = rightColumns.size();

        SideOrdinal leftSide = sideOrdinal(leftExpression.ordinal(), leftColumnCount, rightColumnCount);
        SideOrdinal rightSide = sideOrdinal(rightExpression.ordinal(), leftColumnCount, rightColumnCount);
        if (leftSide == null || rightSide == null || leftSide.side() == rightSide.side()) {
            return null;
        }

        int leftKeyOrdinal = leftSide.side() == Side.LEFT ? leftSide.ordinal() : rightSide.ordinal();
        int rightKeyOrdinal = leftSide.side() == Side.RIGHT ? leftSide.ordinal() : rightSide.ordinal();

        LogicalType leftType = leftColumns.get(leftKeyOrdinal).logicalType();
        LogicalType rightType = rightColumns.get(rightKeyOrdinal).logicalType();
        if (!leftType.equals(rightType) || !supportsHashJoinKeyType(leftType)) {
            return null;
        }
        return new HashJoinKeys(leftKeyOrdinal, rightKeyOrdinal);
    }

    private JoinFilterSplit splitJoinFilter(LogicalJoin join, BoundExpression predicate) {
        JoinFilterSplit disjunctiveSplit = splitDisjunctiveJoinFilter(join, predicate);
        if (disjunctiveSplit != null) {
            return disjunctiveSplit;
        }
        List<ColumnCatalogEntry> leftColumns = columns(join.left());
        List<ColumnCatalogEntry> rightColumns = columns(rightTable(join.right()));
        int leftColumnCount = leftColumns.size();
        int rightColumnCount = rightColumns.size();
        ArrayList<BoundExpression> leftPredicates = new ArrayList<>();
        ArrayList<BoundExpression> rightPredicates = new ArrayList<>();
        ArrayList<BoundExpression> residualPredicates = new ArrayList<>();
        ArrayList<BoundExpression> conjuncts = new ArrayList<>();
        flattenConjuncts(predicate, conjuncts);

        for (BoundExpression conjunct : conjuncts) {
            PredicateScope scope = scopeOf(conjunct, leftColumnCount, rightColumnCount);
            if (scope == PredicateScope.LEFT) {
                leftPredicates.add(rewriteForJoinSide(conjunct, leftColumnCount, JoinSide.LEFT));
            } else if (scope == PredicateScope.RIGHT) {
                rightPredicates.add(rewriteForJoinSide(conjunct, leftColumnCount, JoinSide.RIGHT));
            } else {
                residualPredicates.add(conjunct);
            }
        }

        return new JoinFilterSplit(
                combineConjuncts(leftPredicates),
                combineConjuncts(rightPredicates),
                combineConjuncts(residualPredicates)
        );
    }

    private JoinFilterSplit splitDisjunctiveJoinFilter(LogicalJoin join, BoundExpression predicate) {
        ArrayList<BoundExpression> branches = new ArrayList<>();
        flattenDisjuncts(predicate, branches);
        if (branches.size() <= 1) {
            return null;
        }

        List<ColumnCatalogEntry> leftColumns = columns(join.left());
        List<ColumnCatalogEntry> rightColumns = columns(rightTable(join.right()));
        int leftColumnCount = leftColumns.size();
        int rightColumnCount = rightColumns.size();
        ArrayList<BoundExpression> leftBranches = new ArrayList<>();
        ArrayList<BoundExpression> rightBranches = new ArrayList<>();
        boolean canPrefilterLeft = true;
        boolean canPrefilterRight = true;

        for (BoundExpression branch : branches) {
            ArrayList<BoundExpression> conjuncts = new ArrayList<>();
            flattenConjuncts(branch, conjuncts);
            ArrayList<BoundExpression> leftConjuncts = new ArrayList<>();
            ArrayList<BoundExpression> rightConjuncts = new ArrayList<>();
            for (BoundExpression conjunct : conjuncts) {
                PredicateScope scope = scopeOf(conjunct, leftColumnCount, rightColumnCount);
                if (scope == PredicateScope.LEFT) {
                    leftConjuncts.add(rewriteForJoinSide(conjunct, leftColumnCount, JoinSide.LEFT));
                } else if (scope == PredicateScope.RIGHT) {
                    rightConjuncts.add(rewriteForJoinSide(conjunct, leftColumnCount, JoinSide.RIGHT));
                }
            }
            if (leftConjuncts.isEmpty()) {
                canPrefilterLeft = false;
            } else {
                leftBranches.add(combineConjuncts(leftConjuncts));
            }
            if (rightConjuncts.isEmpty()) {
                canPrefilterRight = false;
            } else {
                rightBranches.add(combineConjuncts(rightConjuncts));
            }
        }

        BoundExpression leftPredicate = canPrefilterLeft ? combineDisjuncts(leftBranches) : null;
        BoundExpression rightPredicate = canPrefilterRight ? combineDisjuncts(rightBranches) : null;
        if (leftPredicate == null && rightPredicate == null) {
            return null;
        }
        return new JoinFilterSplit(leftPredicate, rightPredicate, predicate);
    }

    private void flattenConjuncts(BoundExpression expression, List<BoundExpression> output) {
        if (expression instanceof BoundBinaryExpression binary && binary.operator() == BinaryOperator.AND) {
            flattenConjuncts(binary.left(), output);
            flattenConjuncts(binary.right(), output);
            return;
        }
        output.add(expression);
    }

    private void flattenDisjuncts(BoundExpression expression, List<BoundExpression> output) {
        if (expression instanceof BoundBinaryExpression binary && binary.operator() == BinaryOperator.OR) {
            flattenDisjuncts(binary.left(), output);
            flattenDisjuncts(binary.right(), output);
            return;
        }
        output.add(expression);
    }

    private BoundExpression combineConjuncts(List<BoundExpression> conjuncts) {
        if (conjuncts.isEmpty()) {
            return null;
        }
        BoundExpression result = conjuncts.get(0);
        for (int index = 1; index < conjuncts.size(); index++) {
            result = new BoundBinaryExpression(result, BinaryOperator.AND, conjuncts.get(index), LogicalType.BOOLEAN);
        }
        return result;
    }

    private BoundExpression combineNullable(BoundExpression left, BoundExpression right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return new BoundBinaryExpression(left, BinaryOperator.AND, right, LogicalType.BOOLEAN);
    }

    private BoundExpression combineDisjuncts(List<BoundExpression> disjuncts) {
        if (disjuncts.isEmpty()) {
            return null;
        }
        BoundExpression result = disjuncts.get(0);
        for (int index = 1; index < disjuncts.size(); index++) {
            result = new BoundBinaryExpression(result, BinaryOperator.OR, disjuncts.get(index), LogicalType.BOOLEAN);
        }
        return result;
    }

    private PredicateScope scopeOf(BoundExpression expression, int leftColumnCount, int rightColumnCount) {
        return switch (expression) {
            case BoundAggregateExpression aggregate -> {
                PredicateScope scope = PredicateScope.NONE;
                for (BoundExpression argument : aggregate.arguments()) {
                    scope = combineScope(scope, scopeOf(argument, leftColumnCount, rightColumnCount));
                }
                yield scope;
            }
            case BoundBetweenExpression between -> combineScope(
                    combineScope(
                            scopeOf(between.input(), leftColumnCount, rightColumnCount),
                            scopeOf(between.lower(), leftColumnCount, rightColumnCount)
                    ),
                    scopeOf(between.upper(), leftColumnCount, rightColumnCount)
            );
            case BoundBinaryExpression binary -> combineScope(
                    scopeOf(binary.left(), leftColumnCount, rightColumnCount),
                    scopeOf(binary.right(), leftColumnCount, rightColumnCount)
            );
            case BoundCastExpression cast -> scopeOf(cast.child(), leftColumnCount, rightColumnCount);
            case BoundCaseExpression caseExpression -> {
                PredicateScope scope = scopeOf(caseExpression.elseExpression(), leftColumnCount, rightColumnCount);
                for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
                    scope = combineScope(scope, scopeOf(branch.condition(), leftColumnCount, rightColumnCount));
                    scope = combineScope(scope, scopeOf(branch.result(), leftColumnCount, rightColumnCount));
                }
                yield scope;
            }
            case BoundColumnRefExpression column -> columnScope(column.ordinal(), leftColumnCount, rightColumnCount);
            case BoundExistsSubqueryExpression ignored -> PredicateScope.NONE;
            case BoundFunctionExpression function -> {
                PredicateScope scope = PredicateScope.NONE;
                for (BoundExpression argument : function.arguments()) {
                    scope = combineScope(scope, scopeOf(argument, leftColumnCount, rightColumnCount));
                }
                yield scope;
            }
            case BoundInExpression in -> {
                PredicateScope scope = scopeOf(in.input(), leftColumnCount, rightColumnCount);
                for (BoundExpression candidate : in.candidates()) {
                    scope = combineScope(scope, scopeOf(candidate, leftColumnCount, rightColumnCount));
                }
                yield scope;
            }
            case BoundInSubqueryExpression in -> scopeOf(in.input(), leftColumnCount, rightColumnCount);
            case BoundLiteralExpression ignored -> PredicateScope.NONE;
            case BoundIntervalExpression ignored -> PredicateScope.NONE;
            case BoundOutputColumnExpression output -> columnScope(output.ordinal(), leftColumnCount, rightColumnCount);
            case BoundSubqueryExpression subquery -> subquery.isCorrelated() ? PredicateScope.MIXED : PredicateScope.NONE;
        };
    }

    private PredicateScope columnScope(int ordinal, int leftColumnCount, int rightColumnCount) {
        if (ordinal >= 0 && ordinal < leftColumnCount) {
            return PredicateScope.LEFT;
        }
        int rightOrdinal = ordinal - leftColumnCount;
        if (rightOrdinal >= 0 && rightOrdinal < rightColumnCount) {
            return PredicateScope.RIGHT;
        }
        return PredicateScope.MIXED;
    }

    private PredicateScope combineScope(PredicateScope left, PredicateScope right) {
        if (left == PredicateScope.NONE) {
            return right;
        }
        if (right == PredicateScope.NONE) {
            return left;
        }
        if (left == right) {
            return left;
        }
        return PredicateScope.MIXED;
    }

    private BoundExpression rewriteForJoinSide(BoundExpression expression, int leftColumnCount, JoinSide side) {
        return switch (expression) {
            case BoundAggregateExpression aggregate -> {
                ArrayList<BoundExpression> arguments = new ArrayList<>(aggregate.arguments().size());
                for (BoundExpression argument : aggregate.arguments()) {
                    arguments.add(rewriteForJoinSide(argument, leftColumnCount, side));
                }
                yield new BoundAggregateExpression(
                        aggregate.function(),
                        arguments,
                        aggregate.starArgument(),
                        aggregate.distinct()
                );
            }
            case BoundBetweenExpression between -> new BoundBetweenExpression(
                    rewriteForJoinSide(between.input(), leftColumnCount, side),
                    rewriteForJoinSide(between.lower(), leftColumnCount, side),
                    rewriteForJoinSide(between.upper(), leftColumnCount, side)
            );
            case BoundBinaryExpression binary -> new BoundBinaryExpression(
                    rewriteForJoinSide(binary.left(), leftColumnCount, side),
                    binary.operator(),
                    rewriteForJoinSide(binary.right(), leftColumnCount, side),
                    binary.logicalType()
            );
            case BoundCastExpression cast -> new BoundCastExpression(
                    rewriteForJoinSide(cast.child(), leftColumnCount, side),
                    cast.logicalType()
            );
            case BoundCaseExpression caseExpression -> {
                ArrayList<BoundCaseExpression.WhenClause> branches = new ArrayList<>(caseExpression.branches().size());
                for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
                    branches.add(new BoundCaseExpression.WhenClause(
                            rewriteForJoinSide(branch.condition(), leftColumnCount, side),
                            rewriteForJoinSide(branch.result(), leftColumnCount, side)
                    ));
                }
                yield new BoundCaseExpression(
                        branches,
                        rewriteForJoinSide(caseExpression.elseExpression(), leftColumnCount, side),
                        caseExpression.logicalType()
                );
            }
            case BoundColumnRefExpression column -> rewriteColumn(column, leftColumnCount, side);
            case BoundExistsSubqueryExpression exists -> exists;
            case BoundFunctionExpression function -> {
                ArrayList<BoundExpression> arguments = new ArrayList<>(function.arguments().size());
                for (BoundExpression argument : function.arguments()) {
                    arguments.add(rewriteForJoinSide(argument, leftColumnCount, side));
                }
                yield new BoundFunctionExpression(function.function(), arguments);
            }
            case BoundInExpression in -> {
                ArrayList<BoundExpression> candidates = new ArrayList<>(in.candidates().size());
                for (BoundExpression candidate : in.candidates()) {
                    candidates.add(rewriteForJoinSide(candidate, leftColumnCount, side));
                }
                yield new BoundInExpression(
                        rewriteForJoinSide(in.input(), leftColumnCount, side),
                        candidates,
                        in.negated()
                );
            }
            case BoundInSubqueryExpression in -> new BoundInSubqueryExpression(
                    rewriteForJoinSide(in.input(), leftColumnCount, side),
                    in.subquery(),
                    in.negated()
            );
            case BoundLiteralExpression literal -> literal;
            case BoundIntervalExpression interval -> interval;
            case BoundOutputColumnExpression output -> rewriteOutputColumn(output, leftColumnCount, side);
            case BoundSubqueryExpression subquery -> subquery;
        };
    }

    private BoundExpression rewriteColumn(BoundColumnRefExpression column, int leftColumnCount, JoinSide side) {
        int ordinal = column.ordinal();
        if (side == JoinSide.LEFT) {
            return new BoundColumnRefExpression(column.column(), ordinal);
        }
        return new BoundColumnRefExpression(column.column(), ordinal - leftColumnCount);
    }

    private BoundExpression rewriteOutputColumn(BoundOutputColumnExpression output, int leftColumnCount, JoinSide side) {
        int ordinal = output.ordinal();
        if (side == JoinSide.LEFT) {
            return new BoundOutputColumnExpression(output.name(), ordinal, output.logicalType());
        }
        return new BoundOutputColumnExpression(output.name(), ordinal - leftColumnCount, output.logicalType());
    }

    private SideOrdinal sideOrdinal(int joinedOrdinal, int leftColumnCount, int rightColumnCount) {
        if (joinedOrdinal >= 0 && joinedOrdinal < leftColumnCount) {
            return new SideOrdinal(Side.LEFT, joinedOrdinal);
        }
        int rightOrdinal = joinedOrdinal - leftColumnCount;
        if (rightOrdinal >= 0 && rightOrdinal < rightColumnCount) {
            return new SideOrdinal(Side.RIGHT, rightOrdinal);
        }
        return null;
    }

    private List<ColumnCatalogEntry> columns(BoundTableRef tableRef) {
        if (tableRef.isReplacementScan()) {
            return tableRef.replacementScan().columns();
        }
        return tableRef.table().columns();
    }

    private List<ColumnCatalogEntry> columns(LogicalOperator logical) {
        if (logical instanceof LogicalGet get) {
            return columns(get.tableRef());
        }
        if (logical instanceof LogicalFilter filter) {
            return columns(filter.child());
        }
        if (logical instanceof LogicalDependentJoin join) {
            ArrayList<ColumnCatalogEntry> result = new ArrayList<>(columns(join.child()));
            result.add(join.marker().column());
            return result;
        }
        if (logical instanceof LogicalLimit limit) {
            return columns(limit.child());
        }
        if (logical instanceof LogicalOrder order) {
            return columns(order.child());
        }
        if (logical instanceof LogicalJoin join) {
            ArrayList<ColumnCatalogEntry> result = new ArrayList<>(columns(join.left()));
            result.addAll(columns(rightTable(join.right())));
            return result;
        }
        if (logical instanceof LogicalProjection projection) {
            ArrayList<ColumnCatalogEntry> result = new ArrayList<>(projection.expressions().size());
            for (int index = 0; index < projection.expressions().size(); index++) {
                result.add(new ColumnCatalogEntry(
                        projection.names().get(index),
                        BoundExpressionTypes.logicalType(projection.expressions().get(index)),
                        index
                ));
            }
            return result;
        }
        throw new ExecutionException("Cannot derive output columns for " + logical.getClass().getSimpleName());
    }

    private List<String> names(List<ColumnCatalogEntry> columns) {
        ArrayList<String> names = new ArrayList<>(columns.size());
        for (ColumnCatalogEntry column : columns) {
            names.add(column.name());
        }
        return names;
    }

    private List<LogicalType> types(List<ColumnCatalogEntry> columns) {
        ArrayList<LogicalType> types = new ArrayList<>(columns.size());
        for (ColumnCatalogEntry column : columns) {
            types.add(column.logicalType());
        }
        return types;
    }

    private BoundTableRef rightTable(LogicalOperator logical) {
        if (logical instanceof LogicalGet get) {
            return get.tableRef();
        }
        throw new ExecutionException("Only left-deep joins with table right sides are supported");
    }

    private boolean supportsHashJoinKeyType(LogicalType logicalType) {
        return logicalType.equals(LogicalType.BOOLEAN)
                || logicalType.equals(LogicalType.INTEGER)
                || logicalType.equals(LogicalType.BIGINT)
                || logicalType.equals(LogicalType.DATE);
    }

    private enum Side {
        LEFT,
        RIGHT
    }

    private enum JoinSide {
        LEFT,
        RIGHT
    }

    private enum PredicateScope {
        NONE,
        LEFT,
        RIGHT,
        MIXED
    }

    private record SideOrdinal(Side side, int ordinal) {
    }
}
