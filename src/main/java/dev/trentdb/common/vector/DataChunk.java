package dev.trentdb.common.vector;

import java.util.List;

public final class DataChunk {
    private final List<String> names;
    private final List<Vector> vectors;
    private final int cardinality;

    public DataChunk(List<String> names, List<Vector> vectors) {
        if (names.size() != vectors.size()) {
            throw new IllegalArgumentException("DataChunk names and vectors must have the same size");
        }
        this.names = List.copyOf(names);
        this.vectors = List.copyOf(vectors);
        this.cardinality = vectors.isEmpty() ? 0 : vectors.getFirst().size();
        for (var vector : vectors) {
            if (vector.size() != cardinality) {
                throw new IllegalArgumentException("All vectors in a DataChunk must have the same size");
            }
        }
    }

    public static DataChunk empty(List<String> names) {
        return new DataChunk(names, List.of());
    }

    public List<String> names() {
        return names;
    }

    public List<Vector> vectors() {
        return vectors;
    }

    public int cardinality() {
        return cardinality;
    }

    public Vector column(int index) {
        return vectors.get(index);
    }

    public DataChunk slice(SelectionVector selection, int selectedCount) {
        return new DataChunk(names, vectors.stream().map(vector -> vector.slice(selection, selectedCount)).toList());
    }
}
