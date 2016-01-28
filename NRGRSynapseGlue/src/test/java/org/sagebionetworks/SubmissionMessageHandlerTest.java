package org.sagebionetworks;

import static org.junit.Assert.assertEquals;
import static org.sagebionetworks.Util.getProperty;

import java.nio.charset.Charset;
import java.util.List;
import java.util.UUID;

import org.apache.http.entity.ContentType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.evaluation.model.Evaluation;
import org.sagebionetworks.evaluation.model.EvaluationStatus;
import org.sagebionetworks.evaluation.model.SubmissionBundle;
import org.sagebionetworks.repo.model.Project;

public class SubmissionMessageHandlerTest {
	private SynapseClient synapseClient;
	
	private static final String FILE_CONTENT = "some file content";
	private static final ContentType CONTENT_TYPE = ContentType.create("text/plain", Charset.defaultCharset());
    
    private Project project;
    private Evaluation evaluation;

    @Before
	public void setup() throws Exception {
		synapseClient = Util.createSynapseClient();
		String adminUserName = getProperty("USERNAME");
		String adminPassword = getProperty("PASSWORD");
		synapseClient.login(adminUserName, adminPassword);
    	project = new Project();
    	project.setName(UUID.randomUUID().toString());
    	project = synapseClient.createEntity(project);
     	evaluation = new Evaluation();
    	evaluation.setContentSource(project.getId());
    	evaluation.setName(UUID.randomUUID().toString());
    	evaluation.setStatus(EvaluationStatus.OPEN);
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
		EvaluationUtil evaluationUtil = new EvaluationUtil(synapseClient);
		List<SubmissionBundle> submissionBundles = evaluationUtil.getReceivedSubmissions(evaluation.getId());
		assertEquals(0, submissionBundles.size());

		SubmissionMessageHandler smh = new SubmissionMessageHandler(synapseClient, project.getId(), evaluation.getId());
		smh.handleMessageContent(FILE_CONTENT.getBytes());
		
		submissionBundles = evaluationUtil.getReceivedSubmissions(evaluation.getId());
		assertEquals(1, submissionBundles.size());
	}
	

}
