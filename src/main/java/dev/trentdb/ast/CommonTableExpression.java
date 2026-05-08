package dev.trentdb.ast;

import java.util.List;

public record CommonTableExpression(String name, List<String> columnAliases, SelectStatement select) {
    public CommonTableExpression {
        columnAliases = List.copyOf(columnAliases);
    }
}
