package dev.trentdb.optimizer;

import dev.trentdb.planner.BoundAggregateExpression;
import dev.trentdb.planner.BoundBetweenExpression;
import dev.trentdb.planner.BoundBinaryExpression;
import dev.trentdb.planner.BoundCaseExpression;
import dev.trentdb.planner.BoundCastExpression;
import dev.trentdb.planner.BoundColumnRefExpression;
import dev.trentdb.planner.BoundExistsSubqueryExpression;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundFrom;
import dev.trentdb.planner.BoundFunctionExpression;
import dev.trentdb.planner.BoundInExpression;
import dev.trentdb.planner.BoundInSubqueryExpression;
import dev.trentdb.planner.BoundIntervalExpression;
import dev.trentdb.planner.BoundJoinRef;
import dev.trentdb.planner.BoundLiteralExpression;
import dev.trentdb.planner.BoundOrderByItem;
import dev.trentdb.planner.BoundOutputColumnExpression;
import dev.trentdb.planner.BoundSelectStatement;
import dev.trentdb.planner.BoundSubqueryRef;
import dev.trentdb.planner.BoundSubqueryExpression;
import dev.trentdb.planner.BoundTableRef;

import java.util.ArrayList;
import java.util.List;

class BoundExpressionRewriter {
    BoundExpression rewrite(BoundExpression expression) {
        if (expression == null) {
            throw new IllegalArgumentException("Expression must not be null");
        }
        return switch (expression) {
            case BoundAggregateExpression aggregate -> visitAggregate(aggregate);
            case BoundBetweenExpression between -> visitBetween(between);
            case BoundBinaryExpression binary -> visitBinary(binary);
            case BoundCaseExpression caseExpression -> visitCase(caseExpression);
            case BoundCastExpression cast -> visitCast(cast);
            case BoundColumnRefExpression column -> visitColumnRef(column);
            case BoundExistsSubqueryExpression exists -> visitExistsSubquery(exists);
            case BoundFunctionExpression function -> visitFunction(function);
            case BoundInExpression in -> visitIn(in);
            case BoundInSubqueryExpression in -> visitInSubquery(in);
            case BoundIntervalExpression interval -> visitInterval(interval);
            case BoundLiteralExpression literal -> visitLiteral(literal);
            case BoundOutputColumnExpression output -> visitOutputColumn(output);
            case BoundSubqueryExpression subquery -> visitScalarSubquery(subquery);
        };
    }

    BoundSelectStatement rewriteSelect(BoundSelectStatement statement) {
        BoundFrom from = rewriteFrom(statement.from());
        ExpressionList selectList = rewriteList(statement.selectList());
        ExpressionList groupBy = rewriteList(statement.groupBy());
        BoundExpression where = rewriteOptional(statement.where());
        BoundExpression having = rewriteOptional(statement.having());
        OrderList orderBy = rewriteOrderBy(statement.orderBy());
        // Child rewriters return the original object when a subtree is unchanged, so reference equality is intentional.
        if (from == statement.from() && !selectList.changed() && !groupBy.changed() && where == statement.where()
                && having == statement.having() && !orderBy.changed()) {
            return statement;
        }
        return new BoundSelectStatement(
                from,
                selectList.expressions(),
                statement.selectNames(),
                where,
                groupBy.expressions(),
                having,
                orderBy.items(),
                statement.limit()
        );
    }

    List<BoundOrderByItem> rewriteOrderByItems(List<BoundOrderByItem> items) {
        return rewriteOrderBy(items).items();
    }

    protected BoundFrom rewriteFrom(BoundFrom from) {
        if (from instanceof BoundJoinRef join) {
            BoundFrom left = rewriteFrom(join.left());
            BoundExpression condition = rewrite(join.condition());
            if (left == join.left() && condition == join.condition()) {
                return join;
            }
            return new BoundJoinRef(left, join.right(), condition, join.type());
        }
        if (from instanceof BoundSubqueryRef subquery) {
            BoundSelectStatement statement = rewriteSelect(subquery.subquery());
            if (statement == subquery.subquery()) {
                return subquery;
            }
            return new BoundSubqueryRef(statement, subquery.relationName(), subquery.columns());
        }
        if (from instanceof BoundTableRef) {
            return from;
        }
        throw new IllegalArgumentException("Unsupported bound FROM source: " + from.getClass().getSimpleName());
    }

    protected BoundExpression visitAggregate(BoundAggregateExpression aggregate) {
        ExpressionList arguments = rewriteList(aggregate.arguments());
        if (!arguments.changed()) {
            return aggregate;
        }
        return new BoundAggregateExpression(
                aggregate.function(),
                arguments.expressions(),
                aggregate.starArgument(),
                aggregate.distinct()
        );
    }

    protected BoundExpression visitBetween(BoundBetweenExpression between) {
        BoundExpression input = rewrite(between.input());
        BoundExpression lower = rewrite(between.lower());
        BoundExpression upper = rewrite(between.upper());
        if (input == between.input() && lower == between.lower() && upper == between.upper()) {
            return between;
        }
        return new BoundBetweenExpression(input, lower, upper);
    }

    protected BoundExpression visitBinary(BoundBinaryExpression binary) {
        BoundExpression left = rewrite(binary.left());
        BoundExpression right = rewrite(binary.right());
        if (left == binary.left() && right == binary.right()) {
            return binary;
        }
        return new BoundBinaryExpression(left, binary.operator(), right, binary.logicalType());
    }

    protected BoundExpression visitCase(BoundCaseExpression caseExpression) {
        ArrayList<BoundCaseExpression.WhenClause> branches = new ArrayList<>(caseExpression.branches().size());
        boolean changed = false;
        for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
            BoundExpression condition = rewrite(branch.condition());
            BoundExpression result = rewrite(branch.result());
            if (condition != branch.condition() || result != branch.result()) {
                changed = true;
            }
            branches.add(new BoundCaseExpression.WhenClause(condition, result));
        }
        BoundExpression elseExpression = rewrite(caseExpression.elseExpression());
        if (!changed && elseExpression == caseExpression.elseExpression()) {
            return caseExpression;
        }
        return new BoundCaseExpression(branches, elseExpression, caseExpression.logicalType());
    }

    protected BoundExpression visitCast(BoundCastExpression cast) {
        BoundExpression child = rewrite(cast.child());
        return child == cast.child() ? cast : new BoundCastExpression(child, cast.logicalType());
    }

    protected BoundExpression visitColumnRef(BoundColumnRefExpression column) {
        return column;
    }

    protected BoundExpression visitExistsSubquery(BoundExistsSubqueryExpression exists) {
        BoundSelectStatement subquery = rewriteSelect(exists.subquery());
        if (subquery == exists.subquery()) {
            return exists;
        }
        return new BoundExistsSubqueryExpression(subquery, exists.localColumnCount(), exists.correlatedColumns());
    }

    protected BoundExpression visitFunction(BoundFunctionExpression function) {
        ExpressionList arguments = rewriteList(function.arguments());
        return arguments.changed() ? new BoundFunctionExpression(function.function(), arguments.expressions()) : function;
    }

    protected BoundExpression visitIn(BoundInExpression in) {
        BoundExpression input = rewrite(in.input());
        ExpressionList candidates = rewriteList(in.candidates());
        if (input == in.input() && !candidates.changed()) {
            return in;
        }
        return new BoundInExpression(input, candidates.expressions(), in.negated());
    }

    protected BoundExpression visitInSubquery(BoundInSubqueryExpression in) {
        BoundExpression input = rewrite(in.input());
        BoundSelectStatement subquery = rewriteSelect(in.subquery());
        if (input == in.input() && subquery == in.subquery()) {
            return in;
        }
        return new BoundInSubqueryExpression(input, subquery, in.negated());
    }

    protected BoundExpression visitInterval(BoundIntervalExpression interval) {
        return interval;
    }

    protected BoundExpression visitLiteral(BoundLiteralExpression literal) {
        return literal;
    }

    protected BoundExpression visitOutputColumn(BoundOutputColumnExpression output) {
        return output;
    }

    protected BoundExpression visitScalarSubquery(BoundSubqueryExpression subquery) {
        BoundSelectStatement statement = rewriteSelect(subquery.subquery());
        if (statement == subquery.subquery()) {
            return subquery;
        }
        return new BoundSubqueryExpression(
                statement,
                subquery.logicalType(),
                subquery.localColumnCount(),
                subquery.correlatedColumns()
        );
    }

    private ExpressionList rewriteList(List<BoundExpression> expressions) {
        ArrayList<BoundExpression> rewritten = new ArrayList<>(expressions.size());
        boolean changed = false;
        for (BoundExpression expression : expressions) {
            BoundExpression rewrittenExpression = rewrite(expression);
            if (rewrittenExpression != expression) {
                changed = true;
            }
            rewritten.add(rewrittenExpression);
        }
        return changed ? new ExpressionList(rewritten, true) : new ExpressionList(expressions, false);
    }

    private OrderList rewriteOrderBy(List<BoundOrderByItem> items) {
        ArrayList<BoundOrderByItem> rewritten = new ArrayList<>(items.size());
        boolean changed = false;
        for (BoundOrderByItem item : items) {
            BoundExpression expression = rewrite(item.expression());
            if (expression == item.expression()) {
                rewritten.add(item);
            } else {
                changed = true;
                rewritten.add(new BoundOrderByItem(expression, item.direction()));
            }
        }
        return changed ? new OrderList(rewritten, true) : new OrderList(items, false);
    }

    private BoundExpression rewriteOptional(BoundExpression expression) {
        return expression == null ? null : rewrite(expression);
    }

    private record ExpressionList(List<BoundExpression> expressions, boolean changed) {
    }

    private record OrderList(List<BoundOrderByItem> items, boolean changed) {
    }
}
