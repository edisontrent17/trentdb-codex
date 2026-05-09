package dev.trentdb.optimizer;

import dev.trentdb.planner.logical.LogicalOperator;

public final class Optimizer {
    private final boolean collectMetrics;
    private Metrics metrics = Metrics.empty();

    public Optimizer() {
        this(false);
    }

    public Optimizer(boolean collectMetrics) {
        this.collectMetrics = collectMetrics;
    }

    public LogicalOperator optimize(LogicalOperator plan) {
        if (!collectMetrics) {
            metrics = Metrics.empty();
            return plan;
        }
        BoundExpressionRewriter expressionRewriter = new BoundExpressionRewriter();
        LogicalOperatorRewriter logicalRewriter = new LogicalOperatorRewriter(expressionRewriter);
        LogicalOperator rewritten = logicalRewriter.rewrite(plan);
        metrics = Metrics.from(logicalRewriter, expressionRewriter);
        return rewritten;
    }

    public Metrics metrics() {
        return metrics;
    }

    public record Metrics(
            int logicalOperatorsVisited,
            int logicalOperatorsRebuilt,
            int boundExpressionsVisited,
            int boundExpressionsRebuilt,
            int boundSelectsVisited,
            int boundSelectsRebuilt,
            int boundFromSourcesVisited,
            int boundFromSourcesRebuilt,
            int expressionListsRebuilt,
            int orderListsRebuilt
    ) {
        static Metrics empty() {
            return new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        static Metrics from(
                LogicalOperatorRewriter logicalRewriter,
                BoundExpressionRewriter expressionRewriter
        ) {
            return new Metrics(
                    logicalRewriter.operatorsVisited(),
                    logicalRewriter.operatorsRebuilt(),
                    expressionRewriter.expressionsVisited(),
                    expressionRewriter.expressionsRebuilt(),
                    expressionRewriter.selectStatementsVisited(),
                    expressionRewriter.selectStatementsRebuilt(),
                    expressionRewriter.fromSourcesVisited(),
                    expressionRewriter.fromSourcesRebuilt(),
                    logicalRewriter.expressionListsRebuilt() + expressionRewriter.expressionListsRebuilt(),
                    expressionRewriter.orderListsRebuilt()
            );
        }

        public String profileDetails() {
            return "logicalVisited=" + logicalOperatorsVisited
                    + " logicalRebuilt=" + logicalOperatorsRebuilt
                    + " expressionsVisited=" + boundExpressionsVisited
                    + " expressionsRebuilt=" + boundExpressionsRebuilt
                    + " selectsVisited=" + boundSelectsVisited
                    + " selectsRebuilt=" + boundSelectsRebuilt
                    + " fromVisited=" + boundFromSourcesVisited
                    + " fromRebuilt=" + boundFromSourcesRebuilt
                    + " expressionListsRebuilt=" + expressionListsRebuilt
                    + " orderListsRebuilt=" + orderListsRebuilt;
        }
    }
}
