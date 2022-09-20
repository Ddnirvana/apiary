package org.dbos.apiary;

import org.dbos.apiary.function.ProvenanceBuffer;
import org.dbos.apiary.utilities.ApiaryConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class ProvenanceTests {
    private static final Logger logger = LoggerFactory.getLogger(ProvenanceTests.class);

    @BeforeAll
    public static void testConnection() {
        ProvenanceBuffer buf;
        try {
            buf = new ProvenanceBuffer(ApiaryConfig.postgres, "localhost");
            if (buf.conn.get() == null) {
                logger.info("Provenance buffer (Postgres) not available.");
                assumeTrue(false);
            }
        } catch (Exception e) {
            logger.info("Provenance buffer (Postgres) not available.");
            assumeTrue(false);
        } catch (NoClassDefFoundError e) {
            logger.info("Provenance buffer (Postgres) not available.");
            assumeTrue(false);
        }
    }

    @Test
    public void testProvenanceBuffer() throws InterruptedException, ClassNotFoundException, SQLException {
        logger.info("testProvenanceBuffer");
        ProvenanceBuffer buf = new ProvenanceBuffer(ApiaryConfig.postgres, "localhost");
        String table = ProvenanceBuffer.PROV_FuncInvocations;

        // Wait until previous exporter finished.
        Thread.sleep(ProvenanceBuffer.exportInterval * 2);
        Connection conn = buf.conn.get();
        Statement stmt = conn.createStatement();
        stmt.execute(String.format("TRUNCATE TABLE %s;", table));

        // Add something to function invocation log table.
        long txid = 1234l;
        long timestamp = 3456789l;
        long executionID = 456l;
        String service = "testService";
        String funcName = "testFunction";
        buf.addEntry(table, txid, timestamp, executionID, service, funcName);

        long txid2 = 2222l;
        long timestamp2 = 456789l;
        long executionID2 = 789l;
        buf.addEntry(table, txid2, timestamp2, executionID2, service, funcName);
        Thread.sleep(ProvenanceBuffer.exportInterval * 2);

        ResultSet rs = stmt.executeQuery(String.format("SELECT * FROM %s ORDER BY %s;", table, ProvenanceBuffer.PROV_APIARY_TRANSACTION_ID));
        int cnt = 0;
        while (rs.next()) {
            long resTxid = rs.getLong(ProvenanceBuffer.PROV_APIARY_TRANSACTION_ID);
            long resTimestamp = rs.getLong(ProvenanceBuffer.PROV_APIARY_TIMESTAMP);
            long resExecId = rs.getLong(ProvenanceBuffer.PROV_EXECUTIONID);
            String resService = rs.getString(ProvenanceBuffer.PROV_SERVICE);
            String resFuncName = rs.getString(ProvenanceBuffer.PROV_PROCEDURENAME);
            if (cnt == 0) {
                assertEquals(txid, resTxid);
                assertEquals(timestamp, resTimestamp);
                assertEquals(executionID, resExecId);
                assertTrue(funcName.equals(resFuncName));
            } else {
                assertEquals(txid2, resTxid);
                assertEquals(timestamp2, resTimestamp);
                assertEquals(executionID2, resExecId);
                assertTrue(funcName.equals(resFuncName));
            }
            assertTrue(service.equals(resService));

            cnt++;
        }
        assertEquals(2, cnt);
        buf.close();
    }

}
