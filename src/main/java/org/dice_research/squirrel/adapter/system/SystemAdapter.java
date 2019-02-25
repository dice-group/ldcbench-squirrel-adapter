package org.dice_research.squirrel.adapter.system;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;

import org.dice_research.squirrel.benchmark.Constants;
import org.hobbit.core.components.AbstractSystemAdapter;
import org.hobbit.core.components.ContainerStateObserver;
import org.hobbit.core.rabbit.DataSender;
import org.hobbit.core.rabbit.DataSenderImpl;
import org.hobbit.core.rabbit.RabbitMQUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SystemAdapter extends AbstractSystemAdapter implements ContainerStateObserver {
    private static final Logger LOGGER = LoggerFactory.getLogger(SystemAdapter.class);

    private final static String FRONTIER_IMAGE = "squirrel.frontier:latest";
    private final static String MONGODB_IMAGE = "mongo:4.0.0";
    private final static String WORKER_IMAGE = "squirrel.worker:latest";

    protected String mongoInstance;
    protected String frontierInstance;
    protected int numberOfWorkers = 1;
    protected Set<String> workerInstances = new HashSet<>();
    protected Semaphore frontierTerminated = new Semaphore(0);
    protected boolean terminating = false;
    private DataSender senderFrontier;



    @Override
    public void init() throws Exception {
        super.init();
        LOGGER.debug("Initializing MongoDB server...");
        mongoInstance = createContainer(MONGODB_IMAGE, null, this);
        LOGGER.debug("MongoDB server started");

        LOGGER.debug("Initializing Squirrel Frontier...");
        String[] FRONTIER_ENV = { "HOBBIT_RABBIT_HOST=rabbit", "SEED_FILE=/var/squirrel/seeds.txt",
                "MDB_HOST_NAME=" + mongoInstance, "MDB_PORT=27017" };
        frontierInstance = createContainer(FRONTIER_IMAGE, FRONTIER_ENV, this);
        LOGGER.debug("Squirrel frontier started");
        senderFrontier = DataSenderImpl.builder().queue(outgoingDataQueuefactory, Constants.FRONTIER_QUEUE_NAME)
                .build();
        LOGGER.info("Squirrel crawler initialized and waiting for additional data...");
    }

    @Override
    public void receiveGeneratedData(byte[] data) {
        // handle the incoming data as described in the benchmark description
        String sparqlEndpoint = RabbitMQUtils.readString(data);
        LOGGER.debug("received SPARQL endpoint \"{}\".", sparqlEndpoint);
        String[] WORKER_ENV = { "HOBBIT_RABBIT_HOST=rabbit", "OUTPUT_FOLDER=/var/squirrel/data",
                "HTML_SCRAPER_YAML_PATH=/var/squirrel/yaml",
                "CONTEXT_CONFIG_FILE=/var/squirrel/spring-config/context.xml", "SPARQL_HOST_NAME=" + sparqlEndpoint,
                "SPARQL_HOST_PORT=8890", "DEDUPLICATION_ACTIVE=false", "MDB_HOST_NAME=" + mongoInstance,
                "MDB_PORT=27017" };
        String worker;
        for (int i = 0; i < numberOfWorkers; ++i) {
            worker = createContainer(WORKER_IMAGE, WORKER_ENV, this);
            if (worker == null) {
                LOGGER.error("Error while trying to start worker #{}. Exiting.", i);
                System.exit(1);
            } else {
                LOGGER.info("Worker #{} started.", i);
                workerInstances.add(worker);
            }
        }
    }

    @Override
    public void receiveGeneratedTask(String taskId, byte[] data) {
        // handle the incoming task and create a result
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("receiveGeneratedTask({})->{}", taskId, new String(data));
        } else {
            LOGGER.debug("Received seed URI(s).");
        }

        // TODO Send message to frontier
        String seed = RabbitMQUtils.readString(data);
        
        try {
			senderFrontier.sendData(seed.getBytes());
		} catch (IOException e) {
			LOGGER.warn(e.getMessage());
		}

        LOGGER.debug("Seed URI(s) forwarded.");
    }

    public void containerStopped(String containerName, int exitCode) {
        // Check whether it is one of your containers and react accordingly
        if ((frontierInstance != null) && (frontierInstance.equals(containerName))) {
            Exception e = null;
            if (exitCode != 0) {
                // The frontier had an error. Its time to panic
                LOGGER.error("Frontier terminated with exit code {}.", exitCode);
                e = new IllegalStateException("Frontier terminated with exit code " + exitCode + ".");
            }
            frontierInstance = null;
            terminate(e);
        } else if ((mongoInstance != null) && (mongoInstance.equals(containerName)) && !terminating) {
            // If we are not terminating, this behavior is not expected!
            LOGGER.error("Mongo DB terminated unexpectedly with exit code {}.", exitCode);
            terminate(new IllegalStateException("Mongo DB terminated unexpectedly with exit code " + exitCode + "."));
        } else if ((containerName != null) && (workerInstances.contains(containerName)) && !terminating) {
            // If we are not terminating, this behavior is not expected!
            LOGGER.error("A worker terminated unexpectedly with exit code {}.", exitCode);
            terminate(new IllegalStateException("A worker terminated unexpectedly with exit code " + exitCode + "."));
        } else {
            LOGGER.warn(
                    "Got an unexpected message about a terminated container that is not known ({}). It will be ignored.",
                    containerName);
        }
    }

    @Override
    protected synchronized void terminate(Exception cause) {
        terminating = true;
        super.terminate(cause);
    }

    @Override
    public void close() throws IOException {
        // Free the resources you requested here
        LOGGER.debug("close()");
        senderFrontier.close();
        for (String worker : workerInstances) {
            stopContainer(worker);
        }
        if (frontierInstance != null) {
            stopContainer(frontierInstance);
        }
        if (mongoInstance != null) {
            stopContainer(mongoInstance);
        }
        // Always close the super class after yours!
        super.close();
    }

}
