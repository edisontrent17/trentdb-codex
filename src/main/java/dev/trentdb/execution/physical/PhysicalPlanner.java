package dev.trentdb.execution.physical;

import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.execution.ExecutionException;
import dev.trentdb.planner.BoundBinaryExpression;
import dev.trentdb.planner.BoundColumnRefExpression;
import dev.trentdb.planner.BoundExpression;
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

    private final StorageManager storageManager;

    public PhysicalPlanner(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    public Pipeline plan(LogicalOperator logical) {
        PhysicalResultCollector sink = new PhysicalResultCollector();
        if (logical instanceof LogicalExplain explain) {
            return new Pipeline(new PhysicalExplain(explain), java.util.List.of(), sink);
        }

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
            HashJoinKeys hashJoinKeys = hashJoinKeys(join.left(), join.right(), join.condition());
            if (hashJoinKeys != null) {
                return new PhysicalHashJoinSource(
                        storageManager,
                        join.left(),
                        join.right(),
                        hashJoinKeys.leftKeyOrdinal(),
                        hashJoinKeys.rightKeyOrdinal()
                );
            }
            return new PhysicalNestedLoopJoinSource(storageManager, join.left(), join.right(), join.condition());
        }
        throw new ExecutionException("Unsupported logical operator for physical planning: " + logical.getClass().getSimpleName());
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
                || logicalType.equals(LogicalType.TEXT)
                || logicalType.equals(LogicalType.DATE);
    }

    private enum Side {
        LEFT,
        RIGHT
    }

    private record SideOrdinal(Side side, int ordinal) {
    }
}
