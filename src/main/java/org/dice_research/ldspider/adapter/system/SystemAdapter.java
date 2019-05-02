package org.dice_research.ldspider.adapter.system;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;

import org.dice_research.squirrel.data.uri.serialize.Serializer;
import org.dice_research.squirrel.data.uri.serialize.java.GzipJavaUriSerializer;
import org.hobbit.core.components.AbstractSystemAdapter;
import org.hobbit.core.components.ContainerStateObserver;
import org.hobbit.core.rabbit.RabbitMQUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SystemAdapter extends AbstractSystemAdapter implements ContainerStateObserver {
	
    private static final Logger LOGGER = LoggerFactory.getLogger(SystemAdapter.class);

	
	private final static String LDSPIDER_IMAGE = "ldspider:latest";
    private long numberOfThreads = 2;
    protected boolean terminating = false;
    protected String[] LDSPIDER_ENV;
    
    protected String ldSpiderInstance;

	
	@Override
	public void init() throws Exception {
		super.init();
		
	}
	
	@Override
	public void receiveGeneratedData(byte[] data) {
		// handle the incoming data as described in the benchmark description
        ByteBuffer buffer = ByteBuffer.wrap(data);
        String sparqlUrl = RabbitMQUtils.readString(buffer);
        String sparqlUser = RabbitMQUtils.readString(buffer);
        String sparqlPwd = RabbitMQUtils.readString(buffer);
        	
        LDSPIDER_ENV = new String[]{ "b=1000",
                "oe=" + sparqlUrl,
                "user_sparql=" + sparqlUser,
                "passwd_sparql=" + sparqlPwd,
                "t="+numberOfThreads+"",
                "s=seed"};
		
        
		
	}
	
	@Override
	public void receiveGeneratedTask(String taskId, byte[] data) {
		 // handle the incoming task and create a result
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("receiveGeneratedTask({})->{}", taskId, new String(data));
        } else {
            LOGGER.debug("Received seed URI(s).");
        }
        //TODO implement a solution for inserting seeds in ldspider
        
        
	 	String seed = RabbitMQUtils.readString(data);
		
	 	LDSPIDER_ENV[5].replaceAll("seed", seed);
        
		ldSpiderInstance = createContainer(LDSPIDER_IMAGE, LDSPIDER_ENV, this);
	}
	
	@Override
	protected synchronized void terminate(Exception cause) {
		LOGGER.debug("Terminating");
        terminating = true;
        super.terminate(cause);
	}
	
	
	@Override
	public void containerStopped(String containerName, int exitCode) {
        // Check whether it is one of your containers and react accordingly
        if ((ldSpiderInstance != null) && (ldSpiderInstance.equals(containerName)) && !terminating) {
            Exception e = null;
            if (exitCode != 0) {
                // The ldspider had an error. Its time to panic
                LOGGER.error("ldspider terminated with exit code {}.", exitCode);
                e = new IllegalStateException("ldspider terminated with exit code " + exitCode + ".");
            }
            ldSpiderInstance = null;
            terminate(e);
        } 
		
	}
	
	@Override
	public void close() throws IOException {
		// TODO Auto-generated method stub
		super.close();
	}

}
