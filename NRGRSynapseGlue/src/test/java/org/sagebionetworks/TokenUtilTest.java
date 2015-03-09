package org.sagebionetworks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class TokenUtilTest {

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
	
	@Test
	public void testCreateToken() throws Exception {
		Long userId = new Long(273995L);
		String token = TokenUtil.createToken(""+userId);
		Set<TokenAnalysisResult> tars = TokenUtil.parseTokensFromFileContent(token);
		assertEquals(1, tars.size());
		TokenAnalysisResult tar = tars.iterator().next();
		assertTrue(tar.isValid());
		assertEquals(userId, tar.getUserId());
		assertNull(tar.getReason());
		TokenContent tc = tar.getTokenContent();
		assertEquals(userId.longValue(), tc.getUserId());
	}

	@Test
	public void testParseTokenFromFileContent() throws Exception {
		Long userId = new Long(273995L);
		Long timestamp = new Long(1425074994612L);
		List<Long> arIds = Arrays.asList(new Long[]{3248760L});
		String unsignedToken = TokenUtil.createUnsignedToken(""+userId, arIds, ""+timestamp);
		String signedToken = unsignedToken+TokenUtil.hmac(unsignedToken)+"|";
		String fileContent= TokenUtil.TOKEN_TERMINATOR+"\\\n"+
				signedToken+"\n"+TokenUtil.TOKEN_TERMINATOR+"}";
		Set<TokenAnalysisResult> tars = TokenUtil.parseTokensFromFileContent(fileContent);
		assertEquals(1, tars.size());
		TokenAnalysisResult tar = tars.iterator().next();
		checkTokenAnalysisResult(userId, timestamp, arIds, tar);

		fileContent = 
				TokenUtil.TOKEN_TERMINATOR+"\\\n"+
						signedToken+"\\\n"+TokenUtil.TOKEN_TERMINATOR+"}";
		tars = TokenUtil.parseTokensFromFileContent(fileContent);
		assertEquals(1, tars.size());
		tar = tars.iterator().next();
		checkTokenAnalysisResult(userId, timestamp, arIds, tar);
	}

}
