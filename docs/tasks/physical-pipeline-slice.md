# Physical Pipeline Slice

## Goal

Move execution behind a DuckDB-shaped physical plan and pipeline layer.

Current execution runs directly from logical operators. This slice introduces an explicit physical planning boundary and a pipeline model with a source, intermediate operators, and a sink.

## Scope

- add physical operator package
- add physical source/operator/sink interfaces
- add physical table scan source
- add physical filter operator
- add physical projection operator
- add physical explain source
- add physical result collector sink
- add physical planner
- add pipeline object
- add pipeline executor
- refactor query executor to run physical pipelines
- keep existing vector, selection, dictionary, and validity behavior unchanged

## Out of Scope

- parallel pipelines
- pipeline dependencies
- pipeline events
- local/global operator state
- blocking operators
- primitive vector storage
- optimized physical planning

## Tasks

- [x] add physical operator package
- [x] add `PhysicalOperator`
- [x] add `PhysicalSource`
- [x] add `PhysicalIntermediateOperator`
- [x] add `PhysicalSink`
- [x] add `PhysicalTableScan`
- [x] add `PhysicalFilter`
- [x] add `PhysicalProjection`
- [x] add `PhysicalExplain`
- [x] add `PhysicalResultCollector`
- [x] add `PhysicalPlanner`
- [x] add `Pipeline`
- [x] add `PipelineExecutor`
- [x] refactor `QueryExecutor`
- [x] add pipeline shape tests
- [x] run `mvn test`

## Notes

- Keep this single-threaded for now.
- The shape should mirror DuckDB, but without parallel/event complexity yet.
- Do not change vector internals in this slice.
