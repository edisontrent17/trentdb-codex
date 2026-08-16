package dev.trentdb.ast;

/** The SQL set operators supported by a compound SELECT. */
public enum SetOperation {
    UNION,
    UNION_ALL,
    EXCEPT,
    INTERSECT
}
