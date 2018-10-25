package org.sagebionetworks;

import static org.sagebionetworks.TableUtil.APPLICATION_TEAM_ID;
import static org.sagebionetworks.TableUtil.DATE_REVOKED;
import static org.sagebionetworks.TableUtil.FIRST_NAME;
import static org.sagebionetworks.TableUtil.LAST_NAME;
import static org.sagebionetworks.TableUtil.MEMBERSHIP_REQUEST_EXPIRATION_DATE;
import static org.sagebionetworks.TableUtil.TABLE_UPDATE_TIMEOUT;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.mail.Address;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.http.entity.ContentType;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.evaluation.model.Submission;
import org.sagebionetworks.evaluation.model.SubmissionBundle;
import org.sagebionetworks.evaluation.model.SubmissionStatus;
import org.sagebionetworks.evaluation.model.SubmissionStatusEnum;
import org.sagebionetworks.reflection.model.PaginatedResults;
import org.sagebionetworks.repo.model.AccessApproval;
import org.sagebionetworks.repo.model.ApprovalState;
import org.sagebionetworks.repo.model.MembershipRequest;
import org.sagebionetworks.repo.model.TeamMembershipStatus;
import org.sagebionetworks.repo.model.UserProfile;
import org.sagebionetworks.repo.model.auth.LoginRequest;
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

	private static final String SYNAPSE_ACCESS_AND_COMPLIANCE_TEAM_ID = "464532";

	private SynapseClient synapseClient;
	private MessageUtil messageUtil;
	private TableUtil tableUtil;
	private EvaluationUtil evaluationUtil;
	private IMAPClient mailClient;

	/*
	 * Parameters:
	 * signupTeamId:  Users wishing to gain access create a membership request in this Team
	 * accessRequirementId: The ID of the access requirement retricting access to the data of interest
	 * 
	 */

	public NRGRSynapseGlue() throws SynapseException {
		synapseClient = SynapseClientFactory.createSynapseClient();
		String adminUserName = getProperty("USERNAME");
		String adminPassword = getProperty("PASSWORD");
		LoginRequest loginRequest = new LoginRequest();
		loginRequest.setUsername(adminUserName);
		loginRequest.setPassword(adminPassword);
		synapseClient.login(loginRequest);
		messageUtil = new MessageUtil(synapseClient);
		tableUtil = new TableUtil(synapseClient, getProperty("TABLE_ID"), getProperty("CONFIGURATION_TABLE_ID"));
		evaluationUtil = new EvaluationUtil(synapseClient);
		this.mailClient = new IMAPClient();
	}
	
	/*
	 * for testing
	 */
	public NRGRSynapseGlue(
			SynapseClient synapseClient, 
			MessageUtil messageUtil, 
			TableUtil tableUtil, 
			EvaluationUtil evaluationUtil,
			IMAPClient mailClient) {
		this.synapseClient=synapseClient;
		this.messageUtil=messageUtil;
		this.tableUtil=tableUtil;
		this.evaluationUtil=evaluationUtil;
		this.mailClient=mailClient;
	}

	public static void main(String[] args) throws Exception {
		NRGRSynapseGlue sg = new NRGRSynapseGlue();
		sg.processNewApplicants();
		sg.checkForMail();
		Map<String,DatasetSettings> settings = sg.getDatasetSettings();
		sg.approveApplicants(settings);
		sg.removeExpiredAccess(settings);
	}
	
	public Map<String,DatasetSettings> getDatasetSettings() throws Exception {
		return tableUtil.getDatasetSettings();
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

	private static long PAGE_SIZE = 50L;

	public void processNewApplicants() throws Exception {
		List<Row> applicantsProcessed = new ArrayList<Row>();
		long now = System.currentTimeMillis();
		for (DatasetSettings datasetSettings : tableUtil.getDatasetSettings().values()) {
			// process each data set
			long total = Integer.MAX_VALUE;
			for (long offset=0; offset<total; offset+=PAGE_SIZE) {
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
					Date expiresOn = Util.cleanDate(mr.getExpiresOn());
					Long expiresOnAsLong = expiresOn==null ? null : expiresOn.getTime();
	
					String token = TokenUtil.createToken(userId, now, datasetSettings, expiresOnAsLong);
					UserProfile userProfile = synapseClient.getUserProfile(userId);
					String messageBody = createTokenMessage(userProfile, token, datasetSettings.getTokenEmailSynapseId());
					MessageToUser messageToUser = new MessageToUser();
					messageToUser.setSubject(datasetSettings.getDataDescriptor()+" Data Access Request");
					Set<String> recipients = new HashSet<String>(Collections.singleton(userId));
					String ccRecipient = getProperty("CC_RECIPIENT", true);
					if (ccRecipient!=null) recipients.add(ccRecipient);
					messageToUser.setRecipients(recipients);
					messageUtil.sendMessage(messageToUser, messageBody);
					
					Row applicantProcessed = new Row();
					applicantProcessed.setValues(Arrays.asList(new String[]{
							userId,
							mr.getTeamId(),
							userProfile.getUserName(),
							userProfile.getFirstName(),
							userProfile.getLastName(),
							""+now,
							expiresOnAsLong==null?null:""+expiresOnAsLong
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
					APPLICATION_TEAM_ID,
					USER_NAME,
					FIRST_NAME,
					LAST_NAME,
					TOKEN_SENT_DATE,
					MEMBERSHIP_REQUEST_EXPIRATION_DATE};
			rowSet.setHeaders(tableUtil.createRowSetHeaders(tableId, columnNames));
			rowSet.setRows(applicantsProcessed);
			synapseClient.appendRowsToTable(rowSet, TABLE_UPDATE_TIMEOUT, tableId);
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
		String messageParentId = getProperty("MESSAGE_CONTAINER_ENTITY_ID");
		String evaluationId = getProperty("EVALUATION_ID"); 
		mailClient.processNewMessages(new SubmissionMessageHandler(synapseClient, messageParentId, evaluationId));
	}

	// ACT members may submit tokens to the Evaluation queue.  In that case
	// we do not have to check the ORIGINATING_IP_SUBNET
	private boolean canBypassMessageValidation(String submissionCreatorId, String myOwnId) throws SynapseException {
		// don't do this override if the creator is the cron job service account
		if (submissionCreatorId.equals(myOwnId)) return false;
		TeamMembershipStatus tms = 
				synapseClient.getTeamMembershipStatus(
						SYNAPSE_ACCESS_AND_COMPLIANCE_TEAM_ID, submissionCreatorId);
		return tms.getIsMember();
	}
	
	/*
	 * return the list of allowed IP originating subnets (if any) required by the valid tokens in the
	 * given tokenAnalysisResults. If empty then any subnet is OK.
	 */
	List<String> getAllowedIPOriginatingSubnets(TokenAnalysisResult tar, Map<String,DatasetSettings> settings) {
		String applicationTeamId = tar.getTokenContent().getApplicationTeamId();
		DatasetSettings ds = settings.get(applicationTeamId);
		return ds.getOriginatingIPsubnets();
	}
	
	public SubmissionProcessingResult processReceivedSubmissions(List<SubmissionBundle> submissionsToProcess, Map<String,DatasetSettings> settings) {
		String myOwnSnapseId = null;
		SubmissionProcessingResult result = new SubmissionProcessingResult();
		for (SubmissionBundle bundle : submissionsToProcess) {
			Submission sub = bundle.getSubmission();
			SubmissionStatus status = bundle.getSubmissionStatus();
			status.setStatus(SubmissionStatusEnum.CLOSED); // this is the default, might be overridden
			result.addProcessedSubmission(status);

			InputStream fileIs = null;
			MimeMessage message = null;
			try {
				File temp = evaluationUtil.downloadSubmissionFile(sub);
				temp.deleteOnExit();
				message = MessageUtil.readMessageFromFile(temp);
				if (myOwnSnapseId==null) {
					myOwnSnapseId = synapseClient.getMyProfile().getOwnerId();
				}
				
				fileIs = new FileInputStream(temp);
				Set<TokenAnalysisResult> tokenAnalysisResults = 
						TokenUtil.parseTokensFromInput(IOUtils.toByteArray(fileIs), settings, System.currentTimeMillis());
				int validTokensInMessage = 0;
				int messageViolatesSubnetRequirementTokenCount = 0;
				for (TokenAnalysisResult tar : tokenAnalysisResults) {
					if(tar.isValid()) {
						validTokensInMessage++;
						List<String> rios = getAllowedIPOriginatingSubnets(tar, settings);
						if (!canBypassMessageValidation(sub.getUserId(), myOwnSnapseId) &&
							!rios.isEmpty() && 
							!OriginValidator.isOriginatingIPInSubnets(message, rios)) {
							messageViolatesSubnetRequirementTokenCount++;
						} else {
							result.addValidToken(tar.getTokenContent());
						}
					}
				}
				if (messageViolatesSubnetRequirementTokenCount>0) {
					String reason = "Message lacks X-Originating-IP header or is outside of the allowed subnet.";
					if (messageViolatesSubnetRequirementTokenCount==validTokensInMessage) {
						throw new Exception(reason);
					} else {
						// This is weird edge case in which the message has the right subnet for some
						// tokens, but not for others.
						reason = "For "+messageViolatesSubnetRequirementTokenCount+" tokens, "+reason;
						result.addMessageToSender(new MimeMessageAndReason(message, reason));
					}
				}
				if (validTokensInMessage==0) {
					throw new Exception("No valid token found in file.");
				}
				if (validTokensInMessage < tokenAnalysisResults.size()) {
					String reason = ""+validTokensInMessage+" valid token(s) and "+
							(tokenAnalysisResults.size()-validTokensInMessage)+" invalid token(s) "+
							"were found in this message.";
					result.addMessageToSender(new MimeMessageAndReason(message, reason));
				}
			} catch (Exception e) {
				status.setStatus(SubmissionStatusEnum.REJECTED);
				EvaluationUtil.addRejectionReasonToStatus(status, e.getMessage());
				if (message!=null) result.addMessageToSender(new MimeMessageAndReason(message, e.getMessage()));
			} finally {
				if (fileIs!=null) {
					try {
						fileIs.close();
					} catch (IOException e) {
						// continue
					}
				}
			}
		} // end for SubmissionBundle bundle ...
		
		return result;
	}
	
	// Check for incoming email and if there is a valid attachment then approve them
	public void approveApplicants(Map<String,DatasetSettings> settings) throws Exception {
		String evaluationId = getProperty("EVALUATION_ID");

		List<SubmissionBundle> receivedSubmissions = evaluationUtil.getReceivedSubmissions(evaluationId);
		SubmissionProcessingResult sprs = processReceivedSubmissions(receivedSubmissions, settings);
		
		// notify message sender about any bad messages (missing tokens, etc.)
		sendRejectionsToMailSender(sprs.getMessagesToSender());
		
		// find those not already approved
		TokenTableLookupResults usersToApprove = tableUtil.getRowsForAcceptedButNotYetApprovedUserIds(sprs.getValidTokens());
		
		// create the AccessApprovals in Synapse
		createAccessApprovals(usersToApprove.getTokens());
		
		// accept team membership requests
		acceptTeamMembershipRequests(usersToApprove.getTokens());

		// send the notifications
		sendApproveNotifications(usersToApprove.getTokens(), settings);
		
		// update the approval records in the Table
		RowSet rowSet = usersToApprove.getRowSet();
		if (!rowSet.getRows().isEmpty()) {
			int approvedOrRejectedDateIndex = 
					TableUtil.getColumnIndexForName(
							rowSet.getHeaders(), TableUtil.APPROVED_ON);
			Long now = System.currentTimeMillis();
			for (Row row : rowSet.getRows()) {
				row.getValues().set(approvedOrRejectedDateIndex, now.toString());
			}
			String tableId = getProperty("TABLE_ID");
			synapseClient.appendRowsToTable(rowSet, TABLE_UPDATE_TIMEOUT, tableId);
		}
		
		// update submission statuses
		evaluationUtil.updateSubmissionStatusBatch(sprs.getProcessedSubmissions(), evaluationId);

		System.out.println("Retrieved "+sprs.getProcessedSubmissions().size()+
				" submissions for approval and accepted "+sprs.getValidTokens().size()+" users.");

	}
	
	private static final boolean ENABLE_REVOCATION = true;
	
	public void removeExpiredAccess(Map<String,DatasetSettings> settings) throws Exception {
		Long now = System.currentTimeMillis();
		for (String approvalTeamId : settings.keySet()) {
			DatasetSettings ds = settings.get(approvalTeamId);
			if (ds.getExpiresAfterDays()!=null) {
				Pair<List<SelectColumn>, RowSet> queryResult = tableUtil.getExpiredAccess(ds);
				int userIdIndex = getColumnIndexForName(queryResult.getFirst(), USER_ID);
				int dateRevokedIndex = getColumnIndexForName(queryResult.getFirst(), DATE_REVOKED);
				RowSet rowSet = queryResult.getSecond();
				List<String> usersToNotify = new ArrayList<String>();
				for (Row row : rowSet.getRows()) {
					List<String> values = row.getValues();
					String userId = values.get(userIdIndex);
					usersToNotify.add(userId);
					// revoke access approvals
					for (long requirementId : ds.getAccessRequirementIds()) {
						if (ENABLE_REVOCATION) {
							synapseClient.revokeAccessApprovals(""+requirementId, userId);
						} else {
							System.out.println("Revoking access to requirement "+requirementId+" for user "+userId);
						}
					}
					values.set(dateRevokedIndex, now.toString());
					// remove from approval team
					if (ENABLE_REVOCATION) {
						synapseClient.removeTeamMember(approvalTeamId, userId);
					} else {
						System.out.println("Removing member "+userId+" from team "+approvalTeamId);
					}
				}
				// update table
				if (ENABLE_REVOCATION) {
					synapseClient.appendRowsToTable(rowSet, TABLE_UPDATE_TIMEOUT, rowSet.getTableId());
				} else {
					System.out.println("Updating table to show revocation for "+rowSet.getRows().size()+" users.");
				}
				if (ENABLE_REVOCATION) {
					sendRevocationNotifications(usersToNotify, ds.getRevocationEmailSynapseId());
				} else {
					System.out.println("Sending notifications to "+rowSet.getRows().size()+" users.");
				}
			}
		}
	}
	
	private static final String REJECTION_SUBJECT = "error in token email";
	
	/*
	 * For any rejected emails, send an email *back* to the sender containing the reason.
	 */
	private void sendRejectionsToMailSender(List<MimeMessageAndReason> mimeMessageAndReasons) {
		if (mimeMessageAndReasons.isEmpty()) return;
		try {
			Address notificationFrom = new InternetAddress("noreply@sagebase.org");
			for (MimeMessageAndReason mmr : mimeMessageAndReasons) {
				MimeMessage mimeMessage = mmr.getMimeMessage();
				MimeMultipart content = new MimeMultipart();
				// add a message to the sender, including the reason for the rejection
				MimeBodyPart reason = new MimeBodyPart();
				reason.setContent(mmr.getReason()+"\n(original message below)\n\n", ContentType.TEXT_PLAIN.getMimeType());
				content.addBodyPart(reason);
				Object messageContent = mimeMessage.getContent();
				if (messageContent instanceof MimeMultipart) {
					// add another part for the original message
					MimeMultipart mimeMultipart = (MimeMultipart)messageContent;
					for (int i=0; i<mimeMultipart.getCount(); i++) {
						content.addBodyPart(mimeMultipart.getBodyPart(i));
					}
				} else if (messageContent instanceof String) {
					String stringMessageContent = (String)messageContent;
					MimeBodyPart bodyPart = new MimeBodyPart();
					bodyPart.setContent(stringMessageContent, ContentType.TEXT_PLAIN.getMimeType());
					content.addBodyPart(bodyPart);
				} else if (messageContent instanceof InputStream) {
					String stringMessageContent = IOUtils.toString((InputStream)messageContent);
					MimeBodyPart bodyPart = new MimeBodyPart();
					bodyPart.setContent(stringMessageContent, ContentType.TEXT_PLAIN.getMimeType());
					content.addBodyPart(bodyPart);
				} else {
					throw new RuntimeException("Unexpcected type "+messageContent.getClass());
				}
				mailClient.sendMessage(notificationFrom, mimeMessage.getFrom(), REJECTION_SUBJECT, content);
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void createAccessApprovals(Collection<TokenContent> usersToApprove) throws SynapseException {
		for (TokenContent tc: usersToApprove) {
			long userId = tc.getUserId();
			for (Long accessRequirementId : tc.getAccessRequirementIds()) {
				AccessApproval actAccessApproval = new AccessApproval();
				actAccessApproval.setAccessorId(""+userId);
				actAccessApproval.setState(ApprovalState.APPROVED);
				actAccessApproval.setRequirementId(accessRequirementId);
				actAccessApproval.setRequirementVersion(0L);
				synapseClient.createAccessApproval(actAccessApproval);
			}
		}
	}

	private void acceptTeamMembershipRequests(Collection<TokenContent> usersToApprove) throws SynapseException {
		for (TokenContent tc : usersToApprove) {
			String teamId = tc.getApplicationTeamId();
			long userId = tc.getUserId();
			try {
				synapseClient.addTeamMember(teamId, ""+userId, 
					"https://www.synapse.org/#!Team:", null);
			} catch (SynapseException e) {
				throw new RuntimeException("Team Id: "+teamId+" userId: "+userId, e);
			}
		}
	}

	public void sendApproveNotifications(Collection<TokenContent> usersToApprove, Map<String,DatasetSettings> settingsMap) throws IOException {
		for (TokenContent tc : usersToApprove) {
			try {
				UserProfile userProfile = synapseClient.getUserProfile(""+tc.getUserId());
				DatasetSettings settings = settingsMap.get(tc.getApplicationTeamId());
				MessageToUser message = new MessageToUser();
				message.setSubject(settings.getDataDescriptor()+" Data Access Approval");
				Set<String> recipients = new HashSet<String>(Collections.singleton(""+tc.getUserId()));
				String ccRecipient = getProperty("CC_RECIPIENT", true);
				if (ccRecipient!=null) recipients.add(ccRecipient);
				message.setRecipients(recipients);
				synapseClient.sendStringMessage(message, 
						messageUtil.createGenericMessage(userProfile, 
								settings.getApprovalEmailSynapseId()));
			} catch (SynapseException e) {
				// if the message fails, just log it and go on to the next one
				e.printStackTrace();
			}
		}
	}
	
	public void sendRevocationNotifications(Collection<String> userIds, String revocationTemplateId) throws IOException {
		if (revocationTemplateId==null) throw new IllegalArgumentException("No email template.");
		for (String userId : userIds) {
			try {
				UserProfile userProfile = synapseClient.getUserProfile(userId);
				MessageToUser message = new MessageToUser();
				message.setSubject("NRGR Data Access Revocation");
				Set<String> recipients = new HashSet<String>(Collections.singleton(userId));
				String ccRecipient = getProperty("CC_RECIPIENT", true);
				if (ccRecipient!=null) recipients.add(ccRecipient);
				message.setRecipients(recipients);
				synapseClient.sendStringMessage(message, 
						messageUtil.createGenericMessage(userProfile,revocationTemplateId));
			} catch (SynapseException e) {
				// if the message fails, just log it and go on to the next one
				e.printStackTrace();
			}
		}
	}

}
