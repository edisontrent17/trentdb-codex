package dev.trentdb.planner;

import dev.trentdb.ast.ColumnReferenceExpression;
import dev.trentdb.ast.Expression;

import java.util.List;

final class GroupByAliasResolver {
    private final List<HavingAliasResolver.SelectAlias> aliases;

    GroupByAliasResolver(List<HavingAliasResolver.SelectAlias> aliases) {
        this.aliases = List.copyOf(aliases);
    }

    Expression resolve(Expression expression) {
        if (!(expression instanceof ColumnReferenceExpression column) || column.name().parts().size() != 1) {
            return expression;
        }
        String aliasName = column.name().last();
        Expression match = null;
        for (HavingAliasResolver.SelectAlias alias : aliases) {
            if (alias.name().equals(aliasName)) {
                if (match != null) {
                    throw new BinderException("GROUP BY reference is ambiguous: " + aliasName);
                }
                match = alias.expression();
            }
        }
        return match == null ? expression : match;
    }
}
