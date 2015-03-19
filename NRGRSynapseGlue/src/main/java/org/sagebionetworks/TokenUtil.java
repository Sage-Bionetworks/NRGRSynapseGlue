package org.sagebionetworks;

import static org.sagebionetworks.Util.getProperty;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;

public class TokenUtil {
	private static final String PART_SEPARATOR = "|";
	private static final String REGEX_FOR_PART_SEPARATOR = "\\|";
	
	private static final String ACCESS_REQUIREMENT_ID_SEPARATOR = ",";
	
	private static final String HMAC_SHA1_ALGORITHM = "HmacSHA1";

	public static final String TOKEN_TERMINATOR = 
			"=============== SYNAPSE NRGR LINK TOKEN BOUNDARY ===============";

	private static List<Long> arIdsFromArString(String accessRequirementIdString) {
		List<Long> accessRequirementIds = new ArrayList<Long>();
		for (String arString : accessRequirementIdString.split(ACCESS_REQUIREMENT_ID_SEPARATOR)) {
				accessRequirementIds.add(Long.parseLong(arString.trim()));
		}
		return accessRequirementIds;
	}

	public static String createToken(String userId) {
		String arString = getProperty("ACCESS_REQUIREMENT_IDS");
		List<Long> ars = arIdsFromArString(arString);
		String unsignedToken = 
				createUnsignedToken(
						userId, 
						ars, 
						""+System.currentTimeMillis());
		
		return "\n"+TOKEN_TERMINATOR+"\n"+
				unsignedToken+hmac(unsignedToken)+PART_SEPARATOR+
				"\n"+TOKEN_TERMINATOR+"\n";
	}

	public static String createUnsignedToken(String userId, List<Long> accessRequirementIds, String epoch) {
		StringBuilder sb = new StringBuilder();
		sb.append(PART_SEPARATOR+userId+PART_SEPARATOR);
		String arString = accessRequirementIds.toString();
		arString = arString.substring(1,arString.length()-1);
		sb.append(arString+PART_SEPARATOR);
		sb.append(epoch+PART_SEPARATOR);
		return sb.toString();
	}

	public static String hmac(String s) {
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
	
	private static final int USER_ID_TOKEN_INDEX = 1;
	private static final int AR_ID_TOKEN_INDEX = 2;
	private static final int TIMESTAMP_TOKEN_INDEX = 3;
	private static final int HMAC_TOKEN_INDEX = 4;

	/*
	 * The input could be either a serialized MimeMessage or just a plain file containing
	 * one or more tokens.
	 */
	public static Set<TokenAnalysisResult> parseTokensFromInput(byte[] in) throws IOException {
		// first, just treat the input stream as a plain file
		Set<TokenAnalysisResult> result = new HashSet<TokenAnalysisResult>();
		result.addAll(parseTokensFromString(new String(in)));
		// now treat it as a serialized message
		// we may find the same tokens, or we may find ones not previously found
		// (e.g. if they were in an encoded attachment)
		// since the result is a Set, any repeats will be eliminated.
		try {
			MimeMessage message = MessageUtil.readMessageFromInputStream(new ByteArrayInputStream(in));
			result.addAll(parseTokensFromMessageContent(message.getContent()));
		} catch (MessagingException e) {
			// this is the case if the content is not a serialized message
		}
		return result;
	}

	/*
	 * This function accomodates Multiparts within Multiparts by using recursion
	 */
	private static Set<TokenAnalysisResult> parseTokensFromMessageContent(Object content) throws IOException, MessagingException {
		if (content instanceof String) {
			return parseTokensFromString((String)content);
		} else if (content instanceof InputStream) {
			InputStream is = (InputStream)content;
			String s = new String(IOUtils.toByteArray(is));
			return parseTokensFromString(s);
		} else if (content instanceof MimeMultipart) {
			Set<TokenAnalysisResult> result = new HashSet<TokenAnalysisResult>();
			MimeMultipart mmp = (MimeMultipart) content;
			for (int i=0; i<mmp.getCount(); i++) {
				BodyPart bodyPart = mmp.getBodyPart(i);
				result.addAll(parseTokensFromMessageContent(bodyPart.getContent()));
			}
			return result;
		} else {
			throw new RuntimeException("Unexpected content type "+content.getClass());
		}
	}

	/*
	 * Since a message may include the same content multiple times (e.g. as plain text
	 * and html) we combine the extracted tokens in a Set to eliminate duplicates)
	 */
	private static Set<TokenAnalysisResult> parseTokensFromString(String messageContent) throws IOException {
		Set<TokenAnalysisResult> result = new HashSet<TokenAnalysisResult>();
		int start = 0;
		int leadingTerminator = messageContent.indexOf(TOKEN_TERMINATOR, start);
		while (leadingTerminator>=0) {
			int tokenStart = leadingTerminator+TOKEN_TERMINATOR.length();
			int trailingTerminator = messageContent.indexOf(TOKEN_TERMINATOR, tokenStart);
			if (trailingTerminator<0) { 
				break;
			} else {
				result.add(parseToken( messageContent.substring(tokenStart, trailingTerminator).trim()));
				start = trailingTerminator+TOKEN_TERMINATOR.length();
			}
			leadingTerminator = messageContent.indexOf(TOKEN_TERMINATOR, start);
		} 
		return result;
	}
	
	private static TokenAnalysisResult createFailedTokenAnalysisResult(Long userId, String reason) {
		return new TokenAnalysisResult(null, false, userId, reason);
	}
	
	private static TokenAnalysisResult parseToken(String token) {
		String[] tokenParts = token.split(REGEX_FOR_PART_SEPARATOR);
		// handling of the token could put some garbage characters at the end of the string, 
		// increasing the token pieces from 5 to 6
		if (!(tokenParts.length==5 || tokenParts.length==6)) 
			return createFailedTokenAnalysisResult(null, 
				"Token should contain five or six parts, but "+tokenParts.length+" found: "+
				Arrays.asList(tokenParts));
		Long userId = null;
		try {
			userId = Long.parseLong(tokenParts[USER_ID_TOKEN_INDEX].trim());
		} catch (NumberFormatException nfe) {
			return createFailedTokenAnalysisResult(null, 
					"Could not extract a number from <"+tokenParts[USER_ID_TOKEN_INDEX].trim()+">");
		}
		String accessRequirementIdString = tokenParts[AR_ID_TOKEN_INDEX].trim();
		String epochString = tokenParts[TIMESTAMP_TOKEN_INDEX].trim();
		String hmac = tokenParts[HMAC_TOKEN_INDEX].trim();
		long epoch;
		try {
			epoch = Long.parseLong(epochString);
		} catch (NumberFormatException e) {
			return createFailedTokenAnalysisResult(userId, "Illegal time stamp in message.");
		}
		long tokenTimeout = Long.parseLong(getProperty("TOKEN_EXPIRATION_TIME_MILLIS"));
		if (epoch+tokenTimeout<System.currentTimeMillis())
			return createFailedTokenAnalysisResult(userId, "Message timestamp has expired. Please contact the Synapse Access and Compliance Team, act@sagebase.org ");

		List<Long> accessRequirementIds = null;
		try {
			accessRequirementIds = arIdsFromArString(accessRequirementIdString);
		} catch (NumberFormatException e) {
			return createFailedTokenAnalysisResult(userId, "Bad Access Requirement ID list: "+accessRequirementIdString);
		}
		String recomputedHmac = hmac(createUnsignedToken(""+userId, accessRequirementIds, epochString));
		if (!hmac.equals(recomputedHmac)) return createFailedTokenAnalysisResult(userId, "Message has an invalid digital signature.");
		return new TokenAnalysisResult(new TokenContent(userId, accessRequirementIds, new Date(epoch)), true, userId, null);
	}
	
}
