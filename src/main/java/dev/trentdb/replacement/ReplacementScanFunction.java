package dev.trentdb.replacement;

import dev.trentdb.common.vector.DataChunk;

import java.util.List;

@FunctionalInterface
public interface ReplacementScanFunction {
    List<DataChunk> scan();
}
