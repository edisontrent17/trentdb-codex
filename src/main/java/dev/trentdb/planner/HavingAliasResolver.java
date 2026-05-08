package dev.trentdb.planner;

import dev.trentdb.ast.BetweenExpression;
import dev.trentdb.ast.BinaryExpression;
import dev.trentdb.ast.CaseExpression;
import dev.trentdb.ast.CastExpression;
import dev.trentdb.ast.ColumnReferenceExpression;
import dev.trentdb.ast.Expression;
import dev.trentdb.ast.FunctionCallExpression;
import dev.trentdb.ast.InExpression;
import dev.trentdb.ast.InSubqueryExpression;
import dev.trentdb.ast.IntervalLiteralExpression;
import dev.trentdb.ast.LiteralExpression;
import dev.trentdb.ast.NullCheckExpression;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.ast.StarExpression;
import dev.trentdb.ast.SubqueryExpression;
import dev.trentdb.ast.UnaryExpression;

import java.util.ArrayList;
import java.util.List;

final class HavingAliasResolver {
    record SelectAlias(String name, Expression expression) {
    }

    private final List<SelectAlias> aliases;
    private final String clauseName;

    HavingAliasResolver(List<SelectAlias> aliases) {
        this(aliases, "HAVING");
    }

    HavingAliasResolver(List<SelectAlias> aliases, String clauseName) {
        this.aliases = List.copyOf(aliases);
        this.clauseName = clauseName;
    }

    Expression resolve(Expression expression) {
        return switch (expression) {
            case BetweenExpression between -> new BetweenExpression(
                    resolve(between.input()),
                    resolve(between.lower()),
                    resolve(between.upper())
            );
            case BinaryExpression binary -> new BinaryExpression(
                    resolve(binary.left()),
                    binary.operator(),
                    resolve(binary.right())
            );
            case CaseExpression caseExpression -> resolveCaseExpression(caseExpression);
            case CastExpression cast -> new CastExpression(resolve(cast.child()), cast.targetType());
            case ColumnReferenceExpression column -> resolveColumn(column);
            case FunctionCallExpression function -> resolveFunction(function);
            case InExpression in -> resolveInExpression(in);
            case InSubqueryExpression in -> new InSubqueryExpression(resolve(in.input()), in.subquery(), in.negated());
            case NullCheckExpression nullCheck -> new NullCheckExpression(resolve(nullCheck.expression()), nullCheck.negated());
            case UnaryExpression unary -> new UnaryExpression(unary.operator(), resolve(unary.expression()));
            case IntervalLiteralExpression interval -> interval;
            case LiteralExpression literal -> literal;
            case StarExpression star -> star;
            case SubqueryExpression subquery -> subquery;
        };
    }

    private CaseExpression resolveCaseExpression(CaseExpression caseExpression) {
        ArrayList<CaseExpression.WhenClause> branches = new ArrayList<>(caseExpression.branches().size());
        for (CaseExpression.WhenClause branch : caseExpression.branches()) {
            branches.add(new CaseExpression.WhenClause(
                    resolve(branch.condition()),
                    resolve(branch.result())
            ));
        }
        Expression elseExpression = caseExpression.elseExpression() == null
                ? null
                : resolve(caseExpression.elseExpression());
        return new CaseExpression(branches, elseExpression);
    }

    private FunctionCallExpression resolveFunction(FunctionCallExpression function) {
        ArrayList<Expression> arguments = new ArrayList<>(function.arguments().size());
        for (Expression argument : function.arguments()) {
            arguments.add(resolve(argument));
        }
        return new FunctionCallExpression(function.name(), arguments, function.starArgument(), function.distinct());
    }

    private InExpression resolveInExpression(InExpression in) {
        ArrayList<Expression> candidates = new ArrayList<>(in.candidates().size());
        for (Expression candidate : in.candidates()) {
            candidates.add(resolve(candidate));
        }
        return new InExpression(resolve(in.input()), candidates, in.negated());
    }

    private Expression resolveColumn(ColumnReferenceExpression column) {
        QualifiedName name = column.name();
        if (name.parts().size() != 1) {
            return column;
        }
        String aliasName = name.last();
        Expression match = null;
        for (SelectAlias alias : aliases) {
            if (alias.name().equals(aliasName)) {
                if (match != null) {
                    throw new BinderException(clauseName + " reference is ambiguous: " + aliasName);
                }
                match = alias.expression();
            }
        }
        if (match == null) {
            return column;
        }
        return match;
    }
}
