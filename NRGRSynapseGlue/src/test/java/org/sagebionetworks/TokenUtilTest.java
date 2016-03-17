package org.sagebionetworks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.sagebionetworks.Util.getProperty;

import java.io.InputStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.junit.Test;

public class TokenUtilTest {
	
	private static final String TEAM_ID = "3324934";

	private static DatasetSettings createDatasetSettings() {
		DatasetSettings datasetSettings = new DatasetSettings();
		datasetSettings.setApplicationTeamId(TEAM_ID);
		datasetSettings.setAccessRequirementIds(Collections.singletonList(999L));
		datasetSettings.setApprovalEmailSynapseId("syn202");
		datasetSettings.setDataDescriptor("bar");
		datasetSettings.setOriginatingIPsubnet("156.40.0.0/16");
		datasetSettings.setTokenEmailSynapseId("syn101");
		datasetSettings.setTokenExpirationTimeDays(244);
		datasetSettings.setTokenLabel("foo");
		return datasetSettings;
	}
	
	private static Map<String, DatasetSettings> createDatasetSettingsMap() {
		Map<String, DatasetSettings> result = new HashMap<String, DatasetSettings>();
		DatasetSettings ds = createDatasetSettings();
		result.put(ds.getApplicationTeamId(), ds);
		return result;
	}
	


	private static void checkTokenAnalysisResult(Long userId, Long timestamp, List<Long> arIds, TokenAnalysisResult tar) {
		assertTrue(tar.isValid());
		assertEquals(userId, tar.getUserId());
		assertNull(tar.getReason());
		TokenContent tc = tar.getTokenContent();
		tc.getAccessRequirementIds();
		assertEquals(new Date(timestamp), tc.getTimestamp());
		assertEquals(userId.longValue(), tc.getUserId());
		assertEquals(arIds, tc.getAccessRequirementIds());
	}
	
    private static final DateFormat df = new SimpleDateFormat("MMM dd yyyy");
    private static final long currentTimeForTesting;
    static { 
    	try {
    		currentTimeForTesting =  df.parse("Apr 1 2015").getTime(); 
    	} catch (ParseException e) {
    		throw new RuntimeException(e);
    	}
    }

    @Test
	public void testCreateAndParseV1Token() throws Exception {
		Long userId = new Long(273995L);
		long now = System.currentTimeMillis();
		List<Long> arList = Collections.singletonList(111L);		
		String unsignedToken = 
				TokenUtil.createV1UnsignedToken(
						""+userId, 
						arList,
						""+now);
		String token = "\n"+TokenUtil.TOKEN_TERMINATOR+"\n"+
				unsignedToken+TokenUtil.hmac(unsignedToken)+TokenUtil.PART_SEPARATOR+
				"\n"+TokenUtil.TOKEN_TERMINATOR+"\n";
		
		Set<TokenAnalysisResult> tars = TokenUtil.parseTokensFromInput(token.getBytes(), createDatasetSettingsMap(), currentTimeForTesting);
		assertEquals(1, tars.size());
		TokenAnalysisResult tar = tars.iterator().next();
		assertTrue(tar.isValid());
		assertEquals(userId, tar.getUserId());
		assertNull(tar.getReason());
		TokenContent tc = tar.getTokenContent();
		assertEquals(userId.longValue(), tc.getUserId());
		assertEquals(arList, tc.getAccessRequirementIds());
		assertEquals(getProperty("ORIGINAL_APPLICATION_TEAM_ID"), tc.getApplicationTeamId());
		assertEquals(null, tc.getMembershipRequestExpiration());
		assertEquals(new Date(now), tc.getTimestamp());
		assertEquals(null, tc.getTokenLabel());
	}

    @Test
 	public void testCreateAndParseV2Token() throws Exception {
 		Long userId = new Long(273995L);
 		long now = System.currentTimeMillis();
 		long mrExpiration = now - 1000L; // just make it different
 		DatasetSettings settings = new DatasetSettings();
 		settings.setTokenLabel("PsychENCODE");
 		List<Long> arList = Collections.singletonList(111L);
 		settings.setAccessRequirementIds(arList);
 		settings.setApplicationTeamId(TEAM_ID);
 		String token = TokenUtil.createToken(""+userId, now, settings, mrExpiration);
 		Set<TokenAnalysisResult> tars = TokenUtil.parseTokensFromInput(token.getBytes(), createDatasetSettingsMap(), currentTimeForTesting);
 		assertEquals(1, tars.size());
 		TokenAnalysisResult tar = tars.iterator().next();
 		assertTrue(tar.isValid());
 		assertEquals(userId, tar.getUserId());
 		assertNull(tar.getReason());
 		TokenContent tc = tar.getTokenContent();
 		assertEquals(userId.longValue(), tc.getUserId());
 		assertEquals(arList, tc.getAccessRequirementIds());
 		assertEquals(TEAM_ID, tc.getApplicationTeamId());
 		assertEquals(new Date(mrExpiration), tc.getMembershipRequestExpiration());
 		assertEquals(new Date(now), tc.getTimestamp());
 		assertEquals("PsychENCODE", tc.getTokenLabel());
 	}

    @Test
 	public void testCreateAndParseV2TokenWithNullMembershipRequestExpiration() throws Exception {
 		Long userId = new Long(273995L);
 		long now = System.currentTimeMillis();
 		Long mrExpiration = null; // just make it different
 		DatasetSettings settings = new DatasetSettings();
 		settings.setTokenLabel("PsychENCODE");
 		List<Long> arList = Collections.singletonList(111L);
 		settings.setAccessRequirementIds(arList);
 		settings.setApplicationTeamId(TEAM_ID);
 		String token = TokenUtil.createToken(""+userId, now, settings, mrExpiration);
 		Set<TokenAnalysisResult> tars = TokenUtil.parseTokensFromInput(token.getBytes(), createDatasetSettingsMap(), currentTimeForTesting);
 		assertEquals(1, tars.size());
 		TokenAnalysisResult tar = tars.iterator().next();
 		assertTrue(tar.getReason(), tar.isValid());
 		assertEquals(userId, tar.getUserId());
 		assertNull(tar.getReason());
 		TokenContent tc = tar.getTokenContent();
 		assertEquals(userId.longValue(), tc.getUserId());
 		assertEquals(arList, tc.getAccessRequirementIds());
 		assertEquals(TEAM_ID, tc.getApplicationTeamId());
 		assertNull(tc.getMembershipRequestExpiration());
 		assertEquals(new Date(now), tc.getTimestamp());
 		assertEquals("PsychENCODE", tc.getTokenLabel());
 	}

	@Test
	public void testParseTokenFromMessageContent() throws Exception {
		Long userId = new Long(273995L);
		Long timestamp = System.currentTimeMillis();
		List<Long> arIds = Arrays.asList(new Long[]{111111L, 222222L, 333333L});
		String unsignedToken = TokenUtil.createV1UnsignedToken(""+userId, arIds, ""+timestamp);
		String signedToken = unsignedToken+TokenUtil.hmac(unsignedToken)+"|";
		String fileContent= TokenUtil.TOKEN_TERMINATOR+"\\\n"+
				signedToken+"\n"+TokenUtil.TOKEN_TERMINATOR+"}";
		Set<TokenAnalysisResult> tars = TokenUtil.
				parseTokensFromInput(fileContent.getBytes(), createDatasetSettingsMap(), currentTimeForTesting);
		assertEquals(1, tars.size());
		TokenAnalysisResult tar = tars.iterator().next();
		checkTokenAnalysisResult(userId, timestamp, arIds, tar);

		fileContent = 
				TokenUtil.TOKEN_TERMINATOR+"\\\n"+
						signedToken+"\\\n"+TokenUtil.TOKEN_TERMINATOR+"}";
		tars = TokenUtil.
				parseTokensFromInput(fileContent.getBytes(), createDatasetSettingsMap(), currentTimeForTesting);
		assertEquals(1, tars.size());
		tar = tars.iterator().next();
		checkTokenAnalysisResult(userId, timestamp, arIds, tar);
	}
	
	@Test
	public void testParseTokenFromMessageContentWithOldTerminator() throws Exception {
		Long userId = new Long(273995L);
		Long timestamp = System.currentTimeMillis();
		List<Long> arIds = Arrays.asList(new Long[]{111111L, 222222L, 333333L});
		String unsignedToken = TokenUtil.createV1UnsignedToken(""+userId, arIds, ""+timestamp);
		String signedToken = unsignedToken+TokenUtil.hmac(unsignedToken)+"|";
		String fileContent= TokenUtil.OLD_TOKEN_TERMINATOR+"\\\n"+
				signedToken+"\n"+TokenUtil.OLD_TOKEN_TERMINATOR+"}";
		Set<TokenAnalysisResult> tars = TokenUtil.
				parseTokensFromInput(fileContent.getBytes(), createDatasetSettingsMap(), currentTimeForTesting);
		assertEquals(1, tars.size());
		TokenAnalysisResult tar = tars.iterator().next();
		checkTokenAnalysisResult(userId, timestamp, arIds, tar);

		fileContent = 
				TokenUtil.OLD_TOKEN_TERMINATOR+"\\\n"+
						signedToken+"\\\n"+TokenUtil.OLD_TOKEN_TERMINATOR+"}";
		tars = TokenUtil.
				parseTokensFromInput(fileContent.getBytes(), createDatasetSettingsMap(), currentTimeForTesting);
		assertEquals(1, tars.size());
		tar = tars.iterator().next();
		checkTokenAnalysisResult(userId, timestamp, arIds, tar);
	}
	
	@Test
	public void testParseTokenFromInlineMessage() throws Exception {
		InputStream is = null;
		try {
			is = MessageUtilTest.class.getClassLoader().getResourceAsStream("mimeWithTokenInLine.txt");
			Set<TokenAnalysisResult> tars = TokenUtil.parseTokensFromInput(IOUtils.toByteArray(is), createDatasetSettingsMap(), currentTimeForTesting);
			assertEquals(tars.toString(), 1, tars.size());
			TokenAnalysisResult tar = tars.iterator().next();
			assertTrue(tar.getReason(), tar.isValid());
			assertEquals(new Long(273960L), tar.getUserId());
			TokenContent tc = tar.getTokenContent();
			assertEquals(273960L, tc.getUserId());
			assertEquals(new Date(1425959448905L), tc.getTimestamp());
			assertEquals(Collections.singletonList(3248760L), tc.getAccessRequirementIds());
		} finally {
			is.close();
		}

	}

	@Test
	public void testParseTokenFromMessageAttachment() throws Exception {
		InputStream is = null;
		try {
			is = MessageUtilTest.class.getClassLoader().getResourceAsStream("mimeWithTokenAttachment.txt");
			Set<TokenAnalysisResult> tars = TokenUtil.parseTokensFromInput(IOUtils.toByteArray(is), createDatasetSettingsMap(), currentTimeForTesting);
			assertEquals(1, tars.size());
			TokenAnalysisResult tar = tars.iterator().next();
			assertTrue(tar.getReason(), tar.isValid());
			assertEquals(new Long(273960L), tar.getUserId());
			TokenContent tc = tar.getTokenContent();
			assertEquals(273960L, tc.getUserId());
			assertEquals(new Date(1425959448905L), tc.getTimestamp());
			assertEquals(Collections.singletonList(3248760L), tc.getAccessRequirementIds());
		} finally {
			is.close();
		}

	}

	@Test
	public void testParseTokenFromSMIMEWithAttachments() throws Exception {
		InputStream is = null;
		try {
			is = MessageUtilTest.class.getClassLoader().getResourceAsStream("nimh_with_two_attachments_smime.txt");
			Set<TokenAnalysisResult> tars = TokenUtil.parseTokensFromInput(IOUtils.toByteArray(is), createDatasetSettingsMap(), currentTimeForTesting);
			assertEquals(2, tars.size());
			for (TokenAnalysisResult tar : tars) {
				assertTrue(tar.getReason(), tar.isValid());
				assertTrue(273960L==tar.getUserId() || 1449507L==tar.getUserId());
				TokenContent tc = tar.getTokenContent();
				assertEquals(tar.getUserId().longValue(), tc.getUserId());
				assertEquals(Collections.singletonList(3248760L), tc.getAccessRequirementIds());
				
			}
		} finally {
			is.close();
		}

	}

	// this case appeared as four valid and one invalid token because one copy of one valid token
	// was added with an extra line break.
	@Test
	public void testFourValidOneInvalid() throws Exception {
		InputStream is = null;
		try {
			is = MessageUtilTest.class.getClassLoader().getResourceAsStream("fourValidOneInvalid.txt");
			Set<TokenAnalysisResult> tars = TokenUtil.parseTokensFromInput(IOUtils.toByteArray(is), createDatasetSettingsMap(), currentTimeForTesting);
			assertEquals(4, tars.size());
			for (TokenAnalysisResult tar : tars) {
				assertTrue(tar.getReason(), tar.isValid());
			}
		} finally {
			is.close();
		}

	}


}
