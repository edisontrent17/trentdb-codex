package dev.trentdb.planner;

import dev.trentdb.ast.CommonTableExpression;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.transaction.Transaction;

import java.util.List;

record BindScope(Transaction transaction, CommonTableExpressionScope commonTableExpressions) {
    BindScope(Transaction transaction) {
        this(transaction, CommonTableExpressionScope.empty());
    }

    BindScope withCommonTableExpressions(List<CommonTableExpression> expressions) {
        return new BindScope(transaction, commonTableExpressions.with(expressions));
    }

    CommonTableExpression findCommonTableExpression(QualifiedName name) {
        return commonTableExpressions.find(name);
    }

    BindScope enterCommonTableExpression(String name) {
        return new BindScope(transaction, commonTableExpressions.enter(name));
    }
}
