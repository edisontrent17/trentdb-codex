package dev.trentdb.execution.physical;

import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.execution.ExecutionException;
import dev.trentdb.planner.BoundAggregateExpression;
import dev.trentdb.planner.BoundBetweenExpression;
import dev.trentdb.planner.BoundBinaryExpression;
import dev.trentdb.planner.BoundCastExpression;
import dev.trentdb.planner.BoundColumnRefExpression;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundFunctionExpression;
import dev.trentdb.planner.BoundInExpression;
import dev.trentdb.planner.BoundLiteralExpression;
import dev.trentdb.planner.BoundOutputColumnExpression;
import dev.trentdb.planner.logical.LogicalAggregate;
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
    private record JoinFilterSplit(
            BoundExpression leftPredicate,
            BoundExpression rightPredicate,
            BoundExpression residualPredicate
    ) {
    }

    private final StorageManager storageManager;

    public PhysicalPlanner(StorageManager storageManager) {
        this.storageManager = storageManager;
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
            operators.add(new PhysicalProjection(projection.expressions(), projection.names()));
            return source;
        }
        if (logical instanceof LogicalAggregate aggregate) {
            PhysicalSource source = buildPipeline(aggregate.child(), operators);
            operators.add(new PhysicalHashAggregate(aggregate.groups(), aggregate.selectList(), aggregate.selectNames()));
            return source;
        }
        if (logical instanceof LogicalFilter filter) {
            if (filter.child() instanceof LogicalJoin join) {
                JoinFilterSplit split = splitJoinFilter(join, filter.predicate());
                PhysicalSource source = buildJoinSource(
                        join,
                        split.leftPredicate(),
                        split.rightPredicate(),
                        split.residualPredicate()
                );
                if (split.residualPredicate() != null && !(source instanceof PhysicalHashJoinSource)) {
                    operators.add(new PhysicalFilter(split.residualPredicate()));
                }
                return source;
            }
            PhysicalSource source = buildPipeline(filter.child(), operators);
            operators.add(new PhysicalFilter(filter.predicate()));
            return source;
        }
        if (logical instanceof LogicalLimit limit) {
            PhysicalSource source = buildPipeline(limit.child(), operators);
            operators.add(new PhysicalLimit(limit.limit()));
            return source;
        }
        if (logical instanceof LogicalOrder order) {
            PhysicalSource source = buildPipeline(order.child(), operators);
            operators.add(new PhysicalOrder(order.orders()));
            return source;
        }
        if (logical instanceof LogicalGet get) {
            return new PhysicalTableScan(storageManager, get.tableRef());
        }
        if (logical instanceof LogicalJoin join) {
            return buildJoinSource(join, null, null, null);
        }
        throw new ExecutionException("Unsupported logical operator for physical planning: " + logical.getClass().getSimpleName());
    }

    private PhysicalSource buildJoinSource(
            LogicalJoin join,
            BoundExpression leftPredicate,
            BoundExpression rightPredicate,
            BoundExpression residualPredicate
    ) {
        HashJoinKeys hashJoinKeys = hashJoinKeys(join.left(), join.right(), join.condition());
        if (hashJoinKeys != null) {
            return new PhysicalHashJoinSource(
                    storageManager,
                    join.left(),
                    join.right(),
                    hashJoinKeys.leftKeyOrdinal(),
                    hashJoinKeys.rightKeyOrdinal(),
                    leftPredicate,
                    rightPredicate,
                    residualPredicate
            );
        }
        return new PhysicalNestedLoopJoinSource(
                storageManager,
                join.left(),
                join.right(),
                join.condition(),
                leftPredicate,
                rightPredicate
        );
    }

    private HashJoinKeys hashJoinKeys(BoundTableRef left, BoundTableRef right, BoundExpression condition) {
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
        List<ColumnCatalogEntry> rightColumns = columns(join.right());
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
        List<ColumnCatalogEntry> rightColumns = columns(join.right());
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
            case BoundColumnRefExpression column -> columnScope(column.ordinal(), leftColumnCount, rightColumnCount);
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
            case BoundLiteralExpression ignored -> PredicateScope.NONE;
            case BoundOutputColumnExpression output -> columnScope(output.ordinal(), leftColumnCount, rightColumnCount);
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
                yield new BoundAggregateExpression(aggregate.function(), arguments, aggregate.starArgument());
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
            case BoundColumnRefExpression column -> rewriteColumn(column, leftColumnCount, side);
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
            case BoundLiteralExpression literal -> literal;
            case BoundOutputColumnExpression output -> rewriteOutputColumn(output, leftColumnCount, side);
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
