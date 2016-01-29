package org.sagebionetworks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.Util.getProperty;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.evaluation.model.Submission;
import org.sagebionetworks.evaluation.model.SubmissionBundle;
import org.sagebionetworks.evaluation.model.SubmissionStatus;
import org.sagebionetworks.evaluation.model.SubmissionStatusEnum;
import org.sagebionetworks.reflection.model.PaginatedResults;
import org.sagebionetworks.repo.model.MembershipRequest;
import org.sagebionetworks.repo.model.UserProfile;
import org.sagebionetworks.repo.model.message.MessageToUser;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.RowSet;

@RunWith(MockitoJUnitRunner.class)
public class NRGRSynapseGlueTest  {
	@Mock
	private SynapseClient synapseClient;
	
	@Mock
	private MessageUtil messageUtil;
	
	@Mock
	private TableUtil tableUtil;
	
	@Mock
	private EvaluationUtil evaluationUtil;
	
	NRGRSynapseGlue nrgrSynapseGlue;
	
	private static final String USER_ID = "111";
	private static final String TEAM_ID = "3324934"; // taken from the SETTINGS property
	private static final String TEAM_2_ID = "3334673"; // taken from the SETTINGS property
	private static final long EXPIRES_ON = System.currentTimeMillis()+21081600000L;
	
	@Before
	public void setUp() throws Exception {
		nrgrSynapseGlue = new NRGRSynapseGlue(synapseClient, messageUtil, tableUtil, evaluationUtil);
	}

	@Test
	public void testProcessNewApplicants() throws Exception {
		// mock the receipt of one new membership request, from the first of two sign-up teams
		{
			PaginatedResults<MembershipRequest> pgs = new PaginatedResults<MembershipRequest>();
			List<MembershipRequest> results = new ArrayList<MembershipRequest>();
			MembershipRequest mr = new MembershipRequest();
			mr.setTeamId(TEAM_ID);
			mr.setUserId(USER_ID);
			mr.setExpiresOn(new Date(EXPIRES_ON));
			results.add(mr);
			pgs.setResults(results);
			pgs.setTotalNumberOfResults(1);
			when(synapseClient.getOpenMembershipRequests(eq(TEAM_ID), (String)isNull(), anyLong(), anyLong())).
				thenReturn(pgs);
			
			when(tableUtil.getNewMembershipRequests(results)).thenReturn(results);
		}
		{
			PaginatedResults<MembershipRequest> pgs = new PaginatedResults<MembershipRequest>();
			pgs.setResults(Collections.EMPTY_LIST);
			pgs.setTotalNumberOfResults(0);
			when(synapseClient.getOpenMembershipRequests(eq(TEAM_2_ID), (String)isNull(), anyLong(), anyLong())).
				thenReturn(pgs);
		}
		
		String message = "a message";
		when(messageUtil.createGenericMessage((UserProfile)any(), anyString())).thenReturn(message);
		
		UserProfile userProfile = new UserProfile();
		userProfile.setOwnerId(USER_ID);
		userProfile.setFirstName("fname");
		userProfile.setLastName("lname");
		userProfile.setUserName("uname");
		when(synapseClient.getUserProfile(USER_ID)).thenReturn(userProfile);
		
		// the call under test:
		nrgrSynapseGlue.processNewApplicants();
		
		// called twice, once for each sign-up team
		verify(synapseClient, times(2)).
			getOpenMembershipRequests(anyString(), (String)isNull(), anyLong(), anyLong());
		
		// called twice, once for each sign-up team
		verify(tableUtil, times(2)).getNewMembershipRequests((Collection)any());

		// check that messageUtil.sendMessage() was called for the one new user
		ArgumentCaptor<MessageToUser> captureMessageToUser = ArgumentCaptor
				.forClass(MessageToUser.class);
		ArgumentCaptor<String> captureMessageBody = ArgumentCaptor
				.forClass(String.class);
		verify(messageUtil, times(1)).sendMessage(
				captureMessageToUser.capture(),
				captureMessageBody.capture());
		
		assertEquals(Collections.singleton(USER_ID), captureMessageToUser.getValue().getRecipients());
		assertEquals("CommonMind Data Access Request", captureMessageToUser.getValue().getSubject());
		assertEquals(message, captureMessageBody.getValue());
		
		// check that synapseClient.appendRowsToTable() was called for the new user
		ArgumentCaptor<RowSet> captureRowSet = ArgumentCaptor
				.forClass(RowSet.class);
		verify(synapseClient, times(1)).
			appendRowsToTable(captureRowSet.capture(), eq(10000L), eq(getProperty("TABLE_ID")));
		RowSet rowSet = captureRowSet.getValue();
		List<Row> rows = rowSet.getRows();
		assertEquals(1, rows.size());
		List<String> values = rows.get(0).getValues();
		assertEquals(USER_ID, values.get(0));
		assertEquals(TEAM_ID, values.get(1));
		assertEquals("uname", values.get(2));
		assertEquals("fname", values.get(3));
		assertEquals("lname", values.get(4));
		assertTrue(System.currentTimeMillis()>=Long.parseLong(values.get(5)));
		assertTrue(System.currentTimeMillis()<1000L+Long.parseLong(values.get(5)));
		assertEquals(""+EXPIRES_ON, values.get(6));
	}
	
	private static File createTempFileWithContent(String content) {
		try {
			File file = File.createTempFile("temp", ".txt");
			FileOutputStream fos = new FileOutputStream(file);
			try {
				IOUtils.write(content, fos, "utf-8");
				return file;
			} finally {
				fos.close();
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	private static File createMessageWithToken(String token) throws IOException {
		String templateName = "mimeWithTokenPlaceholder.txt";
		InputStream is = null;
		try {
			is = NRGRSynapseGlueTest.class.getClassLoader().getResourceAsStream(templateName);
			String template = IOUtils.toString(is);
			return createTempFileWithContent(template.replaceAll("###tokenGoesHere###", token));
		} finally {
			if (is!=null) is.close();
		}
	}
	
	@Test
	public void testProcessReceivedSubmissions_ValidToken() throws Exception {
		List<SubmissionBundle> submissionsToProcess = new ArrayList<SubmissionBundle>();
		SubmissionBundle bundle = new SubmissionBundle();
		Submission submission = new Submission();
		submission.setUserId("000");
		SubmissionStatus submissionStatus = new SubmissionStatus();
		bundle.setSubmission(submission);
		bundle.setSubmissionStatus(submissionStatus);
		submissionsToProcess.add(bundle);
		
		DatasetSettings datasetSettings = Util.getDatasetSettings().get(TEAM_ID);
		long now = System.currentTimeMillis();
		String token = TokenUtil.createToken(USER_ID, now, datasetSettings, now+1000L);
		File downloadedFile = createMessageWithToken(token);
		when(evaluationUtil.downloadSubmissionFile(submission)).thenReturn(downloadedFile);
		
		UserProfile userProfile = new UserProfile();
		userProfile.setOwnerId("000");
		when(synapseClient.getMyProfile()).thenReturn(userProfile);

		// method under test
		SubmissionProcessingResult spr = nrgrSynapseGlue.processReceivedSubmissions(submissionsToProcess);
		
		assertEquals(Collections.singletonList(submissionStatus), spr.getProcessedSubmissions());
		assertEquals(SubmissionStatusEnum.CLOSED, submissionStatus.getStatus());
//		StringAnnotation sa = submissionStatus.getAnnotations().getStringAnnos().get(0);
//		assertEquals("rejectionReason", sa.getKey());
//		assertEquals("No valid token found in file.", sa.getValue());
		assertEquals(1, spr.getValidTokens().size());
		TokenAnalysisResult tar = TokenUtil.parseToken(token, now);
		assertEquals(tar.getTokenContent(), spr.getValidTokens().iterator().next());
		assertTrue(spr.getMessagesToSender().isEmpty());
		
	}
	
	@Test
	public void testApproveApplicants() throws Exception {
		// TODO
		// TODO need to test sending rejection email through actual mail to check its appearance
	}
	
}
