package dev.trentdb.replacement;

import java.util.Optional;

public interface ReplacementScanProvider {
    Optional<ReplacementScan> tryReplace(String path);
}
