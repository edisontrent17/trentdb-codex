# TPC-H Complete Remaining Queries

## Scope

- Added coverage for Q2, Q17, Q20, Q21, and Q22, bringing the regression suite to all 22 TPC-H queries.
- Q2, Q17, Q20, Q21, and Q22 use canonical SQL shapes, including scalar aggregate subqueries, nested `IN`, comma joins, `NOT EXISTS`, and `substring(... FROM ... FOR ...)`.

## Engine Work

- Parser: comma joins and SQL-standard `substring`.
- Binder: unary `NOT`, unary plus, unary minus, local-first outer column resolution, and scalar subquery correlation metadata.
- Logical planner: non-correlated conjuncts are planned below dependent `EXISTS` mark joins.
- Physical planner: comma-join predicates can be planned as hash joins, while correlated scalar predicates stay above mixed-scope inputs.
- Execution: correlated `EXISTS`/`NOT EXISTS` mark lookup supports one equality key plus one correlated inequality residual; correlated scalar subqueries execute by substituting the current outer row and rerunning the scalar subquery.

## Verification

- `mvn test`
- Result: 182 tests, 0 failures, 0 errors.
- TPC-H result: 22 tests, 0 failures, 0 errors.
- DuckDB reference checks were rerun for the newly added canonical outputs, including Q2, Q17, Q20, Q21, and Q22.

## Benchmark Notes

Timings are local wall-clock observations on clean generated SF0.01 data. TrentDB timings are from the JUnit TPC-H regression report in one JVM. DuckDB timings are `PRAGMA threads=1` CLI wall time per canonical query and include DuckDB process startup, so these numbers are useful as a smoke comparison, not as engine-only microbenchmarks.

| Query | TrentDB ms | DuckDB ms |
| --- | ---: | ---: |
| Q1 | 173 | 250 |
| Q2 | 20 | 201 |
| Q3 | 32 | 199 |
| Q4 | 135 | 198 |
| Q5 | 118 | 194 |
| Q6 | 146 | 193 |
| Q7 | 112 | 180 |
| Q8 | 302 | 217 |
| Q9 | 42 | 195 |
| Q10 | 68 | 189 |
| Q11 | 7 | 193 |
| Q12 | 281 | 194 |
| Q13 | 93 | 186 |
| Q14 | 15 | 184 |
| Q15 | 244 | 198 |
| Q16 | 16 | 183 |
| Q17 | 47 | 191 |
| Q18 | 391 | 184 |
| Q19 | 192 | 205 |
| Q20 | 64335 | 192 |
| Q21 | 313 | 202 |
| Q22 | 13 | 194 |

## Follow-Up

- Add optimizer infrastructure next.
- Decorrelate scalar aggregate subqueries into join-shaped plans, with Q20 as the first performance target.
- Add a repeatable benchmark harness that keeps both TrentDB and DuckDB in-process or otherwise removes CLI startup from both sides.
