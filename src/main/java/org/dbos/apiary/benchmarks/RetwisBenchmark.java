package org.dbos.apiary.benchmarks;

import com.google.protobuf.InvalidProtocolBufferException;
import org.dbos.apiary.utilities.ApiaryConfig;
import org.dbos.apiary.voltdb.VoltDBConnection;
import org.dbos.apiary.worker.ApiaryWorkerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.voltdb.client.ProcCallException;
import org.zeromq.ZContext;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class RetwisBenchmark {
    private static final Logger logger = LoggerFactory.getLogger(RetwisBenchmark.class);

    public static final int numPosts = 5000;
    public static final int numUsers = 1000;
    public static final int followsPerUsers = 50;
    public static final int percentageGetTimeline = 90;
    private static final int threadWarmupMs = 5000;  // First 5 seconds of request would be warm-up requests.
    private static final int threadPoolSize = 256;

    public static void benchmark(String voltAddr, Integer interval, Integer duration) throws IOException, InterruptedException, ProcCallException {
        VoltDBConnection ctxt = new VoltDBConnection(voltAddr, ApiaryConfig.voltdbPort);
        ctxt.client.callProcedure("TruncateTables");

        ZContext clientContext = new ZContext();
        ThreadLocal<ApiaryWorkerClient> client = ThreadLocal.withInitial(() -> new ApiaryWorkerClient(ZContext.shadow(clientContext)));

        Collection<Long> trialTimes = new ConcurrentLinkedQueue<>();

        AtomicInteger timestamp = new AtomicInteger(0);
        AtomicInteger postIDs = new AtomicInteger(0);
        for (int i = 0; i < numPosts; i++) {
            int userID = ThreadLocalRandom.current().nextInt(numUsers);
            int postID = postIDs.incrementAndGet();
            int ts = timestamp.incrementAndGet();
            String postString = String.format("matei%d", postID);
            client.get().executeFunction(ctxt.getHostname(new Object[]{String.valueOf(userID)}), "RetwisPost", String.valueOf(userID), String.valueOf(postID), String.valueOf(ts), postString);
        }
        for (int userID = 0; userID < numUsers; userID++) {
            int firstFollowee = ThreadLocalRandom.current().nextInt(numUsers);
            for (int i = 0; i < followsPerUsers; i++) {
                int followeeID = (firstFollowee + i) % numUsers;
                client.get().executeFunction(ctxt.getHostname(new Object[]{String.valueOf(userID)}), "RetwisFollow", String.valueOf(userID), String.valueOf(followeeID));
            }
        }
        logger.info("Finished loading!");

        Runnable r = () -> {
            long rStart = System.nanoTime();
            try {
                int userID = ThreadLocalRandom.current().nextInt(numUsers);
                client.get().executeFunction(ctxt.getHostname(new Object[]{String.valueOf(userID)}), "RetwisGetTimeline", String.valueOf(userID));
            } catch (InvalidProtocolBufferException e) {
                e.printStackTrace();
            }
            trialTimes.add(System.nanoTime() - rStart);
        };

        long startTime = System.currentTimeMillis();
        long endTime = startTime + (duration * 1000 + threadWarmupMs);

        ExecutorService threadPool = Executors.newFixedThreadPool(threadPoolSize);
        while (System.currentTimeMillis() < endTime) {
            long t = System.nanoTime();
            if ((System.currentTimeMillis() - startTime) <= threadWarmupMs) {
                // Clean up the arrays if still in warm up time.
                trialTimes.clear();
            }
            threadPool.submit(r);
            while (System.nanoTime() - t < interval.longValue() * 1000) {
                // Busy-spin
            }
        }

        long elapsedTime = (System.currentTimeMillis() - startTime) - threadWarmupMs;
        List<Long> queryTimes = trialTimes.stream().map(i -> i / 1000).sorted().collect(Collectors.toList());
        int numQueries = queryTimes.size();
        long average = queryTimes.stream().mapToLong(i -> i).sum() / numQueries;
        double throughput = (double) numQueries * 1000.0 / elapsedTime;
        long p50 = queryTimes.get(numQueries / 2);
        long p99 = queryTimes.get((numQueries * 99) / 100);
        logger.info("Duration: {} Interval: {}μs Queries: {} TPS: {} Average: {}μs p50: {}μs p99: {}μs", elapsedTime, interval, numQueries, String.format("%.03f", throughput), average, p50, p99);

        threadPool.shutdown();
        threadPool.awaitTermination(100000, TimeUnit.SECONDS);
        logger.info("All queries finished! {}", System.currentTimeMillis() - startTime);
    }
}