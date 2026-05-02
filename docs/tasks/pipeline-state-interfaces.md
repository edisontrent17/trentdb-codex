# Pipeline State Interfaces

## Goal

Introduce DuckDB-shaped global/local state interfaces for physical pipeline execution before adding pipeline breakers or parallel execution.

## Scope

- add global/local state types for sources, regular operators, and sinks
- add input records that carry state into execution methods
- make `Pipeline` own global state
- make `PipelineExecutor` create local state per execution instance
- keep current single-threaded behavior unchanged

## Tasks

- [x] inspect current physical execution interfaces
- [x] add source state types
- [x] add operator state types
- [x] add sink state types
- [x] add state input records
- [x] add default state factory methods to physical interfaces
- [x] make `Pipeline` own global states
- [x] make `PipelineExecutor` create local states
- [x] run `mvn test`

## Notes

- This is scaffolding for future pipeline breakers and parallel execution.
- The current execution path remains single-threaded.
- Existing operators use default empty state until an operator needs real state.
