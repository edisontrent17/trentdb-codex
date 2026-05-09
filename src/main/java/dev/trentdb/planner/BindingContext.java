package dev.trentdb.planner;

import java.util.List;

record BindingContext(BindScope scope, List<BoundColumnBinding> columns, int starColumnCount) {
}
