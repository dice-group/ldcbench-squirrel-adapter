package org.dice_research.squirrel.squirrel_benchmark.system;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.jena.rdf.model.NodeIterator;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.vocabulary.RDF;
import org.hobbit.core.components.AbstractSystemAdapter;
import org.hobbit.vocab.HOBBIT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class SystemAdapter extends AbstractSystemAdapter {
    private static final Logger logger = LoggerFactory.getLogger(SystemAdapter.class);
    private Map<String, String> parameters = new HashMap<>();
    private final static String MONGODB_IMAGE = "mongo:4.0.0";
    
    private final static String SPARQL_IMAGE = "stain/jena-fuseki";
    private final static String[] SPARQL_ENV = {"ADMIN_PASSWORD=pw123","JVM_ARGS=-Xmx4g"};

    private final static String WORKER_IMAGE = "squirrel.worker:latest";
    
    private final static String FRONTIER_IMAGE = "squirrel.frontier:latest";
    
    @Override
    public void init() throws Exception {
//    	System.setProperty("HOBBIT_RABBIT_HOST", "rabbit");
    	
    	
    	
        super.init();
        logger.debug("Init()");
        
        logger.debug("Initializing MongoDB server...");
        String mongoHost = createContainer(MONGODB_IMAGE, null);
        logger.debug("MongoDB server started");
        
//        logger.debug("Initializing SparqlHost...");
//        createContainer(SPARQL_IMAGE, SPARQL_ENV);
//        logger.debug("SparqlHost started");

        
        logger.debug("Initializing Squirrel Frontier...");
        
        String[] FRONTIER_ENV = {
        		"HOBBIT_RABBIT_HOST=rabbit",
        		"SEED_FILE=/var/squirrel/seeds.txt",
        		"MDB_HOST_NAME="+mongoHost,
        		"MDB_PORT=27017"
        };

        String frontierName = createContainer(FRONTIER_IMAGE, FRONTIER_ENV);
        logger.debug("Squirrel frontier started");
        
        logger.debug("Initializing Squirrel Worker...");
        
        String[] WORKER_ENV = {
        		"HOBBIT_RABBIT_HOST=rabbit",
        		"OUTPUT_FOLDER=/var/squirrel/data",
        		"HTML_SCRAPER_YAML_PATH=/var/squirrel/yaml",
        		"CONTEXT_CONFIG_FILE=/var/squirrel/spring-config/context.xml",
        		"SPARQL_HOST_NAME=sparqlhost",
        		"SPARQL_HOST_PORT=3030",
        		"DEDUPLICATION_ACTIVE=false",
        		"MDB_HOST_NAME=" + mongoHost,
        		"MDB_PORT=27017",
        		"JVM_ARGS=-Xmx8g"
        		};
        
        String worker1 = createContainer(WORKER_IMAGE, WORKER_ENV);
        logger.debug("Squirrel Worker Started...");

    }

    @Override
    public void receiveGeneratedData(byte[] data) {
        // handle the incoming data as described in the benchmark description
        String dataStr = new String(data);
        logger.trace("receiveGeneratedData("+new String(data)+"): "+dataStr);



    }

    @Override
    public void receiveGeneratedTask(String taskId, byte[] data) {
        // handle the incoming task and create a result
        String result = "result_"+taskId;
        logger.trace("receiveGeneratedTask({})->{}",taskId, new String(data));
        
        logger.trace("PASSEI AQUI");
        // Send the result to the evaluation storage
        try {
            logger.trace("sendResultToEvalStorage({})->{}", taskId, result);
            sendResultToEvalStorage(taskId, result.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    @Override
    public void close() throws IOException {
        // Free the resources you requested here
        logger.debug("close()");
        stopContainer(MONGODB_IMAGE);
        stopContainer(FRONTIER_IMAGE);
        stopContainer(WORKER_IMAGE);

        // Always close the super class after yours!
        super.close();
    }

}

