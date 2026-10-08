package com.veritrade.ingestion.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/** Records every SQL statement Hibernate prepares; registered by class name in a test's properties. */
public class SqlRecorder implements StatementInspector {

    public static final String PROPERTY =
            "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.veritrade.ingestion.support.SqlRecorder";

    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql);
        return sql;
    }

    public static void clear() {
        STATEMENTS.clear();
    }

    public static List<String> statements() {
        return List.copyOf(STATEMENTS);
    }
}
