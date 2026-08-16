package dev.trentdb.ast; public record DeleteStatement(QualifiedName tableName, Expression where) implements Statement { }
