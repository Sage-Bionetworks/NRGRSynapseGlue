package org.sagebionetworks;

import static org.sagebionetworks.TableUtil.APPROVED;
import static org.sagebionetworks.TableUtil.APPROVED_OR_REJECTED_DATE;
import static org.sagebionetworks.TableUtil.FIRST_NAME;
import static org.sagebionetworks.TableUtil.LAST_NAME;
import static org.sagebionetworks.TableUtil.MEMBERSHIP_REQUEST_EXPIRATION_DATE;
import static org.sagebionetworks.TableUtil.REASON_REJECTED;
import static org.sagebionetworks.TableUtil.TABLE_UPDATE_TIMEOOUT;
import static org.sagebionetworks.TableUtil.TOKEN_SENT_DATE;
import static org.sagebionetworks.TableUtil.USER_ID;
import static org.sagebionetworks.TableUtil.USER_NAME;
import static org.sagebionetworks.TableUtil.getColumnIndexForName;
import static org.sagebionetworks.Util.getProperty;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.mail.Address;
import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

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
import org.sagebionetworks.evaluation.model.BatchUploadResponse;
import org.sagebionetworks.evaluation.model.Submission;
import org.sagebionetworks.evaluation.model.SubmissionBundle;
import org.sagebionetworks.evaluation.model.SubmissionStatus;
import org.sagebionetworks.evaluation.model.SubmissionStatusBatch;
import org.sagebionetworks.evaluation.model.SubmissionStatusEnum;
import org.sagebionetworks.reflection.model.PaginatedResults;
import org.sagebionetworks.repo.model.ACTAccessApproval;
import org.sagebionetworks.repo.model.ACTApprovalStatus;
import org.sagebionetworks.repo.model.MembershipRequest;
import org.sagebionetworks.repo.model.TeamMembershipStatus;
import org.sagebionetworks.repo.model.UserProfile;
import org.sagebionetworks.repo.model.annotation.Annotations;
import org.sagebionetworks.repo.model.annotation.StringAnnotation;
import org.sagebionetworks.repo.model.message.MessageToUser;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.SelectColumn;

/*
 * This application is meant to help Synapse unlock data access based on approval in
 * an external system.  
 * 
 * It works in three steps:
 * (1) Look for new Synapse users who have sent a membership request to a certain team,
 * and send them an access token via email;  [The user then includes the token in their
 * application to the external system.  Upon approval, the administrator in said external
 * system returns the token by email to a preset endpoint.]
 * (2) Check a certain email account for incoming messages.  Turn those messages into
 * submissions in a certain Evaluation queue and move them to a 'processed' folder;
 * (3) Go through the aforementioned Synapse Evaluation queue.  Check the X-Originating-IP address
 * on the email, extract the access tokens, validate the tokens, create the approvals,
 * and notify the Synapse users that their access is approved.
 */
public class NRGRSynapseGlue {

	private static int BATCH_SIZE = 50;

	private static final String REJECTION_REASON_ANNOTATION_LABEL = "rejectionReason";

	private static final int BATCH_UPLOAD_RETRY_COUNT = 3;

	private static final String SYNAPSE_ACCESS_AND_COMPLIANCE_TEAM_ID = "464532";

	private SynapseClient synapseClient;
	private MessageUtil messageUtil;
	private TableUtil tableUtil;

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
		messageUtil = new MessageUtil(synapseClient);
		tableUtil = new TableUtil(synapseClient);
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

	public void processNewApplicants() throws Exception {
		List<Row> applicantsProcessed = new ArrayList<Row>();
		long now = System.currentTimeMillis();
		for (DatasetSettings datasetSettings : Util.getDatasetSettings().values()) {
			// process each data set
			long total = Integer.MAX_VALUE;
			for (int offset=0; offset<total; offset+=PAGE_SIZE) {
				PaginatedResults<MembershipRequest> pgs = 
						synapseClient.getOpenMembershipRequests(datasetSettings.getApplicationTeamId(), 
								null, PAGE_SIZE, offset);
				total = pgs.getTotalNumberOfResults();
				// get just the new requests, omitting the ones for which tokens have already been sent
				List<MembershipRequest> newMembershipRequests = 
						tableUtil.getNewMembershipRequests(pgs.getResults());
				if (newMembershipRequests.isEmpty()) continue;
	
				for (MembershipRequest mr : newMembershipRequests) {
					String userId = mr.getUserId();
					Date expiresOn = mr.getExpiresOn();
	
					String token = TokenUtil.createToken(userId, now, datasetSettings, expiresOn.getTime());
					UserProfile userProfile = synapseClient.getUserProfile(userId);
					String messageBody = createTokenMessage(userProfile, token, datasetSettings.getTokenEmailSynapseId());
					MessageToUser messageToUser = new MessageToUser();
					messageToUser.setSubject(datasetSettings.getDataDescriptor()+" Data Access Request");
					messageToUser.setRecipients(Collections.singleton(userId));
					messageUtil.sendMessage(messageToUser, messageBody);
					
					Row applicantProcessed = new Row();
					applicantProcessed.setValues(Arrays.asList(new String[]{
							userId,
							userProfile.getUserName(),
							userProfile.getFirstName(),
							userProfile.getLastName(),
							""+now,
							""+expiresOn.getTime()
					}));
					applicantsProcessed.add(applicantProcessed);
				}
			}
		}
		// update table to show that the email was sent
		System.out.println("Appending "+applicantsProcessed.size()+" rows to Table.");
		if (applicantsProcessed.size()>0) {
			RowSet rowSet = new RowSet();
			String tableId = getProperty("TABLE_ID");
			rowSet.setTableId(tableId);
			String[] columnNames = new String[]{
					USER_ID,
					USER_NAME,
					FIRST_NAME,
					LAST_NAME,
					TOKEN_SENT_DATE,
					MEMBERSHIP_REQUEST_EXPIRATION_DATE};
			rowSet.setHeaders(tableUtil.createRowSetHeaders(tableId, columnNames));
			rowSet.setRows(applicantsProcessed);
			synapseClient.appendRowsToTable(rowSet, TABLE_UPDATE_TIMEOOUT, tableId);
		}
	}
	
	private static final String TOKEN_PLACE_HOLDER = "##token##";
	
	private String createTokenMessage(UserProfile userProfile, String token, String tokenEmailSynapseId) throws IOException, SynapseException {
		String message = messageUtil.createGenericMessage(userProfile, tokenEmailSynapseId);
		return message.replace(TOKEN_PLACE_HOLDER, token);
	}

	// convert incoming mail messages to Submission in the queue
	public void checkForMail() throws Exception {
		IMAPClient mailClient = new IMAPClient();
		mailClient.processNewMessages(new SubmissionMessageHandler(synapseClient));
	}

	// ACT members may submit tokens to the Evaluation queue.  In that case
	// we do not have to the ORIGINATING_IP_SUBNET
	private boolean canBypassMessageValidation(String submissionCreatorId, String myOwnId) throws SynapseException {
		// don't do this override if the creator is the cron job service account
		if (submissionCreatorId.equals(myOwnId)) return false;
		TeamMembershipStatus tms = 
				synapseClient.getTeamMembershipStatus(
						SYNAPSE_ACCESS_AND_COMPLIANCE_TEAM_ID, submissionCreatorId);
		return tms.getIsMember();
	}

	// Check for incoming email and if there is a valid attachment then approve them
	public void approveApplicants() throws Exception {
		String tableId = getProperty("TABLE_ID");
		Map<String, DatasetSettings> datasetSettings = Util.getDatasetSettings();
		String myOwnSnapseId = null;
		long total = Integer.MAX_VALUE;
		String evaluationId = getProperty("EVALUATION_ID");
		List<TokenContent> acceptedTokens = new ArrayList<TokenContent>();
		List<SubmissionStatus> statusesToUpdate = new ArrayList<SubmissionStatus>();
		Map<Long,String> rejected = new HashMap<Long,String>(); // key is userId, value is 'reason' message
		int rejectedCount = 0;
		List<MimeMessageAndReason> rejectedMessages = new ArrayList<MimeMessageAndReason>();
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
				if (myOwnSnapseId==null) myOwnSnapseId = synapseClient.getMyProfile().getOwnerId();
				MimeMessage message = MessageUtil.readMessageFromFile(temp);
				try {
					if (!canBypassMessageValidation(sub.getUserId(), myOwnSnapseId)) {
						String originatingIpSubnet = getProperty("ORIGINATING_IP_SUBNET", /*nullIsOK*/true);
						if (originatingIpSubnet!=null && originatingIpSubnet.length()>0 && 
								!OriginValidator.isOriginatingIPInSubnet(message, originatingIpSubnet)) {
							throw new Exception("Message lacks X-Originating-IP header or is outside of the allowed subnet.");
						}						
					}

					fileIs = new FileInputStream(temp);
					Set<TokenAnalysisResult> tokenAnalysisResults = TokenUtil.parseTokensFromInput(IOUtils.toByteArray(fileIs));
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
					rejectedMessages.add(new MimeMessageAndReason(message, e.getMessage()));
				} finally {
					if (fileIs!=null) fileIs.close();
				}
				status.setStatus(newStatus);
				statusesToUpdate.add(status);
			}
		}
		// Now we've gone through all the new submissions and know what users we want to accept and reject
		// TODO userId is no longer a key.  the key is <userId, teamId, mrExpiration>
		Map<Long,TokenContent> acceptedUsers = new HashMap<Long,TokenContent>();
		for (TokenContent tc : acceptedTokens) {
			acceptedUsers.put(tc.getUserId(), tc);
		}

		// filter the accepted list, removing users who have already been approved
		List<String> acceptedAndNotYetApprovedUserIds = new ArrayList<String>();
		List<Row> acceptedAndNotYetApprovedRows = new ArrayList<Row>();
		List<TokenContent> acceptedAndNotYetApprovedTC = new ArrayList<TokenContent>();
		if (!acceptedUsers.isEmpty()) {
			Pair<List<SelectColumn>, List<Row>> result = selectUnexpiredTokenForUsersAndTeam(acceptedUsers.keySet());	
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
					// TODO this logic will 'approve' all rows for an unapproved user, not just the
					// row for the matching token (if the user has multiple rows)
					if (approvedString==null || Boolean.valueOf(approvedString)==false) {
						acceptedAndNotYetApprovedUserIds.add(userId);
						acceptedAndNotYetApprovedTC.add(acceptedUsers.get(Long.parseLong(userId)));
						acceptedAndNotYetApprovedRows.add(row);
					}
				}
			}
			// if an ID is in acceptedUserIds but not in the Table, then something weird has happened
			// TODO will encounter this condition when there are multiple tokens / user
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
		sendRejectionsToMailSender(rejectedMessages);

		System.out.println("Retrieved "+total+
				" submissions for approval and rejected "+rejectedCount+". Accepted "+acceptedUsers.size()+
				" users and rejected "+confirmedRejected.size()+".");

	}
	
	private static final String REJECTION_SUBJECT = "rejected token email";
	
	/*
	 * For any rejected emails, send an email *back* to the sender containing the reason.
	 */
	private void sendRejectionsToMailSender(List<MimeMessageAndReason> mimeMessageAndReasons) {
		if (mimeMessageAndReasons.isEmpty()) return;
		IMAPClient mailClient = new IMAPClient();
		try {
			Address notificationFrom = new InternetAddress("noreply@sagebase.org");
			for (MimeMessageAndReason mmr : mimeMessageAndReasons) {
				MimeMessage mimeMessage = mmr.getMimeMessage();
				String reason = mmr.getReason();
				MimeMultipart content = new MimeMultipart();
				// add a messge to the sender, including the reason for the rejection
				content.addBodyPart(part);
				// add another part for the original message
				content.addBodyPart(mimeMessage.getContent());
				mailClient.sendMessage(notificationFrom, mimeMessage.getFrom(), REJECTION_SUBJECT, content);
			}
		} catch (MessagingException e) {
			throw new RuntimeException(e);
		}
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
					try {
						synapseClient.addTeamMember(teamId, mr.getUserId(), 
							"https://www.synapse.org/#!Team:", null);
					} catch (SynapseException e) {
						throw new RuntimeException("Team Id: "+teamId+" userId: "+mr.getUserId(), e);
					}
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

	private Map<Long,String> updateTableForRejected(Map<Long,String> rejected, String tableId) throws SynapseException, InterruptedException {
		Map<Long,String> confirmedRejected = new HashMap<Long,String>();
		if (rejected.isEmpty()) return confirmedRejected;
		Pair<List<SelectColumn>, List<Row>> result = selectUnexpiredTokenForUsersAndTeam(rejected.keySet());	
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

	public void sendApproveNotifications(Collection<String> userIds) throws IOException {
		for (String userId : userIds) {
			try {
				UserProfile userProfile = synapseClient.getUserProfile(userId);
				MessageToUser message = new MessageToUser();
				message.setSubject(getProperty("DATA_DESCRIPTOR")+" Data Access Approval");
				message.setRecipients(Collections.singleton(userId));
				synapseClient.sendStringMessage(message, 
						messageUtil.createGenericMessage(userProfile, getProperty("APPROVE_EMAIL_SYNAPSE_ID")));
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
				message.setSubject(getProperty("DATA_DESCRIPTOR")+" Data Access Declined");
				message.setRecipients(Collections.singleton(""+userId));
				StringBuilder messageBody = new StringBuilder();
				messageBody.append(messageUtil.salutation(userProfile));
				messageBody.append("\nYour request for access to the "+getProperty("DATA_DESCRIPTOR")+" data has been declined.\n");
				if (reason!=null) {
					messageBody.append("\n\tReason: ");
					messageBody.append(reason);
					messageBody.append("\n");
				}
				messageBody.append("\n\nSincerely,\n\nSynapse Administration");
				messageUtil.sendMessage(message, messageBody.toString());
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
