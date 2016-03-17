package org.sagebionetworks;

import static org.sagebionetworks.Util.getProperty;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseConflictingUpdateException;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.evaluation.model.BatchUploadResponse;
import org.sagebionetworks.evaluation.model.Submission;
import org.sagebionetworks.evaluation.model.SubmissionBundle;
import org.sagebionetworks.evaluation.model.SubmissionStatus;
import org.sagebionetworks.evaluation.model.SubmissionStatusBatch;
import org.sagebionetworks.evaluation.model.SubmissionStatusEnum;
import org.sagebionetworks.reflection.model.PaginatedResults;
import org.sagebionetworks.repo.model.annotation.Annotations;
import org.sagebionetworks.repo.model.annotation.StringAnnotation;

public class EvaluationUtil {
	private SynapseClient synapseClient;

	public EvaluationUtil(SynapseClient synapseClient) {
		this.synapseClient=synapseClient;
	}
	
	private static int PAGE_SIZE = 50;

	private static int BATCH_SIZE = 50;

	private static final String REJECTION_REASON_ANNOTATION_LABEL = "rejectionReason";

	private static final int BATCH_UPLOAD_RETRY_COUNT = 3;

	public List<SubmissionBundle> getReceivedSubmissions(String evaluationId) throws SynapseException {
		long total = Integer.MAX_VALUE;
		List<SubmissionBundle> result = new ArrayList<SubmissionBundle>();
		for (int offset=0; offset<total; offset+=PAGE_SIZE) {
			// get the newly RECEIVED Submissions
			PaginatedResults<SubmissionBundle> submissionPGs = 
					synapseClient.getAllSubmissionBundlesByStatus(evaluationId, SubmissionStatusEnum.RECEIVED, offset, PAGE_SIZE);
			total = (int)submissionPGs.getTotalNumberOfResults();
			result.addAll(submissionPGs.getResults());
		}
		return result;
	}
	
	public static void addRejectionReasonToStatus(SubmissionStatus status, String reason) {
		Annotations a = status.getAnnotations();
		if (a==null) {
			a = new Annotations();
			status.setAnnotations(a);
		}
		List<StringAnnotation> stringAnnos = a.getStringAnnos();
		if (stringAnnos==null) {
			stringAnnos = new ArrayList<StringAnnotation>();
			a.setStringAnnos(stringAnnos);
		}
		for (StringAnnotation sa : stringAnnos) {
			if (sa.getKey().equals(REJECTION_REASON_ANNOTATION_LABEL)) {
				sa.setValue(reason);
				return;
			}
		}
		StringAnnotation sa = new StringAnnotation();
		sa.setIsPrivate(false);
		sa.setKey(REJECTION_REASON_ANNOTATION_LABEL);
		sa.setValue(reason);
		stringAnnos.add(sa);
	}

	public void updateSubmissionStatusBatch(List<SubmissionStatus> statusesToUpdate, String evaluationId) throws SynapseException {
		// now we have a batch of statuses to update
		for (int retry=0; retry<BATCH_UPLOAD_RETRY_COUNT; retry++) {
			try {
				String batchToken = null;
				for (int offset=0; offset<statusesToUpdate.size(); offset+=BATCH_SIZE) {
					SubmissionStatusBatch updateBatch = new SubmissionStatusBatch();
					List<SubmissionStatus> batch = new ArrayList<SubmissionStatus>();
					for (int i=0; i<BATCH_SIZE && offset+i<statusesToUpdate.size(); i++) {
						batch.add(statusesToUpdate.get(offset+i));
					}
					updateBatch.setStatuses(batch);
					boolean isFirstBatch = (offset==0);
					updateBatch.setIsFirstBatch(isFirstBatch);
					boolean isLastBatch = (offset+BATCH_SIZE)>=statusesToUpdate.size();
					updateBatch.setIsLastBatch(isLastBatch);
					updateBatch.setBatchToken(batchToken);
					BatchUploadResponse response = 
							synapseClient.updateSubmissionStatusBatch(evaluationId, updateBatch);
					batchToken = response.getNextUploadToken();
				}
				break; // success!
			} catch (SynapseConflictingUpdateException e) {
				// we collided with someone else access the Evaluation.  Will retry!
				System.out.println("WILL RETRY: "+e.getMessage());
			}
		}
	}

	public File downloadSubmissionFile(Submission submission) throws SynapseException, IOException {
		String fileHandleId = getFileHandleIdFromEntityBundle(submission.getEntityBundleJSON());
		File temp = File.createTempFile("temp", null);
		synapseClient.downloadFromSubmission(submission.getId(), fileHandleId, temp);
		return temp;
	}

	private static String getFileHandleIdFromEntityBundle(String s) {
		try {
			JSONObject bundle = new JSONObject(s);
			JSONArray fileHandles = (JSONArray)bundle.get("fileHandles");
			for (int i=0; i<fileHandles.length(); i++) {
				JSONObject fileHandle = fileHandles.getJSONObject(i);
				if (!fileHandle.get("concreteType").equals("org.sagebionetworks.repo.model.file.PreviewFileHandle")) {
					return (String)fileHandle.get("id");
				}
			}
			throw new IllegalArgumentException("File has no file handle ID");
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

}
