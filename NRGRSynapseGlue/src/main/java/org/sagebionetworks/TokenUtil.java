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
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.sagebionetworks.client.exceptions.SynapseException;

public class TokenUtil {
	public static final String PART_SEPARATOR = "|";
	private static final String REGEX_FOR_PART_SEPARATOR = "\\|";
	
	private static final String ACCESS_REQUIREMENT_ID_SEPARATOR = ",";
	
	private static final String HMAC_SHA1_ALGORITHM = "HmacSHA1";

	public static final String TOKEN_TERMINATOR = 
			"=============== SYNAPSE LINK TOKEN BOUNDARY ===============";
	
	// we made the token terminator generic but we have to recognize terminators
	// from tokens which may have been generated before the change.
	public static final String OLD_TOKEN_TERMINATOR = 
			"=============== SYNAPSE NRGR LINK TOKEN BOUNDARY ===============";

	private static final int V1_USER_ID_TOKEN_INDEX = 1;
	private static final int V1_AR_ID_TOKEN_INDEX = 2;
	private static final int V1_TIMESTAMP_TOKEN_INDEX = 3;
	private static final int V1_HMAC_TOKEN_INDEX = 4;

	private static final int V2_LABEL_TOKEN_INDEX = 1;
	private static final int V2_USER_ID_TOKEN_INDEX = 2;
	private static final int V2_APPLICATION_TEAM_ID_TOKEN_INDEX = 3;
	private static final int V2_AR_ID_TOKEN_INDEX = 4;
	private static final int V2_MR_EXPIRATION_TOKEN_INDEX = 5;
	private static final int V2_TIMESTAMP_TOKEN_INDEX = 6;
	private static final int V2_HMAC_TOKEN_INDEX = 7;
	
	private static final long MILLISEC_PER_DAY = 1000*3600*24L;

	private static List<Long> arIdsFromArString(String accessRequirementIdString) {
		List<Long> accessRequirementIds = new ArrayList<Long>();
		for (String arString : accessRequirementIdString.split(ACCESS_REQUIREMENT_ID_SEPARATOR)) {
				accessRequirementIds.add(Long.parseLong(arString.trim()));
		}
		return accessRequirementIds;
	}

	public static String createToken(String userId, long now, DatasetSettings settings, Long mrExpiration) {
		String unsignedToken = 
				createV2UnsignedToken(
						userId, 
						now,
						settings,
						mrExpiration);
		
		return "\n"+TOKEN_TERMINATOR+"\n"+
				unsignedToken+hmac(unsignedToken)+PART_SEPARATOR+
				"\n"+TOKEN_TERMINATOR+"\n";
	}

	// originally there were 3 fields (userId, ars, timestamp)
	public static String createV1UnsignedToken(String userId, List<Long> accessRequirementIds, String epoch) {
		StringBuilder sb = new StringBuilder();
		sb.append(PART_SEPARATOR+userId+PART_SEPARATOR);
		String arString = accessRequirementIds.toString();
		arString = arString.substring(1,arString.length()-1);
		sb.append(arString+PART_SEPARATOR);
		sb.append(epoch+PART_SEPARATOR);
		return sb.toString();
	}
	
	// now there are 6 fields (label, userId, applicationTeamId, ars, mrExpiration, timestamp)
	public static String createV2UnsignedToken(String userId, long now, DatasetSettings settings, Long mrExpiration) {
		StringBuilder sb = new StringBuilder();
		sb.append(PART_SEPARATOR);
		sb.append(settings.getTokenLabel());
		sb.append(PART_SEPARATOR);
		sb.append(userId);
		sb.append(PART_SEPARATOR);
		sb.append(settings.getApplicationTeamId());
		sb.append(PART_SEPARATOR);
		String arString = settings.getAccessRequirementIds().toString();
		arString = arString.substring(1,arString.length()-1);
		sb.append(arString);
		sb.append(PART_SEPARATOR);
		sb.append(mrExpiration);
		sb.append(PART_SEPARATOR);
		sb.append(now);
		sb.append(PART_SEPARATOR);
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
	
	/*
	 * The input could be either a serialized MimeMessage or just a plain file containing
	 * one or more tokens.
	 */
	public static Set<TokenAnalysisResult> parseTokensFromInput(byte[] in, Map<String,DatasetSettings> settings, MembershipRequestChecker mrc, long now) throws IOException, SynapseException {
		// first, just treat the input stream as a plain file
		Set<TokenAnalysisResult> tarList = new HashSet<TokenAnalysisResult>();
		tarList.addAll(parseTokensFromString(new String(in)));
		// now treat it as a serialized message
		// we may find the same tokens, or we may find ones not previously found
		// (e.g. if they were in an encoded attachment)
		// since the result is a Set, any repeats will be eliminated.
		try {
			MimeMessage message = MessageUtil.readMessageFromInputStream(new ByteArrayInputStream(in));
			tarList.addAll(parseTokensFromMessageContent(message.getContent()));
		} catch (MessagingException e) {
			// this is the case if the content is not a serialized message
		}
		
		Set<TokenAnalysisResult> result = new HashSet<TokenAnalysisResult>();
		
		// check for expired tokens
		for (TokenAnalysisResult tar : tarList) {
			if (tar.isValid()) {
				String applicationTeamId = tar.getTokenContent().getApplicationTeamId();
				DatasetSettings ds = settings.get(applicationTeamId);
				long tokenTimeoutMillis = ds.getTokenExpirationTimeDays()*MILLISEC_PER_DAY;
				TokenContent tc = tar.getTokenContent();
				if (tc.getTimestamp().getTime()+tokenTimeoutMillis<now) {
					result.add(createFailedTokenAnalysisResult(tar.getUserId(), "Message timestamp has expired. Applicant must reinitiate the approval process: "+tc));
				} else if (!mrc.doesMembershipRequestExist(tar.getTokenContent().getApplicationTeamId(), ""+tar.getTokenContent().getUserId())) {
					result.add(createFailedTokenAnalysisResult(tar.getUserId(), "Synapse is not excpecting approval token. Applicant must reinitiate the approval process: "+tc));
				} else {
					result.add(tar);
				}
			} else {
				result.add(tar);
			}
		}
		
		return result;
	}

	/*
	 * This function accommodates Multiparts within Multiparts by using recursion
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
		} else if (content instanceof MimeMessage) {
			MimeMessage mimeMessage = (MimeMessage) content;
			return parseTokensFromMessageContent(mimeMessage.getContent());
		} else {
			throw new RuntimeException("Unexpected content type "+content.getClass());
		}
	}

	private static Set<TokenAnalysisResult> parseTokensFromString(String messageContent) throws IOException {
		Set<TokenAnalysisResult> result = new HashSet<TokenAnalysisResult>();
		result.addAll(parseTokensFromString(messageContent, TOKEN_TERMINATOR));
		result.addAll(parseTokensFromString(messageContent, OLD_TOKEN_TERMINATOR));
		return result;
	}
		
	/*
	 * Since a message may include the same content multiple times (e.g. as plain text
	 * and html) we combine the extracted tokens in a Set to eliminate duplicates)
	 */
	private static Set<TokenAnalysisResult> parseTokensFromString(String messageContent, String tokenTerminator) throws IOException {
		Set<TokenAnalysisResult> result = new HashSet<TokenAnalysisResult>();
		int start = 0;
		int leadingTerminator = messageContent.indexOf(tokenTerminator, start);
		while (leadingTerminator>=0) {
			int tokenStart = leadingTerminator+tokenTerminator.length();
			int trailingTerminator = messageContent.indexOf(tokenTerminator, tokenStart);
			if (trailingTerminator<0) { 
				break;
			} else {
				String tokenSubstring = messageContent.substring(tokenStart, trailingTerminator).trim();
				// there was a case in which a line break was added to the HMAC. There shouldn't be line
				// breaks (or any other white space, so we just remove it altogether
				result.add(parseToken( tokenSubstring.replaceAll("\\s", "")));
				start = trailingTerminator+tokenTerminator.length();
			}
			leadingTerminator = messageContent.indexOf(tokenTerminator, start);
		} 
		return result;
	}
	
	private static TokenAnalysisResult createFailedTokenAnalysisResult(Long userId, String reason) {
		return new TokenAnalysisResult(null, false, userId, reason);
	}
	
	public static TokenAnalysisResult parseToken(String token) {
		String[] tokenParts = token.split(REGEX_FOR_PART_SEPARATOR);
		// handling of the token could put some garbage characters at the end of the string, 
		// increasing the token pieces from 5 to 6
		// 01/26/2016 update:  v1 tokens are 5 or 6 parts, v2 are 8 or 9 parts
		boolean isV1Token = tokenParts.length==5 || tokenParts.length==6; 
		boolean isV2Token = tokenParts.length==8 || tokenParts.length==9;
		if (!(isV1Token || isV2Token)) 
			return createFailedTokenAnalysisResult(null, 
				"Incorrectly formatted token found: "+Arrays.asList(tokenParts));
		if (isV1Token) {
			return parseV1Token(tokenParts);
		} else { // isV2Token
			return parseV2Token(tokenParts);
		}
	}
	
	// we pass in current time to facilitate testing
	private static TokenAnalysisResult parseV1Token(String[] tokenParts) {
		Long userId = null;
		try {
			userId = Long.parseLong(tokenParts[V1_USER_ID_TOKEN_INDEX].trim());
		} catch (NumberFormatException nfe) {
			return createFailedTokenAnalysisResult(null, 
					"Could not extract a number from <"+tokenParts[V1_USER_ID_TOKEN_INDEX].trim()+">");
		}
		String accessRequirementIdString = tokenParts[V1_AR_ID_TOKEN_INDEX].trim();
		String epochString = tokenParts[V1_TIMESTAMP_TOKEN_INDEX].trim();
		String hmac = tokenParts[V1_HMAC_TOKEN_INDEX].trim();
		long epoch;
		try {
			epoch = Long.parseLong(epochString);
		} catch (NumberFormatException e) {
			return createFailedTokenAnalysisResult(userId, "Illegal time stamp in token. "+Arrays.asList(tokenParts));
		}
		List<Long> accessRequirementIds = null;
		try {
			accessRequirementIds = arIdsFromArString(accessRequirementIdString);
		} catch (NumberFormatException e) {
			return createFailedTokenAnalysisResult(userId, "Bad Access Requirement ID list in token: "+accessRequirementIdString);
		}
		String recomputedHmac = hmac(
				createV1UnsignedToken(
						""+userId, 
						accessRequirementIds, 
						epochString));
		if (!hmac.equals(recomputedHmac)) return createFailedTokenAnalysisResult(userId, "Message has an invalid digital signature. "+Arrays.asList(tokenParts));
		
		return new TokenAnalysisResult(new TokenContent(userId, accessRequirementIds, new Date(epoch), 
				null, getProperty("ORIGINAL_APPLICATION_TEAM_ID"), null), true, userId, null);
	}
	
	// we pass in current time to facilitate testing
	private static TokenAnalysisResult parseV2Token(String[] tokenParts) {
		Long userId = null;
		try {
			userId = Long.parseLong(tokenParts[V2_USER_ID_TOKEN_INDEX].trim());
		} catch (NumberFormatException nfe) {
			return createFailedTokenAnalysisResult(null, 
					"Could not extract a number from <"+tokenParts[V2_USER_ID_TOKEN_INDEX].trim()+">");
		}
		DatasetSettings settings = new DatasetSettings();
		settings.setApplicationTeamId(tokenParts[V2_APPLICATION_TEAM_ID_TOKEN_INDEX].trim());
		settings.setTokenLabel(tokenParts[V2_LABEL_TOKEN_INDEX].trim());
		String accessRequirementIdString = tokenParts[V2_AR_ID_TOKEN_INDEX].trim();
		String epochString = tokenParts[V2_TIMESTAMP_TOKEN_INDEX].trim();
		String mrExpirationString = tokenParts[V2_MR_EXPIRATION_TOKEN_INDEX].trim();
		String hmac = tokenParts[V2_HMAC_TOKEN_INDEX].trim();
		long epoch;
		try {
			epoch = Long.parseLong(epochString);
		} catch (NumberFormatException e) {
			return createFailedTokenAnalysisResult(userId, "Illegal time stamp in token. "+Arrays.asList(tokenParts));
		}

		List<Long> accessRequirementIds = null;
		try {
			accessRequirementIds = arIdsFromArString(accessRequirementIdString);
		} catch (NumberFormatException e) {
			return createFailedTokenAnalysisResult(userId, "Bad Access Requirement ID list: "+accessRequirementIdString);
		}
		settings.setAccessRequirementIds(accessRequirementIds);
		Long mrExpiration;
		try {
			mrExpiration = mrExpirationString==null||mrExpirationString.equalsIgnoreCase("null") ? null : Long.parseLong(mrExpirationString);
		} catch (NumberFormatException e) {
			return createFailedTokenAnalysisResult(userId, "Illegal membership request time stamp in token: "+mrExpirationString);
		}
		String recomputedHmac = hmac(
				createV2UnsignedToken(
						""+userId, 
						Long.parseLong(epochString), 
						settings, 
						mrExpiration));
		if (!hmac.equals(recomputedHmac)) return createFailedTokenAnalysisResult(userId, "Token has an invalid digital signature. "+Arrays.asList(tokenParts));
		TokenContent tokenContent = new TokenContent(userId, accessRequirementIds, new Date(epoch), 
				settings.getTokenLabel(), settings.getApplicationTeamId(), mrExpiration==null?null:new Date(mrExpiration));
		return new TokenAnalysisResult(tokenContent, true, userId, null);
	}
	
}
