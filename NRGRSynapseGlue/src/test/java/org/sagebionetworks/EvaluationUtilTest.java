package org.sagebionetworks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.sagebionetworks.Util.getProperty;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang.StringUtils;
import org.apache.http.entity.ContentType;
import org.junit.After;
import org.junit.Test;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.evaluation.model.Evaluation;
import org.sagebionetworks.evaluation.model.EvaluationStatus;
import org.sagebionetworks.evaluation.model.Submission;
import org.sagebionetworks.evaluation.model.SubmissionBundle;
import org.sagebionetworks.evaluation.model.SubmissionStatus;
import org.sagebionetworks.evaluation.model.SubmissionStatusEnum;
import org.sagebionetworks.repo.model.FileEntity;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.annotation.StringAnnotation;
import org.sagebionetworks.repo.model.auth.LoginRequest;
import org.sagebionetworks.repo.model.file.S3FileHandle;

import com.amazonaws.util.IOUtils;

public class EvaluationUtilTest {
	
	private static final String FILE_CONTENT = "some file content";
	private static final ContentType CONTENT_TYPE = ContentType.create("text/plain", Charset.defaultCharset());
    
	private SynapseClient synapseClient;
    private Project project;
    private Evaluation evaluation;
    private FileEntity file;
    
    // don't tag this with 'before' because it's not used by all tests
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
	    	evaluation.setStatus(EvaluationStatus.OPEN);
	    	evaluation = synapseClient.createEvaluation(evaluation);
	
	
	    	byte[] messageBytes = FILE_CONTENT.getBytes(Charset.defaultCharset());
	    	InputStream is = new ByteArrayInputStream(messageBytes);
	
	    	S3FileHandle fileHandle;
	
	    	try {
	    		fileHandle = synapseClient.multipartUpload(is, (long)messageBytes.length, 
	    				"message.json", CONTENT_TYPE.toString(), 
	    				null, false, false);
	    	} finally {
	    		is.close();
	    	}
	
	    	String fileHandleId = fileHandle.getId(); 
	
	
	    	file = new FileEntity();
	
	    	file.setDataFileHandleId(fileHandleId);
	    	file.setParentId(project.getId());
	    	file = synapseClient.createEntity(file);

    }
	
	@After
	public void teardown() throws Exception {

    	if (project!=null) {
    		synapseClient.deleteEntityById(project.getId());
    		project=null;
    		evaluation=null;
    		file=null;
    	}
		
	}
	
	private Submission createSubmission(String evaluationId) throws SynapseException {
    	Submission submission = new Submission();
    	submission.setEntityId(file.getId());
    	submission.setVersionNumber(file.getVersionNumber());
    	submission.setEvaluationId(evaluation.getId());
    	submission = synapseClient.createIndividualSubmission(submission, file.getEtag(), null, null);
		return submission;
	}

	@Test
	public void testGetReceivedSubmissions() throws Exception {
		if (StringUtils.isEmpty(getProperty("USERNAME", true))) return; // no properties
		setup();
		createSubmission(evaluation.getId());
		EvaluationUtil evaluationUtil = new EvaluationUtil(synapseClient);
		List<SubmissionBundle> submissionBundles = evaluationUtil.getReceivedSubmissions(evaluation.getId());
		assertEquals(1, submissionBundles.size());
		
		SubmissionStatus status = submissionBundles.get(0).getSubmissionStatus();
		status.setStatus(SubmissionStatusEnum.CLOSED);
		synapseClient.updateSubmissionStatus(status);
		submissionBundles = evaluationUtil.getReceivedSubmissions(evaluation.getId());
		assertEquals(0, submissionBundles.size());
		
	}
	
	@Test
	public void testAddRejectionReasonToStatus() {
		SubmissionStatus status = new SubmissionStatus();
		String reason = "just because";
		EvaluationUtil.addRejectionReasonToStatus(status, reason);
		
		StringAnnotation sa = status.getAnnotations().getStringAnnos().get(0);
		assertEquals("rejectionReason", sa.getKey());
		assertEquals(reason, sa.getValue());
	}
	
	@Test
	public void testUpdateSubmissionStatusBatch() throws Exception {
		if (StringUtils.isEmpty(getProperty("USERNAME", true))) return; // no properties
		setup();
		createSubmission(evaluation.getId());
		EvaluationUtil evaluationUtil = new EvaluationUtil(synapseClient);
		List<SubmissionBundle> submissionBundles = evaluationUtil.getReceivedSubmissions(evaluation.getId());
		assertEquals(1, submissionBundles.size());
		SubmissionStatus status = submissionBundles.get(0).getSubmissionStatus();
		status.setStatus(SubmissionStatusEnum.CLOSED);
		evaluationUtil.updateSubmissionStatusBatch(Collections.singletonList(status), evaluation.getId());
		submissionBundles = evaluationUtil.getReceivedSubmissions(evaluation.getId());
		assertEquals(0, submissionBundles.size());
	}
	
	@Test
	public void testDownloadSubmissionFile() throws Exception {
		if (StringUtils.isEmpty(getProperty("USERNAME", true))) return; // no properties
		setup();
		Submission submission = createSubmission(evaluation.getId());
		EvaluationUtil evaluationUtil = new EvaluationUtil(synapseClient);
		File file = evaluationUtil.downloadSubmissionFile(submission);
		assertTrue(file.exists());
		FileInputStream is = new FileInputStream(file);
		try {
			String fileContent = IOUtils.toString(is);
			assertEquals(FILE_CONTENT, fileContent);
		} finally {
			is.close();
		}
	}

}
