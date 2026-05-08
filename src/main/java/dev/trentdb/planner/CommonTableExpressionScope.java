package dev.trentdb.planner;

import dev.trentdb.ast.CommonTableExpression;
import dev.trentdb.ast.QualifiedName;

import java.util.ArrayList;
import java.util.List;

final class CommonTableExpressionScope {
    private final List<CommonTableExpression> expressions;
    private final List<String> activeNames;

    private CommonTableExpressionScope(List<CommonTableExpression> expressions, List<String> activeNames) {
        this.expressions = List.copyOf(expressions);
        this.activeNames = List.copyOf(activeNames);
    }

    static CommonTableExpressionScope empty() {
        return new CommonTableExpressionScope(List.of(), List.of());
    }

    CommonTableExpressionScope with(List<CommonTableExpression> newExpressions) {
        if (newExpressions.isEmpty()) {
            return this;
        }
        ArrayList<String> names = new ArrayList<>(newExpressions.size());
        for (CommonTableExpression expression : newExpressions) {
            if (names.contains(expression.name())) {
                throw new BinderException("Duplicate common table expression: " + expression.name());
            }
            names.add(expression.name());
        }
        ArrayList<CommonTableExpression> merged = new ArrayList<>(expressions);
        merged.addAll(newExpressions);
        return new CommonTableExpressionScope(merged, activeNames);
    }

    CommonTableExpression find(QualifiedName name) {
        if (name == null || name.parts().size() != 1) {
            return null;
        }
        String expressionName = name.last();
        for (int index = expressions.size() - 1; index >= 0; index--) {
            CommonTableExpression expression = expressions.get(index);
            if (expression.name().equals(expressionName)) {
                return expression;
            }
        }
        return null;
    }

    CommonTableExpressionScope enter(String name) {
        if (activeNames.contains(name)) {
            throw new BinderException("Recursive common table expressions are not supported: " + name);
        }
        ArrayList<String> active = new ArrayList<>(activeNames);
        active.add(name);
        return new CommonTableExpressionScope(expressions, active);
    }
}
