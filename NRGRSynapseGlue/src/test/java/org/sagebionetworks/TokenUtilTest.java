package org.sagebionetworks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.IOUtils;
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
		Set<TokenAnalysisResult> tars = TokenUtil.parseTokensFromInput(token.getBytes());
		assertEquals(1, tars.size());
		TokenAnalysisResult tar = tars.iterator().next();
		assertTrue(tar.isValid());
		assertEquals(userId, tar.getUserId());
		assertNull(tar.getReason());
		TokenContent tc = tar.getTokenContent();
		assertEquals(userId.longValue(), tc.getUserId());
	}

	@Test
	public void testParseTokenFromMessageContent() throws Exception {
		Long userId = new Long(273995L);
		Long timestamp = System.currentTimeMillis();
		List<Long> arIds = Arrays.asList(new Long[]{111111L, 222222L, 333333L});
		String unsignedToken = TokenUtil.createUnsignedToken(""+userId, arIds, ""+timestamp);
		String signedToken = unsignedToken+TokenUtil.hmac(unsignedToken)+"|";
		String fileContent= TokenUtil.TOKEN_TERMINATOR+"\\\n"+
				signedToken+"\n"+TokenUtil.TOKEN_TERMINATOR+"}";
		Set<TokenAnalysisResult> tars = TokenUtil.
				parseTokensFromInput(fileContent.getBytes());
		assertEquals(1, tars.size());
		TokenAnalysisResult tar = tars.iterator().next();
		checkTokenAnalysisResult(userId, timestamp, arIds, tar);

		fileContent = 
				TokenUtil.TOKEN_TERMINATOR+"\\\n"+
						signedToken+"\\\n"+TokenUtil.TOKEN_TERMINATOR+"}";
		tars = TokenUtil.
				parseTokensFromInput(fileContent.getBytes());
		assertEquals(1, tars.size());
		tar = tars.iterator().next();
		checkTokenAnalysisResult(userId, timestamp, arIds, tar);
	}
	
	@Test
	public void testParseTokenFromInlineMessage() throws Exception {
		InputStream is = null;
		try {
			is = MessageUtilTest.class.getClassLoader().getResourceAsStream("mimeWithTokenInLine.txt");
			Set<TokenAnalysisResult> tars = TokenUtil.parseTokensFromInput(IOUtils.toByteArray(is));
			assertEquals(1, tars.size());
			TokenAnalysisResult tar = tars.iterator().next();
			assertTrue(tar.isValid());
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
			Set<TokenAnalysisResult> tars = TokenUtil.parseTokensFromInput(IOUtils.toByteArray(is));
			assertEquals(1, tars.size());
			TokenAnalysisResult tar = tars.iterator().next();
			assertTrue(tar.isValid());
			assertEquals(new Long(273960L), tar.getUserId());
			TokenContent tc = tar.getTokenContent();
			assertEquals(273960L, tc.getUserId());
			assertEquals(new Date(1425959448905L), tc.getTimestamp());
			assertEquals(Collections.singletonList(3248760L), tc.getAccessRequirementIds());
		} finally {
			is.close();
		}

	}

}
