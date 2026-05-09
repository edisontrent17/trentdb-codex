package dev.trentdb.planner;

import dev.trentdb.ast.QualifiedName;
import dev.trentdb.catalog.CatalogException;

final class ColumnBinder {
    private ColumnBinder() {
    }

    static BoundColumnRefExpression bind(BindingContext context, QualifiedName name) {
        if (name.parts().size() == 1) {
            BoundColumnBinding binding = bindUnqualified(context, name.last());
            return new BoundColumnRefExpression(binding.column(), binding.ordinal());
        }
        if (name.parts().size() == 2) {
            String relationName = name.parts().getFirst();
            String columnName = name.parts().get(1);
            for (BoundColumnBinding binding : context.columns()) {
                if (binding.relationName().equals(relationName) && binding.column().name().equals(columnName)) {
                    return new BoundColumnRefExpression(binding.column(), binding.ordinal());
                }
            }
            throw new CatalogException("Column not found: " + String.join(".", name.parts()));
        }
        throw new BinderException("Unsupported qualified column reference: " + String.join(".", name.parts()));
    }

    private static BoundColumnBinding bindUnqualified(BindingContext context, String columnName) {
        BoundColumnBinding local = findMatch(context, columnName, 0, context.starColumnCount());
        if (local != null) {
            return local;
        }
        BoundColumnBinding outer = findMatch(context, columnName, context.starColumnCount(), context.columns().size());
        if (outer != null) {
            return outer;
        }
        throw new CatalogException("Column not found: " + columnName);
    }

    private static BoundColumnBinding findMatch(
            BindingContext context,
            String columnName,
            int fromIndex,
            int toIndex
    ) {
        BoundColumnBinding match = null;
        for (int index = fromIndex; index < toIndex; index++) {
            BoundColumnBinding binding = context.columns().get(index);
            if (binding.column().name().equals(columnName)) {
                if (match != null) {
                    throw new BinderException("Column reference is ambiguous: " + columnName);
                }
                match = binding;
            }
        }
        return match;
    }
}
