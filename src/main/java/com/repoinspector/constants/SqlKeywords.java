package com.repoinspector.constants;

import java.util.Set;

/** SQL keywords used for syntax highlighting in the SQL log panel. Single source of truth. */
public final class SqlKeywords {

    private SqlKeywords() {}

    public static final Set<String> ALL = Set.of(
            "SELECT", "FROM", "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL",
            "ON", "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE", "BETWEEN", "EXISTS",
            "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "DROP",
            "TABLE", "INDEX", "ORDER", "BY", "GROUP", "HAVING", "LIMIT", "OFFSET",
            "DISTINCT", "AS", "CASE", "WHEN", "THEN", "ELSE", "END",
            "UNION", "ALL", "EXCEPT", "INTERSECT", "WITH", "RETURNING",
            "ASC", "DESC", "NULLS", "FIRST", "LAST",
            "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE", "CAST"
    );
}
