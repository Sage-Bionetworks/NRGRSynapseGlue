package org.sagebionetworks;

import static org.junit.Assert.assertEquals;
import static org.sagebionetworks.Util.getProperty;

import java.nio.charset.Charset;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.entity.ContentType;
import org.junit.After;
import org.junit.Test;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.evaluation.model.Evaluation;
import org.sagebionetworks.evaluation.model.SubmissionBundle;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.auth.LoginRequest;

public class SubmissionMessageHandlerTest {
	private SynapseClient synapseClient;
	
	private static final String FILE_CONTENT = "some file content";
	private static final ContentType CONTENT_TYPE = ContentType.create("text/plain", Charset.defaultCharset());
    
    private Project project;
    private Evaluation evaluation;

    public void setup() throws Exception {
	    	synapseClient = SynapseClientFactory.createSynapseClient();
	    	String adminUserName = getProperty("USERNAME");
	    	String adminPassword = getProperty("PASSWORD");
	    	LoginRequest loginRequest = new LoginRequest();
	    	loginRequest.setUsername(adminUserName);
	    	loginRequest.setPassword(adminPassword);
	    	synapseClient.login(loginRequest);
	    	project = new Project();
	    	project.setName(UUID.randomUUID().toString());
	    	project = synapseClient.createEntity(project);
	    	evaluation = new Evaluation();
	    	evaluation.setContentSource(project.getId());
	    	evaluation.setName(UUID.randomUUID().toString());
	    	evaluation = synapseClient.createEvaluation(evaluation);

    }
	
	@After
	public void teardown() throws Exception {

    	if (project!=null) {
    		synapseClient.deleteEntityById(project.getId());
    		project=null;
    		evaluation=null;
    	}
		
	}
	
	@Test
	public void testSubmissionMesssageHandler() throws Exception {
		if (StringUtils.isEmpty(getProperty("USERNAME", true))) return; // no properties
		setup();
		EvaluationUtil evaluationUtil = new EvaluationUtil(synapseClient);
		List<SubmissionBundle> submissionBundles = evaluationUtil.getReceivedSubmissions(evaluation.getId());
		assertEquals(0, submissionBundles.size());

		SubmissionMessageHandler smh = new SubmissionMessageHandler(synapseClient, project.getId(), evaluation.getId());
		smh.handleMessageContent(FILE_CONTENT.getBytes());
		
		submissionBundles = evaluationUtil.getReceivedSubmissions(evaluation.getId());
		assertEquals(1, submissionBundles.size());
	}
	

}
