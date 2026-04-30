# Scalar Function Registry Slice

## Goal

Add the first real extension point: a scalar function registry used by the binder.

This is intentionally not a full extension loader yet. The registry gives future extensions a meaningful subsystem to register into, and built-ins should use the same path that extensions will use later.

## Scope

- add scalar function metadata
- add scalar function registry
- register initial built-in scalar functions through the registry
- bind function calls in `SELECT` lists
- validate function existence
- validate argument count
- validate argument logical types
- add bound function expression

## Initial Built-In

Start with:

- `lower(text) -> text`

Rationale:

- simple, common SQL function
- exercises function name resolution
- exercises argument count validation
- exercises text type validation
- does not require numeric coercion yet

## Out of Scope

- extension loader
- runtime execution of functions
- overload resolution beyond exact argument types
- implicit casts
- aggregate functions
- table functions
- functions in `WHERE`
- variadic functions

## Tasks

- [x] add `dev.trentdb.function` package
- [x] add `ScalarFunction`
- [x] add `FunctionRegistry`
- [x] add duplicate function registration checks
- [x] add missing function lookup errors
- [x] add argument count validation
- [x] add exact argument type validation
- [x] add built-in registration for `lower(text) -> text`
- [x] add `BoundFunctionExpression`
- [x] bind function calls in `SELECT` lists
- [x] keep unsupported function usage in `WHERE` rejected for now
- [x] add tests for successful `lower(name)` binding
- [x] add tests for missing function
- [x] add tests for wrong argument count
- [x] add tests for wrong argument type
- [x] run `mvn test`

## Notes

- Built-ins must register through `FunctionRegistry`; no binder hard-coding.
- Keep catalog and transaction lookup behavior unchanged.
- This registry is the future extension seam.
