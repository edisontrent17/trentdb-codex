package dev.trentdb.types;

import dev.trentdb.ast.TypeName;

public record LogicalType(LogicalTypeId id) {
    public static final LogicalType BOOLEAN = new LogicalType(LogicalTypeId.BOOLEAN);
    public static final LogicalType INTEGER = new LogicalType(LogicalTypeId.INTEGER);
    public static final LogicalType BIGINT = new LogicalType(LogicalTypeId.BIGINT);
    public static final LogicalType DOUBLE = new LogicalType(LogicalTypeId.DOUBLE);
    public static final LogicalType TEXT = new LogicalType(LogicalTypeId.TEXT);
    public static final LogicalType NULL = new LogicalType(LogicalTypeId.NULL);

    public static LogicalType from(TypeName typeName) {
        return switch (typeName) {
            case BOOLEAN -> BOOLEAN;
            case INT -> INTEGER;
            case BIGINT -> BIGINT;
            case DOUBLE -> DOUBLE;
            case TEXT -> TEXT;
        };
    }
}
