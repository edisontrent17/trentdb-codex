package dev.trentdb.planner;

import dev.trentdb.ast.DropIndexStatement;

public record BoundDropIndexStatement(DropIndexStatement statement) implements BoundStatement { }
