package org.sagebionetworks;

import static org.sagebionetworks.Util.getProperty;

import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.evaluation.model.Submission;
import org.sagebionetworks.repo.model.FileEntity;

/*
 * This message handler turns the message into an Evaluation Submission
 */
public class SubmissionMessageHandler implements MessageHandler {
	SynapseClient synapseClient;

	public SubmissionMessageHandler(SynapseClient synapseClient) {
		this.synapseClient=synapseClient;
	}

	@Override
	public void handleMessageContent(byte[] messageContent) {
		String messageParentId = getProperty("MESSAGE_CONTAINER_ENTITY_ID");
		String evaluationId = getProperty("EVALUATION_ID"); 
		try {
			// upload to Synapse
			String fileHandleId = synapseClient.uploadToFileHandle(messageContent, null, messageParentId);
			FileEntity fileEntity = new FileEntity();
			fileEntity.setDataFileHandleId(fileHandleId);
			fileEntity.setParentId(messageParentId);
			fileEntity = synapseClient.createEntity(fileEntity);
			// submit to an evaluation queue
			Submission submission = new Submission();
			submission.setEntityId(fileEntity.getId());
			submission.setEvaluationId(evaluationId);
			submission = synapseClient.createIndividualSubmission(submission, fileEntity.getEtag());
		} catch (SynapseException e) {
			throw new RuntimeException(e);
		}
	}

}
