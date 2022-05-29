package org.dbos.apiary.procedures.postgres.tests;

import org.dbos.apiary.postgres.PostgresContext;
import org.dbos.apiary.postgres.PostgresFunction;

import java.sql.ResultSet;
import java.sql.SQLException;

public class PostgresIndexPerson extends PostgresFunction {

    private static final String insert = "INSERT INTO PersonTable(Name, Number) VALUES (?, ?);";

    public static int runFunction(PostgresContext ctxt, String name, int number) throws SQLException {
        ctxt.executeUpdate(insert, name, number);
        ctxt.apiaryCallFunction("ElasticsearchIndexPerson", name, number);
        return number;
    }
}