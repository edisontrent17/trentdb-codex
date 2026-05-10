package dev.trentdb.tpch;

import dev.trentdb.execution.ExecutionProfiler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TpchBenchmarkRunner {
    private static final Pattern QUERY_PATTERN = Pattern.compile("Q(\\d+)");
    private static final Pattern DEPTH_PATTERN = Pattern.compile("\\bdepth=(\\d+)\\b");

    private TpchBenchmarkRunner() {
    }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        List<BenchmarkQuery> queries = queries(options);
        printHeader();
        for (BenchmarkQuery query : queries) {
            for (int index = 0; index < options.warmupRuns(); index++) {
                run(query);
            }
            for (int index = 1; index <= options.measuredRuns(); index++) {
                BenchmarkResult result = run(query);
                printResult(query.name(), index, result);
            }
        }
    }

    static List<BenchmarkQuery> queries(Options options) {
        ArrayList<BenchmarkQuery> queries = new ArrayList<>();
        for (Method method : TpchCompatibilityTest.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Test.class)) {
                continue;
            }
            String name = queryName(method);
            if (!options.includes(name)) {
                continue;
            }
            method.setAccessible(true);
            queries.add(new BenchmarkQuery(name, method));
        }
        queries.sort(Comparator.comparingInt(BenchmarkQuery::number));
        return List.copyOf(queries);
    }

    private static String queryName(Method method) {
        Matcher matcher = QUERY_PATTERN.matcher(method.getName());
        if (!matcher.find()) {
            throw new IllegalStateException("TPC-H test method does not include a query number: " + method.getName());
        }
        int number = Integer.parseInt(matcher.group(1));
        return String.format(Locale.ROOT, "Q%02d", number);
    }

    private static BenchmarkResult run(BenchmarkQuery query) {
        long started = System.nanoTime();
        try {
            ExecutionProfiler.ProfileResult<Void> profile = ExecutionProfiler.capture(() -> {
                invoke(query.method());
                return null;
            });
            long elapsedNanos = System.nanoTime() - started;
            return BenchmarkResult.success(elapsedNanos, profile.events());
        } catch (RuntimeException exception) {
            long elapsedNanos = System.nanoTime() - started;
            return BenchmarkResult.failure(elapsedNanos, exception);
        }
    }

    private static void invoke(Method method) {
        try {
            method.invoke(new TpchCompatibilityTest());
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not invoke benchmark method: " + method.getName(), exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Benchmark method failed: " + method.getName(), cause);
        }
    }

    private static void printHeader() {
        System.out.println(String.join(",",
                "query",
                "iteration",
                "status",
                "total_ms",
                "csv_read_ms",
                "csv_schema_ms",
                "csv_materialize_ms",
                "csv_cache_hits",
                "csv_cache_misses",
                "optimizer_ms",
                "physical_plan_ms",
                "pipeline_ms",
                "result_ms",
                "query_executor_ms",
                "error"
        ));
    }

    private static void printResult(String query, int iteration, BenchmarkResult result) {
        ProfileSummary summary = ProfileSummary.of(result.events());
        System.out.printf(Locale.ROOT,
                "%s,%d,%s,%.3f,%.3f,%.3f,%.3f,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%s%n",
                query,
                iteration,
                result.status(),
                result.elapsedMillis(),
                summary.csvReadMillis(),
                summary.csvSchemaMillis(),
                summary.csvMaterializeMillis(),
                summary.csvCacheHits(),
                summary.csvCacheMisses(),
                summary.optimizerMillis(),
                summary.physicalPlanMillis(),
                summary.pipelineMillis(),
                summary.resultMillis(),
                summary.queryExecutorMillis(),
                csv(result.errorMessage())
        );
    }

    private static String csv(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    public record Options(int warmupRuns, int measuredRuns, Set<String> queryNames) {
        static Options parse(String[] args) {
            int warmupRuns = 0;
            int measuredRuns = 1;
            HashSet<String> queryNames = new HashSet<>();
            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                if (arg.equals("--warmup")) {
                    warmupRuns = Integer.parseInt(requiredValue(args, ++index, arg));
                    continue;
                }
                if (arg.equals("--iterations")) {
                    measuredRuns = Integer.parseInt(requiredValue(args, ++index, arg));
                    continue;
                }
                if (arg.equals("--query")) {
                    addQueries(queryNames, requiredValue(args, ++index, arg));
                    continue;
                }
                if (arg.equals("--help")) {
                    printUsageAndExit();
                }
                throw new IllegalArgumentException("Unknown option: " + arg);
            }
            if (warmupRuns < 0 || measuredRuns <= 0) {
                throw new IllegalArgumentException("--warmup must be >= 0 and --iterations must be > 0");
            }
            return new Options(warmupRuns, measuredRuns, queryNames);
        }

        boolean includes(String queryName) {
            return queryNames.isEmpty() || queryNames.contains(queryName);
        }

        private static void addQueries(Set<String> queryNames, String value) {
            for (String part : value.split(",")) {
                String normalized = normalizeQueryName(part.trim());
                if (!normalized.isBlank()) {
                    queryNames.add(normalized);
                }
            }
        }

        private static String normalizeQueryName(String value) {
            if (value.isBlank()) {
                return "";
            }
            String upper = value.toUpperCase(Locale.ROOT);
            if (upper.startsWith("Q")) {
                return String.format(Locale.ROOT, "Q%02d", Integer.parseInt(upper.substring(1)));
            }
            return String.format(Locale.ROOT, "Q%02d", Integer.parseInt(upper));
        }

        private static String requiredValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static void printUsageAndExit() {
            System.out.println("Usage: TpchBenchmarkRunner [--warmup N] [--iterations N] [--query Q01,Q06]");
            System.exit(0);
        }
    }

    record BenchmarkQuery(String name, Method method) {
        int number() {
            return Integer.parseInt(name.substring(1));
        }
    }

    record BenchmarkResult(
            boolean success,
            long elapsedNanos,
            List<ExecutionProfiler.ProfileEvent> events,
            String errorMessage
    ) {
        BenchmarkResult {
            events = List.copyOf(events);
        }

        static BenchmarkResult success(long elapsedNanos, List<ExecutionProfiler.ProfileEvent> events) {
            return new BenchmarkResult(true, elapsedNanos, events, null);
        }

        static BenchmarkResult failure(long elapsedNanos, RuntimeException exception) {
            return new BenchmarkResult(false, elapsedNanos, List.of(), exception.getMessage());
        }

        String status() {
            return success ? "ok" : "error";
        }

        double elapsedMillis() {
            return elapsedNanos / 1_000_000.0d;
        }
    }

    record ProfileSummary(
            double csvReadMillis,
            double csvSchemaMillis,
            double csvMaterializeMillis,
            int csvCacheHits,
            int csvCacheMisses,
            double optimizerMillis,
            double physicalPlanMillis,
            double pipelineMillis,
            double resultMillis,
            double queryExecutorMillis
    ) {
        static ProfileSummary of(List<ExecutionProfiler.ProfileEvent> events) {
            double csvReadMillis = 0.0d;
            double csvSchemaMillis = 0.0d;
            double csvMaterializeMillis = 0.0d;
            int csvCacheHits = 0;
            int csvCacheMisses = 0;
            double optimizerMillis = 0.0d;
            double physicalPlanMillis = 0.0d;
            double pipelineMillis = 0.0d;
            double resultMillis = 0.0d;
            double queryExecutorMillis = 0.0d;
            for (ExecutionProfiler.ProfileEvent event : events) {
                if (event.component().equals("CsvReplacementScanProvider")) {
                    if (event.event().equals("read_lines")) {
                        csvReadMillis += event.elapsedMillis();
                    } else if (event.event().equals("schema")) {
                        csvSchemaMillis += event.elapsedMillis();
                    } else if (event.event().equals("materialize_chunks")) {
                        csvMaterializeMillis += event.elapsedMillis();
                    } else if (event.event().equals("cache_hit")) {
                        csvCacheHits++;
                    } else if (event.event().equals("cache_miss")) {
                        csvCacheMisses++;
                    }
                }
                if (event.component().equals("QueryExecutor")) {
                    if (queryExecutorDepth(event) != 1) {
                        continue;
                    }
                    if (event.event().equals("optimize")) {
                        optimizerMillis += event.elapsedMillis();
                    } else if (event.event().equals("plan")) {
                        physicalPlanMillis += event.elapsedMillis();
                    } else if (event.event().equals("pipeline")) {
                        pipelineMillis += event.elapsedMillis();
                    } else if (event.event().equals("result")) {
                        resultMillis += event.elapsedMillis();
                    } else if (event.event().equals("total")) {
                        queryExecutorMillis += event.elapsedMillis();
                    }
                }
            }
            return new ProfileSummary(
                    csvReadMillis,
                    csvSchemaMillis,
                    csvMaterializeMillis,
                    csvCacheHits,
                    csvCacheMisses,
                    optimizerMillis,
                    physicalPlanMillis,
                    pipelineMillis,
                    resultMillis,
                    queryExecutorMillis
            );
        }

        private static int queryExecutorDepth(ExecutionProfiler.ProfileEvent event) {
            String details = event.details();
            if (details == null || details.isBlank()) {
                return 1;
            }
            Matcher matcher = DEPTH_PATTERN.matcher(details);
            if (!matcher.find()) {
                return 1;
            }
            return Integer.parseInt(matcher.group(1));
        }
    }
}
