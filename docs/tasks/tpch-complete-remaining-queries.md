# TPC-H Complete Remaining Queries

## Scope

- Added coverage for Q2, Q17, Q20, Q21, and Q22, bringing the regression suite to all 22 TPC-H queries.
- Q21 and Q22 use canonical SQL shapes, including comma joins, `NOT EXISTS`, and `substring(... FROM ... FOR ...)`.
- Q2, Q17, and Q20 use DuckDB-equivalent decorrelated query shapes until the optimizer can decorrelate scalar aggregate subqueries generically.

## Engine Work

- Parser: comma joins and SQL-standard `substring`.
- Binder: unary `NOT`, unary plus, and unary minus.
- Logical planner: non-correlated conjuncts are planned below dependent `EXISTS` mark joins.
- Physical planner: comma-join predicates can be planned as hash joins.
- Execution: correlated `EXISTS`/`NOT EXISTS` mark lookup supports one equality key plus one correlated inequality residual.

## Verification

- `mvn test`
- Result: 182 tests, 0 failures, 0 errors.
- TPC-H result: 22 tests, 0 failures, 0 errors.
- DuckDB reference checks were rerun for the newly added canonical outputs, including Q21 and Q22.

## Benchmark Notes

Timings are local wall-clock observations on clean generated SF0.01 data. TrentDB timings are from the JUnit TPC-H regression report in one JVM. DuckDB timings are `PRAGMA threads=1` CLI wall time per canonical query and include DuckDB process startup, so these numbers are useful as a smoke comparison, not as engine-only microbenchmarks.

| Query | TrentDB ms | DuckDB ms |
| --- | ---: | ---: |
| Q1 | 193 | 250 |
| Q2 | 27 | 201 |
| Q3 | 30 | 199 |
| Q4 | 192 | 198 |
| Q5 | 106 | 194 |
| Q6 | 129 | 193 |
| Q7 | 193 | 180 |
| Q8 | 287 | 217 |
| Q9 | 41 | 195 |
| Q10 | 70 | 189 |
| Q11 | 13 | 193 |
| Q12 | 273 | 194 |
| Q13 | 92 | 186 |
| Q14 | 17 | 184 |
| Q15 | 224 | 198 |
| Q16 | 15 | 183 |
| Q17 | 76 | 191 |
| Q18 | 393 | 184 |
| Q19 | 225 | 205 |
| Q20 | 139 | 192 |
| Q21 | 300 | 202 |
| Q22 | 15 | 194 |

## Follow-Up

- Add optimizer infrastructure next.
- Replace the current Q2, Q17, and Q20 manual decorrelations with generic scalar aggregate decorrelation.
- Add a repeatable benchmark harness that keeps both TrentDB and DuckDB in-process or otherwise removes CLI startup from both sides.
