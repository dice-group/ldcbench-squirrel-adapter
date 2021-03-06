package org.dice_research.squirrel.adapter.system;

import static org.hobbit.core.Constants.CONTAINER_TYPE_SYSTEM;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;

import org.apache.jena.rdf.model.Literal;
import org.dice_research.squirrel.Constants;
import org.dice_research.squirrel.data.uri.CrawleableUri;
import org.dice_research.squirrel.data.uri.serialize.Serializer;
import org.dice_research.squirrel.data.uri.serialize.java.GzipJavaUriSerializer;
import org.dice_research.squirrel.rabbit.msgs.UriSet;
import org.hobbit.core.Commands;
import org.hobbit.core.components.AbstractSystemAdapter;
import org.hobbit.core.components.ContainerStateObserver;
import org.hobbit.core.rabbit.DataSender;
import org.hobbit.core.rabbit.DataSenderImpl;
import org.hobbit.core.rabbit.RabbitMQUtils;
import org.hobbit.utils.rdf.RdfHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SystemAdapter extends AbstractSystemAdapter implements ContainerStateObserver {
    private static final Logger LOGGER = LoggerFactory.getLogger(SystemAdapter.class);

    private final static String MONGODB_IMAGE = "mongo:4.0.0";

    public final static String FRONTIER_IMAGE_URI = "http://project-hobbit.eu/ldcbench-system/squirrelFrontierImage";
    public final static String WORKER_IMAGE_URI = "http://project-hobbit.eu/ldcbench-system/squirrelWorkerImage";
    public final static String NUMBER_WORKERS_URI = "http://project-hobbit.eu/ldcbench-system/numberOfWorkers";

    protected final String MDB_CONNECTION_TIME_OUT = "5000";
    protected final String MDB_SOCKET_TIME_OUT = "10000";
    protected final String MDB_SERVER_TIME_OUT = "10000";

    protected String frontierImage;
    protected String workerImage;
    protected String mongoInstance;
    protected String frontierInstance;
    protected int numberOfWorkers;
    protected Set<String> workerInstances = new HashSet<>();
    protected Semaphore frontierTerminated = new Semaphore(0);
    protected boolean terminating = false;
    private DataSender senderFrontier;
    private Serializer serializer;

    @Override
    public void init() throws Exception {
        super.init();
        LOGGER.debug("Initializing MongoDB server...");
        mongoInstance = createContainer(MONGODB_IMAGE, CONTAINER_TYPE_SYSTEM, null);
        if (mongoInstance == null) {
            LOGGER.error("Error while trying to start MongoDB server.");
            System.exit(1);
        }
        LOGGER.debug("MongoDB server started: {}", mongoInstance);

        LOGGER.debug("Initializing Squirrel Frontier...");
        String[] FRONTIER_ENV = { "HOBBIT_RABBIT_HOST=rabbit", "SEED_FILE=/var/squirrel/seeds.txt",
                "FRONTIER_CONTEXT_CONFIG_FILE=/var/squirrel/spring-config/frontier-context.xml",
                "MDB_HOST_NAME=" + mongoInstance, "MDB_PORT=27017",
                "MDB_CONNECTION_TIME_OUT=" + MDB_CONNECTION_TIME_OUT, "MDB_SOCKET_TIME_OUT=" + MDB_SOCKET_TIME_OUT,
                "MDB_SERVER_TIME_OUT=" + MDB_SERVER_TIME_OUT };

//        Property parameter;
//        NodeIterator objIterator;
//        Map<String, String> parameters = new HashMap<>();

//        ResIterator iterator = systemParamModel.listResourcesWithProperty(RDF.type, HOBBIT.Parameter);
//        Property defaultValProperty = systemParamModel.getProperty("http://w3id.org/hobbit/vocab#defaultValue");

        Literal workerCountLiteral = RdfHelper.getLiteral(systemParamModel, null,
                systemParamModel.getProperty(NUMBER_WORKERS_URI));
        if (workerCountLiteral == null) {
            throw new IllegalStateException(
                    "Couldn't find necessary parameter value for \"" + NUMBER_WORKERS_URI + "\". Aborting.");
        }
        numberOfWorkers = workerCountLiteral.getInt();

        frontierImage = RdfHelper.getStringValue(systemParamModel, null, systemParamModel.getProperty(FRONTIER_IMAGE_URI));
        workerImage = RdfHelper.getStringValue(systemParamModel, null, systemParamModel.getProperty(WORKER_IMAGE_URI));

//        while (iterator.hasNext()) {
//            parameter = systemParamModel.getProperty(iterator.next().getURI());
//            objIterator = systemParamModel.listObjectsOfProperty(parameter, defaultValProperty);
//            while (objIterator.hasNext()) {
//                String value = objIterator.next().asLiteral().getString();
//                parameters.put(parameter.getLocalName(), value);
//            }
//        }

//        LOGGER.info("PARAMETERS: " + parameters.toString());

        frontierInstance = createContainer(frontierImage, FRONTIER_ENV, this);
        if (frontierInstance == null) {
            LOGGER.error("Error while trying to start Squirrel frontier.");
            System.exit(1);
        }
        LOGGER.debug("Squirrel frontier started: {}", frontierInstance);
        senderFrontier = DataSenderImpl.builder().queue(outgoingDataQueuefactory, Constants.FRONTIER_QUEUE_NAME)
                .build();
        LOGGER.info("Squirrel crawler initialized and waiting for additional data...");
        serializer = new GzipJavaUriSerializer();
    }

    @Override
    public void receiveGeneratedData(byte[] data) {
        // handle the incoming data as described in the benchmark description
        ByteBuffer buffer = ByteBuffer.wrap(data);
        String sparqlUrl = RabbitMQUtils.readString(buffer);
        String sparqlUser = RabbitMQUtils.readString(buffer);
        String sparqlPwd = RabbitMQUtils.readString(buffer);
        String[] seedURIs = RabbitMQUtils.readString(buffer).split("\n");

        LOGGER.info("Sparql Endpoint: " + sparqlUrl);
        LOGGER.info("Seed URIs: {}.", Arrays.toString(seedURIs));

        String[] WORKER_ENV = {
            "HOBBIT_RABBIT_HOST=rabbit",
            "OUTPUT_FOLDER=/var/squirrel/data",
            "HTML_SCRAPER_YAML_PATH=/var/squirrel/yaml",
            "CONTEXT_CONFIG_FILE=/var/squirrel/spring-config/worker-context-sparql.xml",
            "SPARQL_URL=" + sparqlUrl,
            "SPARQL_HOST_USER=" + sparqlUser,
            "SPARQL_HOST_PASSWD=" + sparqlPwd,
            "DEDUPLICATION_ACTIVE=false",
            "STORE_METADATA=false",
        };
        String worker;
        for (int i = 0; i < numberOfWorkers; ++i) {
            worker = createContainer(workerImage, WORKER_ENV, this);
            if (worker == null) {
                LOGGER.error("Error while trying to start worker #{}. Exiting.", i);
                System.exit(1);
            } else {
                LOGGER.info("Worker #{} started.", i);
                workerInstances.add(worker);
            }
        }

        // TODO Send message to frontier
        try {
            ArrayList<CrawleableUri> crawleables = new ArrayList<>();
            for (String s : seedURIs) {
                crawleables.add(new CrawleableUri(new URI(s)));
            }
            senderFrontier.sendData(serializer.serialize(new UriSet(crawleables)));
        } catch (Exception e) {
            LOGGER.warn(e.getMessage());
        }

        LOGGER.debug("Seed URI(s) forwarded.");
    }

    @Override
    public void receiveGeneratedTask(String taskId, byte[] data) {
        throw new IllegalStateException("Should not receive any tasks.");
    }

    @Override
    public void receiveCommand(byte command, byte[] data) {
        if (command == Commands.DOCKER_CONTAINER_TERMINATED) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            String containerName = RabbitMQUtils.readString(buffer);
            int exitCode = buffer.get();
            containerStopped(containerName, exitCode);
        }
        super.receiveCommand(command, data);
    }

    public void containerStopped(String containerName, int exitCode) {
        // Check whether it is one of your containers and react accordingly
        if ((frontierInstance != null) && (frontierInstance.equals(containerName)) && !terminating) {
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
            mongoInstance = null;
            terminate(new IllegalStateException("Mongo DB terminated unexpectedly with exit code " + exitCode + "."));
        } else if ((containerName != null) && (workerInstances.contains(containerName)) && !terminating) {
            // If we are not terminating, this behavior is not expected!
            LOGGER.error("A worker terminated unexpectedly with exit code {}.", exitCode);
            workerInstances.remove(containerName);
            terminate(new IllegalStateException("A worker terminated unexpectedly with exit code " + exitCode + "."));
        } else {
            LOGGER.warn(
                    "Got an unexpected message about a terminated container that is not known ({}). It will be ignored.",
                    containerName);
        }
    }

    @Override
    protected synchronized void terminate(Exception cause) {
        LOGGER.debug("Terminating");
        terminating = true;
        super.terminate(cause);
    }

    @Override
    public void close() throws IOException {
        // Free the resources you requested here
        if (senderFrontier != null) {
            senderFrontier.close();
        }
        LOGGER.debug("Stopping workers...");
        for (String worker : workerInstances) {
            LOGGER.debug("Stopping {}", worker);
            stopContainer(worker);
        }
        if (frontierInstance != null) {
            LOGGER.debug("Stopping frontier {}", frontierInstance);
            stopContainer(frontierInstance);
        } else {
            LOGGER.debug("There is no frontier to stop.");
        }
        if (mongoInstance != null) {
            LOGGER.debug("Stopping MongoDB {}", mongoInstance);
            stopContainer(mongoInstance);
        } else {
            LOGGER.debug("There is no MongoDB to stop.");
        }
        // Always close the super class after yours!
        super.close();
    }
}
