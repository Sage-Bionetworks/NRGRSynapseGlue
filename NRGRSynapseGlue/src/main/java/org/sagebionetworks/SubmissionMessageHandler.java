package org.sagebionetworks;

import org.apache.http.entity.ContentType;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.evaluation.model.Submission;
import org.sagebionetworks.repo.model.FileEntity;

/*
 * This message handler turns the message into an Evaluation Submission
 */
public class SubmissionMessageHandler implements MessageHandler {
	private SynapseClient synapseClient;
	private String messageParentId;
	private String evaluationId; 

	public SubmissionMessageHandler(SynapseClient synapseClient, String messageParentId, String evaluationId) {
		this.synapseClient=synapseClient;
		this.messageParentId=messageParentId;
		this.evaluationId=evaluationId;
	}

	@Override
	public void handleMessageContent(byte[] messageContent) {
		try {
			// upload to Synapse
			String fileHandleId = synapseClient.uploadToFileHandle(messageContent, ContentType.TEXT_PLAIN, messageParentId);
			FileEntity fileEntity = new FileEntity();
			fileEntity.setDataFileHandleId(fileHandleId);
			fileEntity.setParentId(messageParentId);
			fileEntity = synapseClient.createEntity(fileEntity);
			// submit to an evaluation queue
			Submission submission = new Submission();
			submission.setEntityId(fileEntity.getId());
			submission.setVersionNumber(fileEntity.getVersionNumber());
			submission.setEvaluationId(evaluationId);
			submission = synapseClient.createIndividualSubmission(submission, fileEntity.getEtag(),
					"https://www.synapse.org/#!Synapse:", null);
		} catch (SynapseException e) {
			throw new RuntimeException(e);
		}
	}

}
