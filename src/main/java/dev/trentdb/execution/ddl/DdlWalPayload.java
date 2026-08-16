package dev.trentdb.execution.ddl;

import dev.trentdb.ast.ColumnDefinition;
import dev.trentdb.ast.CreateIndexStatement;
import dev.trentdb.ast.CreateTableStatement;
import dev.trentdb.ast.DropIndexStatement;
import dev.trentdb.ast.DropTableStatement;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Deterministic internal catalog and row WAL payloads consumed by in-memory recovery. */
public final class DdlWalPayload {
    private DdlWalPayload() { }

    /** Durable CREATE intent for storage importers that already hold validated catalog columns. */
    public static byte[] createTable(java.util.List<String> qualifiedName, java.util.List<ColumnDefinition> columns) {
        return encode((byte) 1, output -> {
            writeName(output, qualifiedName);
            output.writeInt(columns.size());
            for (ColumnDefinition column : columns) {
                writeString(output, column.name());
                writeString(output, column.type().name());
            }
        });
    }

    public static byte[] createTable(CreateTableStatement statement) {
        return createTable(statement.name().parts(), statement.columns());
    }

    public static byte[] dropTable(DropTableStatement statement) {
        return encode((byte) 2, output -> writeName(output, statement.name().parts()));
    }

    /** Operation 6: stable index identifier, target table, and ordered key directions. */
    public static byte[] createIndex(CreateIndexStatement statement) {
        return encode((byte) 6, output -> {
            writeName(output, statement.name().parts());
            writeName(output, statement.tableName().parts());
            output.writeInt(statement.keys().size());
            for (var key : statement.keys()) {
                writeString(output, key.columnName());
                output.writeByte(key.direction().ordinal());
            }
        });
    }

    /** Operation 7: stable qualified index identifier. */
    public static byte[] dropIndex(DropIndexStatement statement) {
        return encode((byte) 7, output -> writeName(output, statement.name().parts()));
    }

    /** Deterministic typed INSERT intent with a stable logical row ID. */
    /** Deterministic DELETE intent carrying materialized stable row IDs; replay never evaluates SQL predicates. */
    public static byte[] delete(dev.trentdb.planner.BoundDeleteStatement statement, java.util.List<Long> rowIds) {
        return encode((byte) 4, output -> { writeName(output, java.util.List.of(statement.table().schema().name(), statement.table().name())); output.writeInt(rowIds.size()); long previous = 0; for (long rowId : rowIds) { if (rowId <= previous) throw new IllegalArgumentException("Delete row IDs must be sorted"); previous = rowId; output.writeLong(rowId); } });
    }


    /** Deterministic UPDATE intent: stable row IDs plus typed full-row replacement values. */
    public static byte[] update(dev.trentdb.planner.BoundUpdateStatement statement, java.util.List<dev.trentdb.storage.InMemoryTableStorage.RowReplacement> replacements) {
        return encode((byte) 5, output -> { writeName(output, java.util.List.of(statement.table().schema().name(), statement.table().name())); output.writeInt(replacements.size()); long previous = 0; for (var replacement : replacements) { if (replacement.rowId() <= previous) throw new IllegalArgumentException("Update row IDs must be sorted"); previous = replacement.rowId(); if (replacement.values().size() != statement.table().columns().size()) throw new IllegalArgumentException("UPDATE replacement column count mismatch"); output.writeLong(replacement.rowId()); output.writeInt(replacement.values().size()); for (int ordinal = 0; ordinal < replacement.values().size(); ordinal++) { var value = replacement.values().get(ordinal); var type = statement.table().columns().get(ordinal).logicalType(); output.writeInt(type.id().ordinal()); output.writeBoolean(value == null); if (value != null) writeString(output, value.toString()); } } });
    }


    public static byte[] insert(dev.trentdb.planner.BoundInsertStatement statement, long rowId) {
        return encode((byte) 3, output -> {
            writeString(output, statement.table().schema().name());
            writeString(output, statement.table().name());
            if (rowId <= 0) throw new IllegalArgumentException("Row ID must be positive"); output.writeLong(rowId);
            output.writeInt(statement.targetOrdinals().size());
            for (int index = 0; index < statement.targetOrdinals().size(); index++) {
                int ordinal = statement.targetOrdinals().get(index);
                var literal = statement.values().get(index);
                output.writeInt(ordinal);
                output.writeInt(statement.table().columns().get(ordinal).logicalType().id().ordinal());
                output.writeInt(literal.logicalType().id().ordinal());
                output.writeBoolean(literal.value() == null);
                if (literal.value() != null) writeString(output, literal.value().toString());
            }
        });
    }

    /** Durable full-row INSERT intent for the bounded V2 primitive snapshot importer. */
    public static byte[] primitiveInsert(String schema, String table, long rowId,
                                         java.util.List<dev.trentdb.catalog.ColumnCatalogEntry> columns,
                                         java.util.List<Object> values) {
        if (columns.size() != values.size()) throw new IllegalArgumentException("INSERT column/value count mismatch");
        return encode((byte) 3, output -> {
            writeString(output, schema);
            writeString(output, table);
            if (rowId <= 0) throw new IllegalArgumentException("Row ID must be positive");
            output.writeLong(rowId);
            output.writeInt(columns.size());
            for (int ordinal = 0; ordinal < columns.size(); ordinal++) {
                var type = columns.get(ordinal).logicalType().id();
                Object value = values.get(ordinal);
                output.writeInt(ordinal);
                output.writeInt(type.ordinal());
                output.writeInt((value == null ? dev.trentdb.types.LogicalTypeId.NULL : type).ordinal());
                output.writeBoolean(value == null);
                if (value != null) writeString(output, primitiveText(type, value));
            }
        });
    }

    private static String primitiveText(dev.trentdb.types.LogicalTypeId type, Object value) {
        return switch (type) {
        case BOOLEAN -> value instanceof Boolean bool ? Boolean.toString(bool) : invalidPrimitive(type, value);
        case INTEGER, BIGINT -> value instanceof Number number ? Long.toString(number.longValue()) : invalidPrimitive(type, value);
        default -> throw new IllegalArgumentException("Unsupported primitive WAL type: " + type);
        };
    }

    private static String invalidPrimitive(dev.trentdb.types.LogicalTypeId type, Object value) {
        throw new IllegalArgumentException("Invalid primitive WAL value for " + type + ": " + value.getClass().getSimpleName());
    }


    /** Compatibility helper for single-row handcrafted WAL tests. */
    /** Compatibility helper for handcrafted one-row DELETE WAL tests. */
    public static byte[] delete(dev.trentdb.planner.BoundDeleteStatement statement) { return delete(statement, java.util.List.of(1L)); }


    public static byte[] insert(dev.trentdb.planner.BoundInsertStatement statement) { return insert(statement, 1); }


    private static byte[] encode(byte operation, Writer writer) {
        try {
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            output.writeByte(operation);
            writer.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode DDL WAL payload", exception);
        }
    }

    private static void writeName(DataOutputStream output, java.util.List<String> parts) throws IOException {
        output.writeInt(parts.size());
        for (String part : parts) writeString(output, part);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    @FunctionalInterface private interface Writer { void write(DataOutputStream output) throws IOException; }
}
