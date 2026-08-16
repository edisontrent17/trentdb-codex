package dev.trentdb.storage;
import java.util.List;import java.util.OptionalLong;
public record DuckDbTableStatistics(List<Primitive> columns){public DuckDbTableStatistics{columns=List.copyOf(columns);} public record Primitive(boolean hasNull,boolean hasNoNull,long distinctCount,Kind kind,OptionalLong min,OptionalLong max){} public enum Kind{BOOLEAN,INTEGER,BIGINT}}
