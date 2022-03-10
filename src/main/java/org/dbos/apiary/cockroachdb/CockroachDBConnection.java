package org.dbos.apiary.cockroachdb;

import org.dbos.apiary.executor.ApiaryConnection;
import org.dbos.apiary.executor.FunctionOutput;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Not thread-safe and only works for a single table.
public class CockroachDBConnection implements ApiaryConnection {
    private static final Logger logger = LoggerFactory.getLogger(CockroachDBConnection.class);

    private final Connection c;
    private final String tableName;
    private final Map<String, Callable<CockroachDBFunctionInterface>> functions = new HashMap<>();
    private final Map<Integer, String> partitionHostMap = new HashMap<>();

    private class CockroachDBRange {
        public String startKey, endKey;
        public Integer leaseHolder;

        public CockroachDBRange(String startKey, String endKey, Integer leaseHolder) {
            this.startKey = startKey;
            this.endKey = endKey;
            this.leaseHolder = leaseHolder;
        }

    };

    private final ArrayList<CockroachDBRange> cockroachDBRanges = new ArrayList<>();

    public CockroachDBConnection(Connection c, String tableName) throws SQLException {
        this.c = c;
        this.tableName = tableName;
        c.setAutoCommit(false);
        updatePartitionInfo();
    }

    public void registerFunction(String name, Callable<CockroachDBFunctionInterface> function) {
        functions.put(name, function);
    }

    @Override
    public FunctionOutput callFunction(String name, Object... inputs) throws Exception {
        CockroachDBFunctionInterface function = functions.get(name).call();
        FunctionOutput f = null;
        try {
            f = function.runFunction(inputs);
            c.commit();
        } catch (Exception e) {
            e.printStackTrace();
            c.rollback();
        }
        return f;
    }

    @Override
    public void updatePartitionInfo() {
        try {
            // Fill in `partitionHostMap`.
            partitionHostMap.clear();
            Statement getNodes = c.createStatement();
            ResultSet nodes = getNodes.executeQuery("select node_id, address from crdb_internal.gossip_nodes;");
            while (nodes.next()) {
                String[] ipAddrAndPort = nodes.getString("address").split(":");
                partitionHostMap.put(nodes.getInt("node_id"), /* ipAddr= */ipAddrAndPort[0]);
            }
            getNodes.close();

            // Fill in `cockroachDBRanges`.
            cockroachDBRanges.clear();
            Statement getRanges = c.createStatement();
            ResultSet ranges = getRanges.executeQuery(String.format("SHOW RANGES FROM table %s", tableName));
            while (ranges.next()) {
                cockroachDBRanges.add(new CockroachDBRange(ranges.getString("start_key"), ranges.getString("end_key"),
                        ranges.getInt("lease_holder")));
            }
            getRanges.close();

        } catch (Exception e) {
            e.printStackTrace();
        }

        logger.info("Updated partitionHostMap {}", partitionHostMap.entrySet());
        ArrayList<String> cockroachDBRangesStr = cockroachDBRanges.stream().map(range -> {
            return String.format("{[%s, %s], leaseholder=%d}", range.startKey, range.endKey, range.leaseHolder);
        }).collect(Collectors.toCollection(ArrayList<String>::new));
        logger.info("Updated cockroachDBRanges {}", cockroachDBRangesStr);

        return;
    }

    @Override
    public int getNumPartitions() {
        return partitionHostMap.size();
    }

    @Override
    public String getHostname(Object[] input) {
        assert (input[0] instanceof String); // TODO: Support int type explicitly.
        String key = (String) input[0];

        // Linear search for now. In the future, we can sort and bin search or any other
        // efficient algo (in small scale, there aren't that many ranges).
        for (CockroachDBRange range : cockroachDBRanges) {
            Boolean satisfiesStart = range.startKey == null || (key.compareTo(range.startKey) >= 0);
            Boolean satisfiesEnd = range.endKey == null || (range.endKey.compareTo(key) >= 0);

            if (satisfiesStart && satisfiesEnd) {
                return partitionHostMap.get(range.leaseHolder);
            }
        }

        // If we can't find the range, something went wrong. Just log and fallback to
        // the first node.
        return partitionHostMap.get(0);
    }

    @Override
    public Map<Integer, String> getPartitionHostMap() {
        return partitionHostMap;
    }

}