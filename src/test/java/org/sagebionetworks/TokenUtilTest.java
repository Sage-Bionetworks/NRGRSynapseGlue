package org.sagebionetworks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.sagebionetworks.client.exceptions.SynapseException;

public class TokenUtilTest {
	
	private static final String TEAM_ID = "3324934";

	private static DatasetSettings createDatasetSettings(String teamId, long requirementId) {
		DatasetSettings datasetSettings = new DatasetSettings();
		datasetSettings.setApplicationTeamId(teamId);
		datasetSettings.setAccessRequirementIds(Collections.singletonList(requirementId));
		datasetSettings.setApprovalEmailSynapseId("syn202");
		datasetSettings.setDataDescriptor("bar");
		datasetSettings.setOriginatingIPsubnets(Collections.singletonList("156.40.0.0/16"));
		datasetSettings.setTokenEmailSynapseId("syn101");
		datasetSettings.setTokenExpirationTimeDays(244);
		datasetSettings.setTokenLabel("foo");
		return datasetSettings;
	}
	
	private static Map<String, DatasetSettings> createDatasetSettingsMap() {
		return createDatasetSettingsMap(TEAM_ID, 999L);
	}
		
	private static Map<String, DatasetSettings> createDatasetSettingsMap(String teamId, long requirementId) {
		Map<String, DatasetSettings> result = new HashMap<String, DatasetSettings>();
		DatasetSettings ds = createDatasetSettings(teamId, requirementId);
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
    
    private static final MembershipRequestChecker MOCK_MRC_RETURN_TRUE = new MembershipRequestChecker() {
		@Override
		public boolean doesMembershipRequestExist(String teamId, String userId) throws SynapseException {
			return true;
		}
    };

    private static final MembershipRequestChecker MOCK_MRC_RETURN_FALSE = new MembershipRequestChecker() {
		@Override
		public boolean doesMembershipRequestExist(String teamId, String userId) throws SynapseException {
			return false;
		}
    };
    
    @Before
    public void setUp() throws Exception {
    	System.setProperty("ORIGINAL_APPLICATION_TEAM_ID", TEAM_ID);
    	System.setProperty("HMAC_SECRET_KEY", "foo");
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
		
		Set<TokenAnalysisResult> tars = TokenUtil.parseTokensFromInput(token.getBytes(), createDatasetSettingsMap(), MOCK_MRC_RETURN_TRUE, currentTimeForTesting);
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
 		Set<TokenAnalysisResult> tars = TokenUtil.parseTokensFromInput(token.getBytes(), createDatasetSettingsMap(), MOCK_MRC_RETURN_TRUE, currentTimeForTesting);
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
    public void testParseAnotherV2Token() throws Exception {
    	String token = "=============== SYNAPSE LINK TOKEN BOUNDARY ===============|PsychENCODE|3412738|3334673|5612415|null|1595262609995|S72aj7MXN5oyZduc4ivuKLDrMcA=|=============== SYNAPSE LINK TOKEN BOUNDARY ===============";
 		Set<TokenAnalysisResult> tars = TokenUtil.parseTokensFromInput(token.getBytes(), createDatasetSettingsMap(), MOCK_MRC_RETURN_TRUE, currentTimeForTesting);  
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
 		Set<TokenAnalysisResult> tars = TokenUtil.parseTokensFromInput(token.getBytes(), createDatasetSettingsMap(), MOCK_MRC_RETURN_TRUE, currentTimeForTesting);
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
				parseTokensFromInput(fileContent.getBytes(), createDatasetSettingsMap(), MOCK_MRC_RETURN_TRUE, currentTimeForTesting);
		assertEquals(1, tars.size());
		TokenAnalysisResult tar = tars.iterator().next();
		checkTokenAnalysisResult(userId, timestamp, arIds, tar);

		fileContent = 
				TokenUtil.TOKEN_TERMINATOR+"\\\n"+
						signedToken+"\\\n"+TokenUtil.TOKEN_TERMINATOR+"}";
		tars = TokenUtil.
				parseTokensFromInput(fileContent.getBytes(), createDatasetSettingsMap(), MOCK_MRC_RETURN_TRUE, currentTimeForTesting);
		assertEquals(1, tars.size());
		tar = tars.iterator().next();
		checkTokenAnalysisResult(userId, timestamp, arIds, tar);
	}
	
	@Test
	public void testParseTokenNoMembershiupRequest() throws Exception {
		String token="=============== SYNAPSE LINK TOKEN BOUNDARY =============== |mPowerPDQ8|3335825|3336567|5549294|1542728506836|1527172928052|royn6wCAsHfbMstOfS7lnAl/Iko=| =============== SYNAPSE LINK TOKEN BOUNDARY ===============";
		Set<TokenAnalysisResult> tars = TokenUtil.
				parseTokensFromInput(token.getBytes(), createDatasetSettingsMap("3336567", 5549294), MOCK_MRC_RETURN_FALSE, System.currentTimeMillis());
		assertEquals(1, tars.size());
		TokenAnalysisResult tar = tars.iterator().next();
		assertFalse(tar.isValid());
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
				parseTokensFromInput(fileContent.getBytes(), createDatasetSettingsMap(), MOCK_MRC_RETURN_TRUE, currentTimeForTesting);
		assertEquals(1, tars.size());
		TokenAnalysisResult tar = tars.iterator().next();
		checkTokenAnalysisResult(userId, timestamp, arIds, tar);

		fileContent = 
				TokenUtil.OLD_TOKEN_TERMINATOR+"\\\n"+
						signedToken+"\\\n"+TokenUtil.OLD_TOKEN_TERMINATOR+"}";
		tars = TokenUtil.
				parseTokensFromInput(fileContent.getBytes(), createDatasetSettingsMap(), MOCK_MRC_RETURN_TRUE, currentTimeForTesting);
		assertEquals(1, tars.size());
		tar = tars.iterator().next();
		checkTokenAnalysisResult(userId, timestamp, arIds, tar);
	}

}
