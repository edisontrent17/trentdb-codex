package dev.trentdb.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Append-only catalog entry history with snapshot lookup. */
final class VersionedCatalogSet<T extends CatalogEntry> {
    private final CatalogEntryType entryType;
    private final Map<String, List<CatalogVersion<T>>> entries = new LinkedHashMap<>();

    VersionedCatalogSet(CatalogEntryType entryType) {
        this.entryType = entryType;
    }

    private VersionedCatalogSet(VersionedCatalogSet<T> source) {
        this.entryType = source.entryType;
        for (var sourceEntry : source.entries.entrySet()) {
            var versions = new ArrayList<CatalogVersion<T>>(sourceEntry.getValue().size());
            for (var version : sourceEntry.getValue()) {
                versions.add(new CatalogVersion<>(version));
            }
            entries.put(sourceEntry.getKey(), versions);
        }
    }

    VersionedCatalogSet<T> copy() {
        return new VersionedCatalogSet<>(this);
    }

    T lookupOrNull(String name, long snapshotVersion) {
        var versions = entries.get(name);
        if (versions == null) {
            return null;
        }
        for (int index = versions.size() - 1; index >= 0; index--) {
            var version = versions.get(index);
            if (version.visibleAt(snapshotVersion)) {
                return version.entry();
            }
        }
        return null;
    }

    T lookup(String name, long snapshotVersion) {
        var entry = lookupOrNull(name, snapshotVersion);
        if (entry == null) {
            throw new CatalogException(entryTypeName() + " not found: " + name);
        }
        return entry;
    }

    List<T> visibleEntries(long snapshotVersion) {
        var result = new ArrayList<T>();
        for (String name : entries.keySet()) {
            var entry = lookupOrNull(name, snapshotVersion);
            if (entry != null) result.add(entry);
        }
        return List.copyOf(result);
    }

    void installInitial(T entry) {
        entries.computeIfAbsent(entry.name(), ignored -> new ArrayList<>())
                .add(new CatalogVersion<>(entry, 0));
    }

    void apply(String name, T expected, T replacement, long commitVersion) {
        if (expected != null) {
            var versions = entries.get(name);
            if (versions == null) {
                throw new CatalogException(entryTypeName() + " changed concurrently: " + name);
            }
            var current = versions.stream()
                    .filter(version -> version.entry() == expected && version.deletedAt() == null)
                    .findFirst()
                    .orElseThrow(() -> new CatalogException(entryTypeName() + " changed concurrently: " + name));
            current.deleteAt(commitVersion);
        }
        if (replacement != null) {
            entries.computeIfAbsent(name, ignored -> new ArrayList<>())
                    .add(new CatalogVersion<>(replacement, commitVersion));
        }
    }

    private String entryTypeName() {
        return switch (entryType) {
            case SCHEMA -> "Schema";
            case TABLE -> "Table";
            case COLUMN -> "Column";
            case INDEX -> "Index";
        };
    }

    private static final class CatalogVersion<T extends CatalogEntry> {
        private final T entry;
        private final long createdAt;
        private Long deletedAt;

        private CatalogVersion(T entry, long createdAt) {
            this.entry = entry;
            this.createdAt = createdAt;
        }

        private CatalogVersion(CatalogVersion<T> source) {
            this.entry = source.entry;
            this.createdAt = source.createdAt;
            this.deletedAt = source.deletedAt;
        }

        private T entry() {
            return entry;
        }

        private Long deletedAt() {
            return deletedAt;
        }

        private boolean visibleAt(long snapshotVersion) {
            return createdAt <= snapshotVersion && (deletedAt == null || deletedAt > snapshotVersion);
        }

        private void deleteAt(long commitVersion) {
            if (deletedAt != null) {
                throw new IllegalStateException("Catalog version has already been deleted");
            }
            deletedAt = commitVersion;
        }
    }
}
