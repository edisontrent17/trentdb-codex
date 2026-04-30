package dev.trentdb.replacement;

import dev.trentdb.planner.BinderException;

import java.util.ArrayList;
import java.util.List;

public final class ReplacementScanRegistry {
    private final List<ReplacementScanProvider> providers = new ArrayList<>();

    public static ReplacementScanRegistry withBuiltIns() {
        var registry = new ReplacementScanRegistry();
        registry.register(new CsvReplacementScanProvider());
        return registry;
    }

    public void register(ReplacementScanProvider provider) {
        providers.add(provider);
    }

    public ReplacementScan replace(String path) {
        for (var provider : providers) {
            var replacement = provider.tryReplace(path);
            if (replacement.isPresent()) {
                return replacement.get();
            }
        }
        throw new BinderException("No replacement scan found for path: " + path);
    }
}
