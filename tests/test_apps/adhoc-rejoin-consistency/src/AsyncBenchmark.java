/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */
/*
 * This samples uses the native asynchronous request processing protocol
 * to post requests to the VoltDB server, thus leveraging to the maximum
 * VoltDB's ability to run requests in parallel on multiple database
 * partitions, and multiple servers.
 *
 * While asynchronous processing is (marginally) more convoluted to work
 * with and not adapted to all workloads, it is the preferred interaction
 * model to VoltDB as it guarantees blazing performance.
 *
 * Because there is a risk of 'firehosing' a database cluster (if the
 * cluster is too slow (slow or too few CPUs), this sample performs
 * auto-tuning to target a specific latency (5ms by default).
 * This tuning process, as demonstrated here, is important and should be
 * part of your pre-launch evaluation so you can adequately provision your
 * VoltDB cluster with the number of servers required for your needs.
 */

package AdHocRejoinConsistency;

import java.io.IOException;
import java.io.File;
import java.util.Stack;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Random;
import java.lang.Thread;

import org.voltdb.CLIConfig;
//import org.voltdb.VoltProcedure.VoltAbortException;
import org.voltdb.VoltProcedure.VoltAbortException;
import org.voltdb.VoltTable;
import org.voltdb.VoltTableRow;
import org.voltdb.VoltTypeException;
import org.voltdb.VoltSystemProcedure;
import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ClientStats;
import org.voltdb.client.ClientStatsContext;
import org.voltdb.client.ClientStatusListenerExt;
import org.voltdb.client.NoConnectionsException;
import org.voltdb.client.NullCallback;
import org.voltdb.client.ProcCallException;
import org.voltdb.client.ProcedureCallback;
import org.voltdb.dtxn.RPQInterface;

import sun.misc.Signal;
import sun.misc.SignalHandler;

public class AsyncBenchmark {

    // Table of TEST cases.
    // -PTN means test targets a partitioned table
    // -REP means test targets a replicated table
    public enum Tests {
        ADHOCSINGLEPARTPTN,
        ADHOCMULTIPARTPTN,
        ADHOCSINGLEPARTREP,
        ADHOCMULTIPARTREP,
        UPDATEAPPLICATIONCATALOG,
        WRSINGLEPARTSTOREDPROCPTN,
        WRMULTIPARTSTOREDPROCPTN,
        WRMULTIPARTSTOREDPROCREP,
        LOADSINGLEPARTITIONTABLEPTN,
        LOADMULTIPARTITIONTABLEREP
    }

    // handy, rather than typing this out several times
    static final String HORIZONTAL_RULE = "----------" + "----------"
            + "----------" + "----------" + "----------" + "----------"
            + "----------" + "----------" + "\n";
    // Test Case
    Tests testCase = null;
    // validated command line configuration
    final VoterConfig config;
    // Reference to the database connection we will use
    final Client client;
    // Timer for periodic stats printing
    Timer timer;
    // Benchmark start time
    long benchmarkStartTS;
    Thread benchmarkThread;
    String vemTestHome;
    // used by signal handlers to terminate benchmark activity
    boolean runBenchmark = true;
    // Statistics manager objects from the client
    final ClientStatsContext periodicStatsContext;
    final ClientStatsContext fullStatsContext;

    final AtomicLong successfulAdHoc = new AtomicLong(0);
    final AtomicLong failedAdHoc = new AtomicLong(0);
    final AtomicLong totalAdHoc = new AtomicLong(0);
    final AtomicLong successfulAsync = new AtomicLong(0);
    final AtomicLong failedAsync = new AtomicLong(0);
    final AtomicLong totalAsync = new AtomicLong(0);

    final

    Random rand = new Random();

    int nPartitions = 0;

    /**
     * Uses included {@link CLIConfig} class to declaratively state command line
     * options with defaults and validation.
     */
    static class VoterConfig extends CLIConfig {
        @Option(desc = "Interval for performance feedback, in seconds.")
        long displayinterval = 5;

        @Option(desc = "Benchmark duration, in seconds.")
        int duration = 60;

        @Option(desc = "Comma separated list of the form server[:port] to connect to.")
        String servers = "localhost";

        @Option(desc = "test to run")
        String testcase = "ALL";

        @Option(desc = "path to catalog jar files")
        String catalogpath = "";

        @Option(desc = "Maximum TPS rate for benchmark.")
        int ratelimit = 100000;

        @Option(desc = "Determine transaction rate dynamically based on latency.")
        boolean autotune = false;

        @Option(desc = "Server-side latency target for auto-tuning.")
        int latencytarget = 5;

        @Option(desc = "Filename to write raw summary statistics to.")
        String statsfile = "";

        @Option(desc = "User name for connection.")
        String user = "";

        @Option(desc = "Password for connection.")
        String password = "";

        @Override
        public void validate() {
            if (duration <= 0)
                exitWithMessageAndUsage("duration must be > 0");
            if (displayinterval <= 0)
                exitWithMessageAndUsage("displayinterval must be > 0");
            if (ratelimit <= 0)
                exitWithMessageAndUsage("ratelimit must be > 0");
            if (latencytarget <= 0)
                exitWithMessageAndUsage("latencytarget must be > 0");
        }
    }

    /**
     * Provides a callback to be notified on node failure. This example only
     * logs the event.
     */
    class StatusListener extends ClientStatusListenerExt {
        @Override
        public void connectionLost(String hostname, int port,
                int connectionsLeft, DisconnectCause cause) {
            // if the benchmark is still active
            if ((System.currentTimeMillis() - benchmarkStartTS) < (config.duration * 1000)) {
                System.err.printf("Connection to %s:%d was lost.\n", hostname,
                        port);
            }
        }
    }

    /**
     * Constructor for benchmark instance. Configures VoltDB client and prints
     * configuration.
     *
     * @param config
     *            Parsed & validated CLI options.
     */
    public AsyncBenchmark(VoterConfig config) {
        this.config = config;

        vemTestHome = System.getenv("VEMTEST_HOME");
        System.err.printf("VEMTEST_HOME: %s\n", vemTestHome);
        if (vemTestHome == "" || vemTestHome == null) {
            System.err.println("ERROR VEMTEST_HOME is not set");
            System.exit(1);
        }

        try { testCase = Tests.valueOf(config.testcase); }
        catch (Exception e) {
            if (! config.testcase.equalsIgnoreCase("ALL")) {
                System.err.printf("ERROR Unknown testcase: %s\n", config.testcase);
                System.exit(1);
                //throw new RuntimeException(e);
            }
        }
        // to execute ALL tests, testCase is null going fowrard

        ClientConfig clientConfig = new ClientConfig(config.user,
                config.password, new StatusListener());
        if (config.autotune) {
            clientConfig.enableAutoTune();
            clientConfig.setAutoTuneTargetInternalLatency(config.latencytarget);
        } else {
            clientConfig.setMaxTransactionsPerSecond(config.ratelimit);
        }
        client = ClientFactory.createClient(clientConfig);

        periodicStatsContext = client.createStatsContext();
        fullStatsContext = client.createStatsContext();

        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Command Line Configuration");
        System.out.println(HORIZONTAL_RULE);
        System.out.println(config.getConfigDumpString());

        Signal.handle(new Signal("TERM"), new SignalHandler() {
            public void handle(Signal sig) {

                System.err.println("Received SIGTERM signal. Will teardown.");
                // stop run, it will clean up
                runBenchmark = false;
                timer.cancel();
                benchmarkThread.interrupt();
            }
        });
        Signal.handle(new Signal("INT"), new SignalHandler() {
            public void handle(Signal sig) {

                System.err.println("Received SIGINT signal. Will teardown.");
                // stop run, it will clean up
                runBenchmark = false;
                timer.cancel();
                benchmarkThread.interrupt();
            }
        });
    }

    /**
     * Connect to a single server with retry. Limited exponential backoff. No
     * timeout. This will run until the process is killed if it's not able to
     * connect.
     *
     * @param server
     *            hostname:port or just hostname (hostname can be ip).
     */
    void connectToOneServerWithRetry(String server) {
        int sleep = 1000;
        while (runBenchmark) {
            try {
                client.createConnection(server);
                break;
            } catch (Exception e) {
                System.err.printf(
                        "Connection failed - retrying in %d second(s).\n",
                        sleep / 1000);
                try {
                    Thread.sleep(sleep);
                } catch (Exception interruted) {
                }
                if (sleep < 8000)
                    sleep += sleep;
            }
        }
        System.out.printf("Connected to VoltDB node at: %s.\n", server);
    }

    /**
     * Connect to a set of servers in parallel. Each will retry until
     * connection. This call will block until all have connected.
     *
     * @param servers
     *            A comma separated list of servers using the hostname:port
     *            syntax (where :port is optional).
     * @throws InterruptedException
     *             if anything bad happens with the threads.
     */
    void connect(String servers) throws InterruptedException {
        System.out.println("Connecting to VoltDB...");

        String[] serverArray = servers.split(",");
        final CountDownLatch connections = new CountDownLatch(
                serverArray.length);

        // use a new thread to connect to each server
        for (final String server : serverArray) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    connectToOneServerWithRetry(server);
                    connections.countDown();
                }
            }).start();
        }
        // block until all have connected
        connections.await();
    }

    /**
     * Create a Timer task to display performance data on the Vote procedure It
     * calls printStatistics() every displayInterval seconds
     */
    public void schedulePeriodicStats() {
        timer = new Timer();
        TimerTask statsPrinting = new TimerTask() {
            @Override
            public void run() {
                printStatistics();
            }
        };
        timer.scheduleAtFixedRate(statsPrinting, config.displayinterval * 1000,
                config.displayinterval * 1000);
    }

    /**
     * Prints a one line update on performance that can be printed periodically
     * during a benchmark.
     */
    public synchronized void printStatistics() {
        ClientStats stats = periodicStatsContext.fetchAndResetBaseline()
                .getStats();
        long time = Math
                .round((stats.getEndTimestamp() - benchmarkStartTS) / 1000.0);

        System.out.printf("%02d:%02d:%02d ", time / 3600, (time / 60) % 60,
                time % 60);
        System.out.printf("Throughput %d/s, ", stats.getTxnThroughput());
        System.out.printf("Aborts/Failures %d/%d, ",
                stats.getInvocationAborts(), stats.getInvocationErrors());
        System.out.printf("Avg/95%% Latency %.2f/%dms\n",
                stats.getAverageLatency(), stats.kPercentileLatency(0.95));
    }

    /**
     * Prints the results of the voting simulation and statistics about
     * performance.
     *
     * @throws Exception
     *             if anything unexpected happens.
     */
    public synchronized void printResults() throws Exception {
        ClientStats stats = fullStatsContext.fetch().getStats();

        // 3. Performance statistics
        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Client Workload Statistics");
        System.out.println(HORIZONTAL_RULE);

        System.out.printf("Async total: %d successful: %d\n", totalAsync.get(),
                successfulAsync.get());
        System.out.printf("Adhoc total: %d\n", totalAdHoc.get());

        System.out.print("\n" + HORIZONTAL_RULE);

        System.out.printf("Average throughput:            %,9d txns/sec\n",
                stats.getTxnThroughput());
        System.out.printf("Average latency:               %,9.2f ms\n",
                stats.getAverageLatency());
        System.out.printf("95th percentile latency:       %,9d ms\n",
                stats.kPercentileLatency(.95));
        System.out.printf("99th percentile latency:       %,9d ms\n",
                stats.kPercentileLatency(.99));

        System.out.print("\n" + HORIZONTAL_RULE);
        System.out.println(" System Server Statistics");
        System.out.println(HORIZONTAL_RULE);

        if (config.autotune) {
            System.out.printf("Targeted Internal Avg Latency: %,9d ms\n",
                    config.latencytarget);
        }
        System.out.printf("Reported Internal Avg Latency: %,9.2f ms\n",
                stats.getAverageInternalLatency());

        // 4. Write stats to file if requested
        client.writeSummaryCSV(stats, config.statsfile);
    }

    /**
     * Callback to handle the response to a stored procedure call. Tracks
     * response types.
     *
     */
    class SequenceCallback implements ProcedureCallback {
        @Override
        public void clientCallback(ClientResponse response) throws Exception {
            if (response.getStatus() != ClientResponse.SUCCESS) {
                System.err.printf("Database operation failed with %s\n",
                        response.getStatusString());
                //System.exit(1);
            } else {
                successfulAsync.getAndIncrement();
            }
        }
    }

    /**
     * Core benchmark code. Connect. Initialize. Run the loop. Cleanup. Print
     * Results.
     *
     * @throws Exception
     *             if anything unexpected happens.
     */
    public void runBenchmark() throws Exception {

        benchmarkThread = Thread.currentThread();

        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Setup & Initialization");
        System.out.println(HORIZONTAL_RULE);

        // connect to one or more servers, loop until success
        // first server in the list is a blessed node, we only connect to it
        // second server in the list is the rejoinable node, we never connect
        // to it in this part of the test
        connect(config.servers.split(",")[0]);

        // get the partition count
        ClientResponse resp = client.callProcedure("@Statistics", "PARTITIONCOUNT", 0);
        VoltTable[] tpc = resp.getResults();
        nPartitions=0;
        while (tpc[0].advanceRow()) {
            nPartitions = (int) tpc[0].getLong(0);
        }
        System.out.printf("partition count: %d\n", nPartitions);
        if (nPartitions < 2) {
            System.err.printf("Less than 2 partitions\n", nPartitions);
            System.exit(1);
        }

        client.callProcedure("Initialize", nPartitions);

        System.out.print(HORIZONTAL_RULE);
        System.out.println("Starting Benchmark");
        System.out.println(HORIZONTAL_RULE);

        // print periodic statistics to the console
        benchmarkStartTS = System.currentTimeMillis();
        schedulePeriodicStats();

        // Run the benchmark loop for the requested duration
        // The throughput may be throttled depending on client configuration
        System.out.println("\nRunning benchmark...");
        final long benchmarkEndTime = System.currentTimeMillis() + (1000l * config.duration);
        //String qOps[] = {"*","/"};
        String qOps[] = {"+","+"};
        int lastCatalog = 0;

        while (runBenchmark && (benchmarkEndTime > System.currentTimeMillis())) {

            int r = rand.nextInt(2); // 50/50 multiply or divide operation
            int p = rand.nextInt(nPartitions); //choose a counter
            int c = rand.nextInt(1)+2; // values 2-3

            Tests tc;
            if (testCase == null) {
                tc = Tests.values()[rand.nextInt(Tests.values().length)];
                //System.err.printf("selected test: %s\n", tc);
            }
            else
                tc = testCase;

            totalAsync.getAndIncrement();

            try {
                switch (tc) {

                case ADHOCSINGLEPARTPTN:
                    // single part adhoc query ENG-3886 also see ENG-4076
                    //System.err.printf("adhoc singlepart...\n");
                    client.callProcedure(new SequenceCallback(),
                            "@AdHoc", "UPDATE COUNTERS_PTN set COUNTER=COUNTER"+ qOps[r] + Integer.toString(c) + " WHERE id=" +
                                    Integer.toString(p) + ";" );
                    totalAdHoc.getAndIncrement();
                    break;

                case ADHOCMULTIPARTPTN:
                    // multipart adhoc query ENG-3887
                    //System.err.printf("adhoc multipart...\n");
                    client.callProcedure(new SequenceCallback(),
                            "@AdHoc", "UPDATE COUNTERS_PTN set COUNTER=COUNTER"+ qOps[r] + Integer.toString(c) +";");
                    totalAdHoc.getAndIncrement();
                    break;

                case ADHOCSINGLEPARTREP:
                    // multipart adhoc query ENG-3887
                    //System.err.printf("adhoc multipart...\n");
                    client.callProcedure(new SequenceCallback(),
                            "@AdHoc", "UPDATE COUNTERS_REP set COUNTER=COUNTER"+ qOps[r] + Integer.toString(c) + " WHERE id=" +
                                    Integer.toString(p) + ";" );
                    totalAdHoc.getAndIncrement();
                    break;

                case ADHOCMULTIPARTREP:
                    // multipart adhoc query ENG-3887
                    //System.err.printf("adhoc multipart...\n");
                    client.callProcedure(new SequenceCallback(),
                            "@AdHoc", "UPDATE COUNTERS_REP set COUNTER=COUNTER"+ qOps[r] + Integer.toString(c) +";");
                    totalAdHoc.getAndIncrement();
                    break;

                case UPDATEAPPLICATIONCATALOG:
                    // UpdateApplicationCatalog
                    // we want the update application catalog command to be issued during the rejoin
                    // but the client is async relative to killing and rejoining.
                    // also, the rejoin time will vary a lot depending on the nodes and sitesperhost.
                    // so long run times will be required to possibly hit the right timing.
                    // bottom line-this is not going to be a meaningful test when run for short durations.
                    ClientResponse response = null;
                    // Find out which catalog we are on
                    try {
                        response = client.callProcedure("@AdHoc", "Select count(*) from replicated;");
                        if (response.getStatus() == ClientResponse.SUCCESS) {
                            lastCatalog = 1;
                        } else {
                            lastCatalog = 0;
                        }
                    }
                    catch (ProcCallException e) {
                        // expect a planner exception on catalog 0
                        //e.printStackTrace();
                        lastCatalog = 0;
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                        throw new RuntimeException();
                    }
                    // running ALL, we don't wait, otherwise go slow.
                    if (testCase != null) {
                        Thread.sleep(rand.nextInt(20000)+1); // really slow
                    }
                    // now, flip to the other catalog
                    // this runs as a synchronous tx (for now)
                    System.err.printf("updateapplicationcatalog %d...\n", lastCatalog);
                    // create catalog
                    String catPath = "/home/prosegay/branches/ENG-3884/voltdb/tests/test_apps/adhoc-rejoin-consistency";
                    File catalog_files[] = { new File(catPath + "/AdHocRejoinConsistency.jar"),
                            new File(catPath + "/AdHocRejoinConsistency2.jar") };
                    File file2 = new File(catPath + "/deployment.xml");
                    // Flip the catalog
                    lastCatalog = (lastCatalog+1) % 2;
                    response = client.updateApplicationCatalog(catalog_files[ lastCatalog ], file2);
                    if (response.getStatus() != ClientResponse.SUCCESS) {
                        System.err.printf("UAC operation failed with %s\n", response.getStatusString());
                        throw new RuntimeException();
                    } else {
                        successfulAsync.getAndIncrement();
                        // check if we're on the right catalog
                        try {
                            response = client.callProcedure("@AdHoc", "Select count(*) from replicated;");
                            switch (lastCatalog) {
                            case 0:
                                if (response.getStatus() == ClientResponse.SUCCESS) {
                                    System.err.printf("unexpected result for catalog 0\n");
                                    throw new RuntimeException();
                                }
                                break;
                            case 1:
                                if (response.getStatus() != ClientResponse.SUCCESS) {
                                    System.err.printf("unexpected result for catalog 1\n");
                                    throw new RuntimeException();
                                }
                                break;
                            default:
                                throw new RuntimeException("Invalid catalog switch value");
                            }
                        }
                        catch (ProcCallException e) {
                            if (lastCatalog != 0) {
                                e.printStackTrace();
                                System.err.printf("unexpected result for catalog 1 in proccallexception %d\n%s\n", lastCatalog,
                                        e.getMessage());
                                throw new RuntimeException();
                            }
                        }
                    }
                    break;

                case WRSINGLEPARTSTOREDPROCPTN:
                    // single-part stored procedure
                    client.callProcedure(new SequenceCallback(), "getNextFromPtn", p, nPartitions);
                    break;

                case WRMULTIPARTSTOREDPROCPTN:
                    // multi-part stored procedure
                    // Updates a partitioned table
                    client.callProcedure(new SequenceCallback(), "MPUpdatePtn");

                case WRMULTIPARTSTOREDPROCREP:
                    // multi-part stored procedure
                    // Updates a replicated table
                    client.callProcedure(new SequenceCallback(), "MPUpdateRep");
                    break;

                case LOADSINGLEPARTITIONTABLEPTN: // this case is failing
                    break;
                case LOADMULTIPARTITIONTABLEREP:
                    // LoadSinglePartitionTable LoadMultiPartitionTable ENG-3885 part 1 of 2
                    // voltLoadTable is not client exposed
                    // voltLoadTable is used for the initial load on DR
                    // Get all the rows from the counter table and insert them into the
                    // like_counter table, then compare both copies of the target table after rejoin
                    response = null;
                    try {
                        response = client.callProcedure("getRowFromPtn", p);
                        if (response.getStatus() != ClientResponse.SUCCESS) {
                            System.err.printf("FATAL Unexpectd result getting source row %s\n",
                                    response.getStatusString());
                            throw new RuntimeException();
                        }
                    }
                    catch (ProcCallException e) {
                        //e.printStackTrace();
                        System.err.printf("unexpected exception getting source row\n %s\n", e.getMessage());
                    }
                    VoltTable vt[] = response.getResults();
                    if ( vt.length == 0 ) {
                        System.err.printf("FATAL VoltTable[] object has no elememts\n");
                        throw new RuntimeException();
                    }
                    if ( vt[0].getRowCount() != 1 ) {
                        System.err.printf("FATAL VoltTable object has wrong number of rows %d\n", vt[0].getRowCount());
                        throw new RuntimeException();
                    }
                    VoltTable vt0 = vt[0];
                    // insert row into target table
                    try {
                        switch (tc) {
                        case LOADSINGLEPARTITIONTABLEPTN:
                            client.callProcedure(new SequenceCallback(),
                                    "@LoadSinglepartitionTable", "LIKE_COUNTERS_PTN", vt0);
                            break;
                        case LOADMULTIPARTITIONTABLEREP:
                            client.callProcedure(new SequenceCallback(),
                                    "@LoadMultipartitionTable", "LIKE_COUNTERS_REP", vt0);
                            break;
                        }
                    }
                    catch (VoltAbortException e) {
                        System.err.printf("FATAL Load single/multi table failed with an exception\n%s\n", e.getMessage());
                        throw new RuntimeException();
                    }
                    break;
                default:
                    throw new RuntimeException("Invalid query selector switch value: '" + tc + "'");
                }
            }
            catch (NoConnectionsException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
            catch (InterruptedException e) {
                e.printStackTrace();
                System.err.printf("Caught InterrruptedException: %s\ntoString: %s\n", e.getMessage(), e.toString());
                //throw new RuntimeException(e);
            }
            catch (IOException e) {
                e.printStackTrace();
                System.err.printf("Caught IOException: %s\ntoString: %s\n", e.getMessage(), e.toString());
                //throw new RuntimeException(e);
            }
            catch (Exception e) {
                e.printStackTrace();
                System.err.printf("Caught Exception: %s\ntoString: %s\n", e.getMessage(), e.toString());
                throw new RuntimeException(e);
            }
            Thread.yield();
        } // while

        // cancel periodic stats printing
        timer.cancel();
        try {
            // block until all outstanding txns return
            System.err.println("draining connection...");
            client.drain();
        }

        catch (Exception e) { }
        // print a report
        try { printResults(); }
        catch (Exception e) { }
        // close down the client connections
        try { client.close(); }
        catch (Exception e) { }
    }

    /**
     * Main routine creates a benchmark instance and kicks off the run method.
     *
     * @param args
     *            Command line arguments.
     * @throws Exception
     *             if anything goes wrong.
     * @see {@link VoterConfig}
     */
    public static void main(String[] args) throws Exception {
        // create a configuration from the arguments

        VoterConfig config = new VoterConfig();
        config.parse(AsyncBenchmark.class.getName(), args);
        AsyncBenchmark benchmark = new AsyncBenchmark(config);
        try { benchmark.runBenchmark(); }
        catch (Exception e) {
            e.printStackTrace();
            try { benchmark.timer.cancel(); }
            catch (Exception f) { }
            finally {
                throw new RuntimeException();
            }
        }
    }
}
