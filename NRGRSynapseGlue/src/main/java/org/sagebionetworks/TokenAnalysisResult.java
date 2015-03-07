package org.sagebionetworks;

public class TokenAnalysisResult {
	// the content of the analysed token or null if impossible to analyse
	private TokenContent tokenContent;
	// true iff the token is valid
	private boolean isValid;
	// the userId, if possible to discern from the token
	private Long userId;
	// if invalid, the reason so
	private String reason;
	
	public TokenAnalysisResult(TokenContent tokenContent, boolean isValid, Long userId,
			String reason) {
		this.tokenContent = tokenContent;
		this.isValid = isValid;
		this.userId = userId;
		this.reason = reason;
	}
	public TokenContent getTokenContent() {
		return tokenContent;
	}
	public boolean isValid() {
		return isValid;
	}
	public String getReason() {
		return reason;
	}
	public Long getUserId() {
		return userId;
	}

}
