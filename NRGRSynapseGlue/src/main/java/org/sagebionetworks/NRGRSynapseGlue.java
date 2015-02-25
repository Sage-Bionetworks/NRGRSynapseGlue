package org.sagebionetworks;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.SynapseClientImpl;
import org.sagebionetworks.client.SynapseProfileProxy;
import org.sagebionetworks.client.exceptions.SynapseConflictingUpdateException;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.client.exceptions.SynapseResultNotReadyException;
import org.sagebionetworks.evaluation.model.BatchUploadResponse;
import org.sagebionetworks.evaluation.model.Submission;
import org.sagebionetworks.evaluation.model.SubmissionBundle;
import org.sagebionetworks.evaluation.model.SubmissionStatus;
import org.sagebionetworks.evaluation.model.SubmissionStatusBatch;
import org.sagebionetworks.evaluation.model.SubmissionStatusEnum;
import org.sagebionetworks.repo.model.MembershipRequest;
import org.sagebionetworks.repo.model.PaginatedResults;
import org.sagebionetworks.repo.model.UserProfile;
import org.sagebionetworks.repo.model.message.MessageToUser;
import org.sagebionetworks.repo.model.table.AppendableRowSet;
import org.sagebionetworks.repo.model.table.PartialRow;
import org.sagebionetworks.repo.model.table.PartialRowSet;
import org.sagebionetworks.repo.model.table.QueryResultBundle;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.SelectColumn;

public class NRGRSynapseGlue {
	private static Properties properties = null;

	private static final String MESSAGE_SUBJECT = "CommonMind Data Access Request";

	private static final String HMAC_SHA1_ALGORITHM = "HmacSHA1";

	private static int BATCH_SIZE = 50;

	private SynapseClient synapseClient;


	/*
	 * Parameters:
	 * signupTeamId:  Users wishing to gain access create a membership request in this Team
	 * accessRequirementId: The ID of the access requirement retricting access to the data of interest
	 * 
	 */

	public NRGRSynapseGlue() throws SynapseException {
		synapseClient = createSynapseClient();
		String adminUserName = getProperty("USERNAME");
		String adminPassword = getProperty("PASSWORD");
		synapseClient.login(adminUserName, adminPassword);
	}

	public static void main(String[] args) throws Exception {
		NRGRSynapseGlue sg = new NRGRSynapseGlue();
		sg.processNewApplicants();
		sg.approveApplicants();
	}

	/**
	 * Returns a BASE64-ENCODED HMAC-SHA1 key
	 */
	public static String newHMACSHA1Key() {
		try {
			// Generate a key for the HMAC-SHA1 keyed-hashing algorithm
			KeyGenerator keyGen = KeyGenerator.getInstance("HmacSHA1");
			SecretKey key = keyGen.generateKey();
			return new String(Base64.encodeBase64(key.getEncoded()));
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	private static int PAGE_SIZE = 50;

	/*
	 * Executes a query for which the max number of returned rows is known (i.e. we retrieve in a single page)
	 */
	private Pair<List<SelectColumn>, List<Row>> executeQuery(String sql, String tableId, long queryLimit) throws SynapseException, InterruptedException {
		String asyncJobToken = synapseClient.queryTableEntityBundleAsyncStart(sql, 0L, queryLimit, true, QUERY_PARTS_MASK, tableId);
		QueryResultBundle qrb=null;
		long backoff = 100L;
		for (int i=0; i<100; i++) {
			try {
				qrb = synapseClient.queryTableEntityBundleAsyncGet(asyncJobToken, tableId);
				break;
			} catch (SynapseResultNotReadyException e) {
				// keep waiting
				Thread.sleep(backoff);
				backoff *=2L;
			}
		}
		if (qrb==null) throw new RuntimeException("Query failed to return");
		List<Row> rows = qrb.getQueryResult().getQueryResults().getRows();
		if (qrb.getQueryCount()>rows.size()) throw new IllegalStateException(
				"Queried for "+queryLimit+" users but got back "+ rows.size()+" and total count: "+qrb.getQueryCount());
		return new Pair<List<SelectColumn>, List<Row>>(qrb.getSelectColumns(), rows);
	}

	private static final int QUERY_PARTS_MASK = 
			SynapseClient.QUERY_PARTMASK |
			SynapseClient.COUNT_PARTMASK |
			SynapseClient.COLUMNS_PARTMASK |
			SynapseClient.MAXROWS_PARTMASK;

	private static final int COLUMN_COUNT = 6;
	private static final String USER_ID = "UserId";
	private static final String USER_NAME = "User Name";
	private static final String FIRST_NAME = "First Name";
	private static final String LAST_NAME = "Last Name";
	private static final String TOKEN_SENT = "Token Email Sent";
	private static final String APPROVAL_COMPLETED = "Approval Completed";

	private static SelectColumn getColumnForName(List<SelectColumn> columns, String name)  {
		for (SelectColumn column : columns) {
			if (column.getName().equals(name)) return column;
		}
		throw new IllegalArgumentException("No column named "+name);
	}

	public void processNewApplicants() throws Exception {
		long total = Integer.MAX_VALUE;
		String tableId = getProperty("TABLE_ID");
		List<SelectColumn> appendOrderedColumns;
		List<Row> applicantsProcessed = new ArrayList<Row>();
		RowSet rowSet = new RowSet();
		rowSet.setTableId(tableId);
		rowSet.setRows(applicantsProcessed);
		for (int offset=0; offset<total; offset+=PAGE_SIZE) {
			PaginatedResults<MembershipRequest> pgs = 
					synapseClient.getOpenMembershipRequests(getProperty("APPLICATION_TEAM_ID"), 
							null, PAGE_SIZE, offset);
			total = pgs.getTotalNumberOfResults();
			StringBuilder sb = new StringBuilder("select * from "+tableId+" where UserId in (");
			boolean firstTime = true;
			for (MembershipRequest mr : pgs.getResults()) {
				if (firstTime) firstTime=false; else sb.append(",");
				sb.append(mr.getUserId());
			}
			sb.append(")");
			long queryLimit = (long)pgs.getResults().size();

			Pair<List<SelectColumn>, List<Row>> result = executeQuery(sb.toString(), tableId, queryLimit);
			List<SelectColumn> columns = result.getFirst();
			if (columns.size()!=COLUMN_COUNT) throw new IllegalStateException();
			List<Row> rows = result.getSecond();
			
			appendOrderedColumns = Arrays.asList(new SelectColumn[]{
					getColumnForName(columns, USER_ID),
					getColumnForName(columns, USER_NAME),
					getColumnForName(columns, FIRST_NAME),
					getColumnForName(columns, LAST_NAME),
					getColumnForName(columns, TOKEN_SENT),
					getColumnForName(columns, APPROVAL_COMPLETED)
					
			});
			rowSet.setHeaders(appendOrderedColumns);

			Integer tokenEmailSentColumnIndex = null;
			Integer userIdColumnIndex = null;
			for (int i = 0; i<columns.size(); i++) {
				SelectColumn selectColumn = columns.get(i);
				if (selectColumn.getName().equals(TOKEN_SENT)) {
					tokenEmailSentColumnIndex = i;
				} else if (selectColumn.getName().equals(USER_ID)) {
					userIdColumnIndex = i;
				}
			}
			if (tokenEmailSentColumnIndex==null) throw new IllegalStateException("Could not find 'Token Email Sent' column");
			if (userIdColumnIndex==null) throw new IllegalStateException("Could not find 'UserId' column");

			Map<String,Boolean> alreadyGeneratedToken = new HashMap<String,Boolean>();
			for (Row row : rows) {
				String userId = row.getValues().get(userIdColumnIndex);
				boolean tokenEmailSent = Boolean.valueOf(row.getValues().get(tokenEmailSentColumnIndex));
				alreadyGeneratedToken.put(userId, tokenEmailSent);
			}
			for (MembershipRequest mr : pgs.getResults()) {
				String userId = mr.getUserId();
				Boolean tokenEmailSent = alreadyGeneratedToken.get(userId);
				if (tokenEmailSent!=null && tokenEmailSent) continue;

				String token = createToken(userId);
				UserProfile userProfile = synapseClient.getUserProfile(userId);
				String messageBody = createMessage(
						userProfile.getFirstName(), 
						userProfile.getLastName(), 
						userProfile.getUserName(), 
						token);
				MessageToUser messageToUser = new MessageToUser();
				messageToUser.setSubject(MESSAGE_SUBJECT);
				messageToUser.setRecipients(Collections.singleton(userId));

				if (false) {
					synapseClient.sendStringMessage(messageToUser, messageBody);
				} else {
					System.out.println("Sending message to "+userProfile.getUserName());
				}
				Row applicantProcessed = new Row();
				applicantProcessed.setValues(Arrays.asList(new String[]{
					userId,
					userProfile.getUserName(),
					userProfile.getFirstName(),
					userProfile.getLastName(),
					""+Boolean.FALSE
				}));
				applicantsProcessed.add(applicantProcessed);
			}
		}
		// update table to show that the email was sent
		System.out.println("Appending "+applicantsProcessed.size()+" rows to Table.");
		synapseClient.appendRowsToTable(rowSet, 10000L, tableId);
	}

	private static final String PART_SEPARATOR = "|";

	private String createToken(String userId) {
		String unsignedToken = 
				createUnsignedToken(
						userId, 
						getProperty("ACCESS_REQUIREMENT_ID"), 
						""+System.currentTimeMillis());
		return unsignedToken+hmac(unsignedToken);
	}

	private static String createUnsignedToken(String userId, String accessRequirementId, String epoch) {
		StringBuilder sb = new StringBuilder();
		sb.append(userId+PART_SEPARATOR);
		sb.append(getProperty("ACCESS_REQUIREMENT_ID")+PART_SEPARATOR);
		sb.append(System.currentTimeMillis()+PART_SEPARATOR);
		return sb.toString();
	}

	private String hmac(String s) {
		return new String(generateHMACSHA1SignatureFromBase64EncodedKey(s, getProperty("HMAC_SECRET_KEY")));
	}

	/**
	 * Encodes data using a given BASE-64 Encoded HMAC-SHA1 secret key, base-64 encoding the result
	 */
	public static byte[] generateHMACSHA1SignatureFromBase64EncodedKey(String data, String base64EncodedSecretKey) {
		byte[] secretKey = Base64.decodeBase64(base64EncodedSecretKey.getBytes());
		try {
			Mac mac = Mac.getInstance(HMAC_SHA1_ALGORITHM);
			mac.init(new SecretKeySpec(secretKey, HMAC_SHA1_ALGORITHM));
			return Base64.encodeBase64(mac.doFinal(data.getBytes("UTF-8")));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static final String TOKEN_TERMINATOR = 
			"\n===========================================================================\n";

	private static String createMessage(String firstName, String lastName, String userName, String token) {
		StringBuilder sb = new StringBuilder();
		sb.append("Dear ");
		if ((firstName!=null && firstName.length()>0) || (lastName!=null && lastName.length()>0)) {
			sb.append(firstName+" "+lastName);
		} else {
			sb.append(userName);
		}
		sb.append(":\n");
		sb.append("As part of your application for the CommonMind data access, you must include this email message, including the following section:");
		sb.append(TOKEN_TERMINATOR);
		sb.append(token);
		sb.append(TOKEN_TERMINATOR);
		sb.append("\n");
		sb.append("Sincerely,");
		sb.append("\n");
		sb.append("Synapse Access and Compliance Team");
		return sb.toString();
	}

	private static String parseTokenFromFile(File file) throws IOException {
		FileInputStream fis = new FileInputStream(file);
		String result;
		try {
			result =  IOUtils.toString(fis);
		} finally {
			fis.close();
		}
		int leadingTerminator = result.indexOf(TOKEN_TERMINATOR);
		if (leadingTerminator<0) throw new IllegalArgumentException("Submission contains no token boundary ('==....==').");
		int tokenStart = leadingTerminator+TOKEN_TERMINATOR.length();
		int trailingTerminator = result.indexOf(TOKEN_TERMINATOR, tokenStart);
		if (trailingTerminator<0) throw new IllegalArgumentException("Two token boundaries ('==....==') expected but just one found.");
		String token = result.substring(tokenStart, trailingTerminator);
		String[] tokenParts = token.split(PART_SEPARATOR);
		if (tokenParts.length!=4) throw new IllegalArgumentException("Token should contain four parts, but "+tokenParts.length+" found.");
		String userId = tokenParts[0];
		String accessRequirementId = tokenParts[1];
		String epochString = tokenParts[2];
		long epoch;
		try {
			epoch = Long.parseLong(epochString);
		} catch (NumberFormatException e) {
			throw new UserLinkedException(userId, "Illegal time stamp in message.", e);
		}
		long tokenTimeout = Long.parseLong(getProperty("TOKEN_EXPIRATION_TIME_MILLIS"));
		if (epoch+tokenTimeout>System.currentTimeMillis())
			throw new UserLinkedException(userId, "Message timestamp has expired.");
		String hmac = tokenParts[3];
		String expectedAccessRequirementId = getProperty("ACCESS_REQUIREMENT_ID");
		if (!accessRequirementId.equals(expectedAccessRequirementId))
			throw new UserLinkedException(userId, "Expected access requirement ID "+expectedAccessRequirementId+
					" but found "+accessRequirementId);
		String recomputedHmac = createUnsignedToken(userId, accessRequirementId, epochString);
		if (!hmac.equals(recomputedHmac)) throw new UserLinkedException(userId, "Invalid digital signature.");
		return userId;
	}

	// 2) check for incoming email and if there is a valid attachment then approve them
	public void approveApplicants() throws Exception {
		long total = Integer.MAX_VALUE;
		String evaluationId = getProperty("EVALUATION_ID");
		List<SubmissionStatus> statusesToUpdate = new ArrayList<SubmissionStatus>();
		List<String> acceptedUserIds = new ArrayList<String>();
		List<IllegalArgumentException> rejected = new ArrayList<IllegalArgumentException>();
		for (int offset=0; offset<total; offset+=PAGE_SIZE) {
			// get the newly RECEIVED Submissions
			PaginatedResults<SubmissionBundle> submissionPGs = 
					synapseClient.getAllSubmissionBundlesByStatus(evaluationId, SubmissionStatusEnum.RECEIVED, offset, PAGE_SIZE);
			total = (int)submissionPGs.getTotalNumberOfResults();
			List<SubmissionBundle> page = submissionPGs.getResults();
			for (int i=0; i<page.size(); i++) {
				SubmissionBundle bundle = page.get(i);
				Submission sub = bundle.getSubmission();
				File temp = downloadSubmissionFile(sub);
				temp.deleteOnExit();

				String userId;

				SubmissionStatus status = bundle.getSubmissionStatus();
				SubmissionStatusEnum newStatus = null;
				try {
					userId = parseTokenFromFile(temp);
					newStatus = SubmissionStatusEnum.VALIDATED;
					acceptedUserIds.add(userId);
				} catch (UserLinkedException e) {
					userId = e.getUserId();
					newStatus = SubmissionStatusEnum.REJECTED;
					addRejectionReasonToStatus(status, e.getMessage());
					rejected.add(e);
				} catch (IllegalArgumentException e) {
					newStatus = SubmissionStatusEnum.REJECTED;
					addRejectionReasonToStatus(status, e.getMessage());
					rejected.add(e);
				}
				status.setStatus(newStatus);
				statusesToUpdate.add(status);
			}
		}
		createAccessApprovals(acceptedUserIds);
		acceptTeamMembershipRequests(acceptedUserIds);
		updateTable(acceptedUserIds, rejected);
		updateSubmissionStatusBatch(evaluationId, statusesToUpdate);
		sendApproveNotifications(acceptedUserIds);
		sendRejectionNotifications(rejected);
		System.out.println("Retrieved "+total+
				" submissions for approval. Accepted "+acceptedUserIds.size()+
				" and rejected "+rejected.size()+".");

	}
	
	private void createAccessApprovals(List<String> userIds) {
		// TODO
	}
	
	private void acceptTeamMembershipRequests(List<String> userIds) {
		// TODO
	}
	
	private void updateTable(List<String> userIds, List<IllegalArgumentException> rejected) {
		// if we can get a USER_ID then add rejection reason to Table
		// TODO
	}
	
	private static void addRejectionReasonToStatus(SubmissionStatus status, String reason) {
		// TODO
	}
	
	private void sendApproveNotifications(List<String> userIds) {
		// TODO
	}
	
	private void sendRejectionNotifications(List<IllegalArgumentException> rejected) {
		for (IllegalArgumentException e : rejected) {
			if (e instanceof UserLinkedException) {
				UserLinkedException ule = (UserLinkedException)e;
				String userId = ule.getUserId();
				String reason = ule.getMessage();
				// TODO send rejection reason to user
			}
		}
	}


	private static final int BATCH_UPLOAD_RETRY_COUNT = 3;

	private void updateSubmissionStatusBatch(String evaluationId, List<SubmissionStatus> statusesToUpdate) throws SynapseException {
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
					System.out.println("Successfully updated "+batch.size()+" submissions.");
				}
				break; // success!
			} catch (SynapseConflictingUpdateException e) {
				// we collided with someone else access the Evaluation.  Will retry!
				System.out.println("WILL RETRY: "+e.getMessage());
			}
		}
	}

	private File downloadSubmissionFile(Submission submission) throws SynapseException, IOException {
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


	public static void initProperties() {
		if (properties!=null) return;
		properties = new Properties();
		InputStream is = null;
		try {
			is = NRGRSynapseGlue.class.getClassLoader().getResourceAsStream("global.properties");
			properties.load(is);
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			if (is!=null) try {
				is.close();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public static String getProperty(String key) {
		initProperties();
		String commandlineOption = System.getProperty(key);
		if (commandlineOption!=null) return commandlineOption;
		String embeddedProperty = properties.getProperty(key);
		if (embeddedProperty!=null) return embeddedProperty;
		throw new RuntimeException("Cannot find value for "+key);
	}	

	private static SynapseClient createSynapseClient() {
		SynapseClientImpl scIntern = new SynapseClientImpl();
		scIntern.setAuthEndpoint("https://repo-prod.prod.sagebase.org/auth/v1");
		scIntern.setRepositoryEndpoint("https://repo-prod.prod.sagebase.org/repo/v1");
		scIntern.setFileEndpoint("https://repo-prod.prod.sagebase.org/file/v1");
		return SynapseProfileProxy.createProfileProxy(scIntern);
	}
}
