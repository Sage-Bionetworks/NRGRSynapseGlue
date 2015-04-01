package org.sagebionetworks;

import static org.sagebionetworks.TokenUtil.createToken;
import static org.sagebionetworks.TokenUtil.parseTokensFromInput;
import static org.sagebionetworks.Util.getProperty;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.mail.internet.MimeMessage;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.http.entity.ContentType;
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
import org.sagebionetworks.repo.model.ACTAccessApproval;
import org.sagebionetworks.repo.model.ACTApprovalStatus;
import org.sagebionetworks.repo.model.MembershipRequest;
import org.sagebionetworks.repo.model.PaginatedResults;
import org.sagebionetworks.repo.model.TeamMembershipStatus;
import org.sagebionetworks.repo.model.UserProfile;
import org.sagebionetworks.repo.model.annotation.Annotations;
import org.sagebionetworks.repo.model.annotation.StringAnnotation;
import org.sagebionetworks.repo.model.message.MessageToUser;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.QueryResultBundle;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.SelectColumn;

/*
 * This application works in three steps:
 * (1) Look for new Synapse users who have sent a membership request to a certain team,
 * and send them a access token via email;
 * (2) Check a certain email account for incoming messages.  Turn those messages into
 * submissions in a certain Evaluation queue and move them to a 'processed' folder;
 * (3) Go through the aforementioned Synapse Evaluation queue.  Check the S/MIME signture
 * on the email, extract the access tokens, validate the tokens, create the approvals,
 * and notify the Synapse users that their access is approved.
 */
public class NRGRSynapseGlue {
	private static final String MESSAGE_SUBJECT = "CommonMind Data Access Request";

	private static int BATCH_SIZE = 50;

	private static final int QUERY_PARTS_MASK = 
			SynapseClient.QUERY_PARTMASK |
			SynapseClient.COUNT_PARTMASK |
			SynapseClient.COLUMNS_PARTMASK |
			SynapseClient.MAXROWS_PARTMASK;

	private static final int COLUMN_COUNT = 8;
	private static final String USER_ID = "UserId";
	private static final String USER_NAME = "User Name";
	private static final String FIRST_NAME = "First Name";
	private static final String LAST_NAME = "Last Name";
	private static final String TOKEN_SENT_DATE = "Date Email Sent";
	private static final String APPROVED_OR_REJECTED_DATE = "Date Approved/Rejected";
	private static final String APPROVED = "Approved";
	private static final String REASON_REJECTED = "Reason Rejected";

	private static final long TABLE_UPDATE_TIMEOOUT = 10000L;

	private static final String REJECTION_REASON_LABEL = "rejectionReason";

	private static final int BATCH_UPLOAD_RETRY_COUNT = 3;

	private static final String SYNAPSE_ACCESS_AND_COMPLIANCE_TEAM_ID = "464532";

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
		sg.checkForMail();
		sg.approveApplicants();
	}

	/*
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

	private Pair<List<SelectColumn>, List<Row>> selectRowsForUsers(Collection<Long> userIds) throws SynapseException, InterruptedException {
		String tableId = getProperty("TABLE_ID");
		if (userIds.isEmpty()) {
			List<ColumnModel> models = synapseClient.getColumnModelsForTableEntity(tableId);
			List<SelectColumn> selectColumns = new ArrayList<SelectColumn>();
			for (ColumnModel columnModel : models) {
				SelectColumn selectColumn = new SelectColumn();
				selectColumn.setId(columnModel.getId());
				selectColumn.setName(columnModel.getName());
				selectColumn.setColumnType(columnModel.getColumnType());
				selectColumns.add(selectColumn);
			}
			return new Pair<List<SelectColumn>, List<Row>>(selectColumns, Collections.EMPTY_LIST);
		}
		StringBuilder sb = new StringBuilder("SELECT ");
		sb.append("\""+USER_ID+"\", ");
		sb.append("\""+USER_NAME+"\", ");
		sb.append("\""+FIRST_NAME+"\", ");
		sb.append("\""+LAST_NAME+"\", ");
		sb.append("\""+TOKEN_SENT_DATE+"\", ");
		sb.append("\""+APPROVED_OR_REJECTED_DATE+"\", ");
		sb.append("\""+APPROVED+"\", ");
		sb.append("\""+REASON_REJECTED+"\"");
		sb.append(" FROM "+tableId+" WHERE "+USER_ID+" IN (");
		boolean firstTime = true;
		for (Long userId : userIds) {
			if (firstTime) firstTime=false; else sb.append(",");
			sb.append(userId);
		}
		sb.append(")");
		String sql = sb.toString();
		return executeQuery(sql, tableId, userIds.size());
	}

	private static SelectColumn getColumnForName(List<SelectColumn> columns, String name)  {
		for (SelectColumn column : columns) {
			if (column.getName().equals(name)) return column;
		}
		throw new IllegalArgumentException("No column named "+name);
	}

	private static int getColumnIndexForName(List<SelectColumn> columns, String name)  {
		for (int i=0; i<columns.size(); i++) {
			if (columns.get(i).getName().equals(name)) return i;
		}
		List<String> names = new ArrayList<String>();
		for (SelectColumn column : columns) names.add(column.getName());
		throw new IllegalArgumentException("No column named "+name+". Available names: "+names);
	}

	public void processNewApplicants() throws Exception {
		String tableId = getProperty("TABLE_ID");
		long total = Integer.MAX_VALUE;
		List<Row> applicantsProcessed = new ArrayList<Row>();
		RowSet rowSet = new RowSet();
		rowSet.setTableId(tableId);
		rowSet.setRows(applicantsProcessed);
		for (int offset=0; offset<total; offset+=PAGE_SIZE) {
			PaginatedResults<MembershipRequest> pgs = 
					synapseClient.getOpenMembershipRequests(getProperty("APPLICATION_TEAM_ID"), 
							null, PAGE_SIZE, offset);
			total = pgs.getTotalNumberOfResults();
			List<Long> userIds = new ArrayList<Long>();
			for (MembershipRequest mr : pgs.getResults()) {
				userIds.add(Long.parseLong(mr.getUserId()));
			}
			if (userIds.isEmpty()) continue;

			Pair<List<SelectColumn>, List<Row>> result = selectRowsForUsers(userIds);
			List<SelectColumn> columns = result.getFirst();
			List<Row> rows = result.getSecond();

			if (rows.size()>0 && columns.size()!=COLUMN_COUNT) throw new IllegalStateException(""+columns.size()+"!="+COLUMN_COUNT);

			List<SelectColumn> appendOrderedColumns = Arrays.asList(new SelectColumn[]{
					getColumnForName(columns, USER_ID),
					getColumnForName(columns, USER_NAME),
					getColumnForName(columns, FIRST_NAME),
					getColumnForName(columns, LAST_NAME),
					getColumnForName(columns, TOKEN_SENT_DATE),
					getColumnForName(columns, APPROVED_OR_REJECTED_DATE),
					getColumnForName(columns, APPROVED),
					getColumnForName(columns, REASON_REJECTED)

			});
			rowSet.setHeaders(appendOrderedColumns);

			Integer dateApprovedRejectedColumnIndex = null;
			Integer userIdColumnIndex = null;
			for (int i = 0; i<columns.size(); i++) {
				SelectColumn selectColumn = columns.get(i);
				if (selectColumn.getName().equals(TOKEN_SENT_DATE)) {
					dateApprovedRejectedColumnIndex = i;
				} else if (selectColumn.getName().equals(USER_ID)) {
					userIdColumnIndex = i;
				}
			}
			if (dateApprovedRejectedColumnIndex==null) throw new IllegalStateException("Could not find 'Date Email Sent' column");
			if (userIdColumnIndex==null) throw new IllegalStateException("Could not find 'UserId' column");

			Map<String,Row> userToRowMap = new HashMap<String,Row>();
			for (Row row : rows) {
				String userId = row.getValues().get(userIdColumnIndex);
				if (userToRowMap.get(userId)!=null) throw new IllegalStateException("There are multiple rows in "+tableId+" for user "+userId);
				userToRowMap.put(userId, row);
			}
			for (MembershipRequest mr : pgs.getResults()) {
				String userId = mr.getUserId();
				Row row = userToRowMap.get(userId);
				String tokenEmailSentDateString = null;
				if (row!=null) {
					tokenEmailSentDateString = row.getValues().get(userIdColumnIndex);
				}
				if (tokenEmailSentDateString!=null) continue;

				String token = createToken(userId);
				UserProfile userProfile = synapseClient.getUserProfile(userId);
				String messageBody = createTokenMessage(userProfile, token);
				MessageToUser messageToUser = new MessageToUser();
				messageToUser.setSubject(MESSAGE_SUBJECT);
				messageToUser.setRecipients(Collections.singleton(userId));

				sendMessage(messageToUser, messageBody);
				Row applicantProcessed = null;
				if (row==null) {
					applicantProcessed = new Row();
				} else {
					applicantProcessed = row;
				}
				applicantProcessed.setValues(Arrays.asList(new String[]{
						userId,
						userProfile.getUserName(),
						userProfile.getFirstName(),
						userProfile.getLastName(),
						""+System.currentTimeMillis(),
						null,
						null,
						null
				}));
				applicantsProcessed.add(applicantProcessed);
			}
		}
		// update table to show that the email was sent
		System.out.println("Appending "+applicantsProcessed.size()+" rows to Table.");
		if (applicantsProcessed.size()>0) {
			synapseClient.appendRowsToTable(rowSet, TABLE_UPDATE_TIMEOOUT, tableId);
		}
	}

	private void sendMessage(MessageToUser messageToUser, String messageBody) throws SynapseException {
		synapseClient.sendStringMessage(messageToUser, messageBody);
	}

	private static String salutation(UserProfile up) {
		StringBuilder sb = new StringBuilder();
		sb.append("Dear ");
		if ((up.getFirstName()!=null && up.getFirstName().length()>0) || 
				(up.getLastName()!=null && up.getLastName().length()>0)) {
			sb.append(up.getFirstName()+" "+up.getLastName());
		} else {
			sb.append(up.getUserName());
		}
		sb.append(":\n");
		return sb.toString();
	}

	private static final String MESSAGE_SIGNATURE_LINE = "\nSincerely,\n\nSynapse Access and Compliance Team";

	private static String createTokenMessage(UserProfile userProfile, String token) {
		StringBuilder sb = new StringBuilder();
		sb.append(salutation(userProfile));
		sb.append("\nAs part of your application to the NIMH Repository and Genomics Resources (NRGR) ");
		sb.append("for access to the CommonMind data, you must include the following Synapse data access ");
		sb.append("token with the application documents: https://www.nimhgenetics.org/available_data/commonmind.\n");
		sb.append("\n");
		sb.append(token);
		sb.append("\nPlease copy the three-line token (above) along with the tokens generated by all individuals from your group working ");
		sb.append("on the study into a document labeled \"CMC_SynapseToken_PIname_Date\" ");
		sb.append("and submit the document as part of the NRGR application. If you are not the ");
		sb.append("PI on the NRGR application, please provide your token to the study PI so they may submit it.\n");
		sb.append("\n");
		sb.append("Note:  This token is valid for 122 days.  If the application is not approved within that time, a new token must be submitted.\n");
		sb.append(MESSAGE_SIGNATURE_LINE);
		return sb.toString();
	}

	// convert incoming mail messages to Submission in the queue
	public void checkForMail() throws Exception {
		IMAPClient mailClient = new IMAPClient();
		mailClient.processNewMessages(new SubmissionMessageHandler(synapseClient));
	}

	// ACT members may submit tokens to the Evaluation queue.  In that case
	// we do not have to validate S/MIME signature
	private boolean canBypassMessageValidation(String submissionCreatorId, String myOwnId) throws SynapseException {
		// don't do this override if the creator is the cron job service account
		if (submissionCreatorId.equals(myOwnId)) return false;
		TeamMembershipStatus tms = 
				synapseClient.getTeamMembershipStatus(
						SYNAPSE_ACCESS_AND_COMPLIANCE_TEAM_ID, submissionCreatorId);
		return tms.getIsMember();
	}

	// 3) check for incoming email and if there is a valid attachment then approve them
	public void approveApplicants() throws Exception {
		String tableId = getProperty("TABLE_ID");
		String myOwnSnapseId = null;
		long total = Integer.MAX_VALUE;
		String evaluationId = getProperty("EVALUATION_ID");
		List<TokenContent> acceptedTokens = new ArrayList<TokenContent>();
		List<SubmissionStatus> statusesToUpdate = new ArrayList<SubmissionStatus>();
		Map<Long,String> rejected = new HashMap<Long,String>(); // key is userId, value is 'reason' message
		int rejectedCount = 0;
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

				SubmissionStatus status = bundle.getSubmissionStatus();
				SubmissionStatusEnum newStatus = null;
				InputStream fileIs = null;
				try {
					if (myOwnSnapseId==null) myOwnSnapseId = synapseClient.getMyProfile().getOwnerId();
					if (!canBypassMessageValidation(sub.getUserId(), myOwnSnapseId)) {
						MimeMessage message = MessageUtil.readMessageFromFile(temp);
						if (!OriginValidator.isOriginatingIPInSubnet(message, getProperty("ORIGINATING_IP_SUBNET"))) {
							throw new Exception("Message lacks X-Originating-IP header or is outside of the allowed subnet.");
						}						
					}

					fileIs = new FileInputStream(temp);
					Set<TokenAnalysisResult> tokenAnalysisResults = parseTokensFromInput(IOUtils.toByteArray(fileIs));
					int validTokensInMessage = 0;
					for (TokenAnalysisResult tar : tokenAnalysisResults) {
						if(tar.isValid()) {
							if (tar.getUserId()==null) {
								// should never happen.  Will be caught and handled below
								throw new IllegalStateException("Missing userId");
							}
							acceptedTokens.add(tar.getTokenContent());
							validTokensInMessage++;
						} else {
							if (tar.getUserId()!=null) {
								rejected.put(tar.getUserId(), tar.getReason());
							}
						}
					}
					if (validTokensInMessage>0) {
						newStatus = SubmissionStatusEnum.CLOSED;
					} else {
						throw new Exception("No valid token found in file.");
					}
				} catch (Exception e) {
					newStatus = SubmissionStatusEnum.REJECTED;
					rejectedCount++;
					addRejectionReasonToStatus(status, e.getMessage());
				} finally {
					if (fileIs!=null) fileIs.close();
				}
				status.setStatus(newStatus);
				statusesToUpdate.add(status);
			}
		}
		// Now we've gone through all the new submissions and know what users we want to accept and reject
		Map<Long,TokenContent> acceptedUsers = new HashMap<Long,TokenContent>();
		for (TokenContent tc : acceptedTokens) {
			acceptedUsers.put(tc.getUserId(), tc);
		}

		// filter the accepted list, removing users who have already been approved
		List<String> acceptedAndNotYetApprovedUserIds = new ArrayList<String>();
		List<Row> acceptedAndNotYetApprovedRows = new ArrayList<Row>();
		List<TokenContent> acceptedAndNotYetApprovedTC = new ArrayList<TokenContent>();
		if (!acceptedUsers.isEmpty()) {
			Pair<List<SelectColumn>, List<Row>> result = selectRowsForUsers(acceptedUsers.keySet());	
			Integer userIdIndex = null;
			Integer approvedOrRejectedDateIndex = null;
			Integer approvedIndex = null;
			Integer reasonRejectedIndex = null;
			if (!result.getSecond().isEmpty()) {
				userIdIndex = getColumnIndexForName(result.getFirst(), USER_ID);
				approvedOrRejectedDateIndex = getColumnIndexForName(result.getFirst(), APPROVED_OR_REJECTED_DATE);
				approvedIndex = getColumnIndexForName(result.getFirst(), APPROVED);
				reasonRejectedIndex = getColumnIndexForName(result.getFirst(), REASON_REJECTED);
				for (Row row : result.getSecond()) {
					String userId = row.getValues().get(userIdIndex);
					String approvedString = row.getValues().get(approvedIndex);
					if (approvedString==null || Boolean.valueOf(approvedString)==false) {
						acceptedAndNotYetApprovedUserIds.add(userId);
						acceptedAndNotYetApprovedTC.add(acceptedUsers.get(Long.parseLong(userId)));
						acceptedAndNotYetApprovedRows.add(row);
					}
				}
			}
			// if an ID is in acceptedUserIds but not in the Table, then something weird has happened
			if (acceptedUsers.size()!=result.getSecond().size()) {
				System.out.println("Warning:  Found "+acceptedUsers.size()+
						" valid token(s), but the number of Table rows matching the userIds is "+
						result.getSecond().size());
			}

			createAccessApprovals(acceptedAndNotYetApprovedTC);
			acceptTeamMembershipRequests(acceptedAndNotYetApprovedUserIds);
			long now = System.currentTimeMillis();
			for (Row row : acceptedAndNotYetApprovedRows) {
				row.getValues().set(approvedOrRejectedDateIndex, ""+now);
				row.getValues().set(approvedIndex, Boolean.TRUE.toString());
				row.getValues().set(reasonRejectedIndex, null);
			}

			// now update the table to show that approval has been granted
			{
				RowSet rowsToUpdate = new RowSet();
				rowsToUpdate.setTableId(tableId);
				rowsToUpdate.setHeaders(result.getFirst());
				rowsToUpdate.setRows(new ArrayList<Row>(acceptedAndNotYetApprovedRows));
				synapseClient.appendRowsToTable(rowsToUpdate, TABLE_UPDATE_TIMEOOUT, tableId);
			}
		}
		// filter list down to just the users that were not previously approved
		// (That is, if you're already approved then a bad submission will not revoke
		// your access.)
		Map<Long,String> confirmedRejected = updateTableForRejected(rejected, tableId);

		updateSubmissionStatusBatch(evaluationId, statusesToUpdate);
		sendApproveNotifications(acceptedAndNotYetApprovedUserIds);
		sendRejectionNotifications(confirmedRejected);

		System.out.println("Retrieved "+total+
				" submissions for approval and rejected "+rejectedCount+". Accepted "+acceptedUsers.size()+
				" users and rejected "+confirmedRejected.size()+".");

	}

	private void createAccessApprovals(Collection<TokenContent> usersToApprove) throws SynapseException {
		for (TokenContent tc: usersToApprove) {
			long userId = tc.getUserId();
			for (Long accessRequirementId : tc.getAccessRequirementIds()) {
				ACTAccessApproval actAccessApproval = new ACTAccessApproval();
				actAccessApproval.setAccessorId(""+userId);
				actAccessApproval.setApprovalStatus(ACTApprovalStatus.APPROVED);
				actAccessApproval.setRequirementId(accessRequirementId);
				synapseClient.createAccessApproval(actAccessApproval);
			}
		}
	}

	private void acceptTeamMembershipRequests(Collection<String> userIds) throws SynapseException {
		long total = Integer.MAX_VALUE;
		String teamId = getProperty("APPLICATION_TEAM_ID");
		for (int offset=0; offset<total; offset+=PAGE_SIZE) {
			PaginatedResults<MembershipRequest> pgs = 
					synapseClient.getOpenMembershipRequests(teamId, 
							null, PAGE_SIZE, offset);
			total = pgs.getTotalNumberOfResults();
			for (MembershipRequest mr : pgs.getResults()) {
				if(userIds.contains(mr.getUserId())) {
					synapseClient.addTeamMember(teamId, mr.getUserId());
				}
			}
		}
	}

	private static void addRejectionReasonToStatus(SubmissionStatus status, String reason) {
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
			if (sa.getKey().equals(REJECTION_REASON_LABEL)) {
				sa.setValue(reason);
				return;
			}
		}
		StringAnnotation sa = new StringAnnotation();
		sa.setIsPrivate(false);
		sa.setKey(REJECTION_REASON_LABEL);
		sa.setValue(reason);
		stringAnnos.add(sa);
	}

	private Map<Long,String> updateTableForRejected(Map<Long,String> rejected, String tableId) throws SynapseException, InterruptedException {
		Map<Long,String> confirmedRejected = new HashMap<Long,String>();
		if (rejected.isEmpty()) return confirmedRejected;
		Pair<List<SelectColumn>, List<Row>> result = selectRowsForUsers(rejected.keySet());	
		if (result.getSecond().isEmpty()) return confirmedRejected;
		int userIdIndex = getColumnIndexForName(result.getFirst(), USER_ID);
		int approvedOrRejectedDateIndex = getColumnIndexForName(result.getFirst(), APPROVED_OR_REJECTED_DATE);
		int approvedIndex = getColumnIndexForName(result.getFirst(), APPROVED);
		int reasonIndex =  getColumnIndexForName(result.getFirst(), REASON_REJECTED);
		long now = System.currentTimeMillis();
		for (Row row : result.getSecond()) {
			List<String> values = row.getValues();
			String approvedString = values.get(approvedIndex);
			if (approvedString!=null && Boolean.valueOf(approvedString)) {
				continue; // if you're already approved, then a bad submission won't cause you to be rejected
			}
			Long userId = Long.parseLong(values.get(userIdIndex));
			if (rejected.keySet().contains(userId)) {
				values.set(approvedOrRejectedDateIndex, ""+now);
				String reason = rejected.get(userId);
				values.set(reasonIndex, reason);
				confirmedRejected.put(userId, reason);
			}
		}
		RowSet rowsToUpdate = new RowSet();
		rowsToUpdate.setTableId(tableId);
		rowsToUpdate.setHeaders(result.getFirst());
		rowsToUpdate.setRows(new ArrayList<Row>(result.getSecond()));
		synapseClient.appendRowsToTable(rowsToUpdate, TABLE_UPDATE_TIMEOOUT, tableId);
		return confirmedRejected;
	}

	public void sendApproveNotifications(Collection<String> userIds) {
		for (String userId : userIds) {
			try {
				UserProfile userProfile = synapseClient.getUserProfile(userId);
				MessageToUser message = new MessageToUser();
				message.setSubject("Common Mind Data Access Approval");
				message.setRecipients(Collections.singleton(userId));
				StringBuilder messageBody = new StringBuilder();
				messageBody.append(salutation(userProfile));
				messageBody.append("<div><br></div><div>The NRGR has approved you for access to the Common Mind Consortium data release 1. This data may be accessed through the CommonMind Consortium Knowledge Portal:<a target=\"_blank\" class=\"\" href=\"http://dx.doi.org/10.7303/syn2759792\" style=\"color:rgb(58,91,220);text-decoration:none;font-family:&#39;Helvetica Neue&#39;,Helvetica,Arial,sans-serif;font-size:14px;line-height:20px\">doi:10.7303/syn2759792</a>.</div><div><br></div><div>Please note that the first time you download a Controlled Access data file you will be asked to acknowledge the CommonMind Consortium in publications derived from use of the data. Also note that there is an embargo on the publication of whole genome analysis based on the bipolar samples until August 1, 2015. Agreement to these terms must be performed through the website.<br></div><div><br></div><div>For a review of the CommonMind Data Terms of Use, please see here:<a href=\"https://www.synapse.org/#!Synapse:syn2759792/wiki/197282\">https://www.synapse.org/#!Synapse:syn2759792/wiki/197282</a><br></div><div><br></div><div><div>Please note, this email is sent from an unmonitored account. Send any questions to <a href=\"mailto:act@sagebase.org\">act@sagebase.org</a>.</div></div><div><br></div><div><div>Sincerely,</div><div>The Synapse Access and Compliance Team</div></div>");
				Charset charset = Charset.forName("UTF-8");
				ContentType contentType = ContentType.create("text/html", charset);
				String fileHandleId = synapseClient.uploadToFileHandle(messageBody.toString().getBytes(charset), contentType, getProperty("MESSAGE_CONTAINER_ENTITY_ID"));
				message.setFileHandleId(fileHandleId);
				synapseClient.sendMessage(message);
			} catch (SynapseException e) {
				// if the message fails, just log it and go on to the next one
				e.printStackTrace();
			}
		}
	}

	private void sendRejectionNotifications(Map<Long,String> rejected) {
		for (Long userId : rejected.keySet()) {
			String reason = rejected.get(userId);
			try {
				UserProfile userProfile = synapseClient.getUserProfile(""+userId);
				MessageToUser message = new MessageToUser();
				message.setSubject("Common Mind Data Access Declined");
				message.setRecipients(Collections.singleton(""+userId));
				StringBuilder messageBody = new StringBuilder();
				messageBody.append(salutation(userProfile));
				messageBody.append("\nYour request for access to the Common Mind data has been declined.\n");
				if (reason!=null) {
					messageBody.append("\n\tReason: ");
					messageBody.append(reason);
					messageBody.append("\n");
				}
				messageBody.append(MESSAGE_SIGNATURE_LINE);
				sendMessage(message, messageBody.toString());
			} catch (SynapseException e) {
				// if the message fails, just log it and go on to the next one
				e.printStackTrace();
			}
		}
	}


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


	private static SynapseClient createSynapseClient() {
		SynapseClientImpl scIntern = new SynapseClientImpl();
		scIntern.setAuthEndpoint("https://repo-prod.prod.sagebase.org/auth/v1");
		scIntern.setRepositoryEndpoint("https://repo-prod.prod.sagebase.org/repo/v1");
		scIntern.setFileEndpoint("https://repo-prod.prod.sagebase.org/file/v1");
		return SynapseProfileProxy.createProfileProxy(scIntern);
	}
}
