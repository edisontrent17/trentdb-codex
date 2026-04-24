package dev.trentdb.ast;

import java.util.List;

public record FromItem(TableReference base, List<JoinClause> joins) {
}
