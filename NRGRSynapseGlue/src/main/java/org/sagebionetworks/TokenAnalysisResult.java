package org.sagebionetworks;

public class TokenAnalysisResult {
	// the content of the analyzed token or null if impossible to analyze
	private TokenContent tokenContent;
	// true iff the token is valid
	private boolean isValid;
	// the userId, if possible to discern from the token
	private Long userId;
	// if invalid, the reason why
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
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (isValid ? 1231 : 1237);
		result = prime * result + ((reason == null) ? 0 : reason.hashCode());
		result = prime * result
				+ ((tokenContent == null) ? 0 : tokenContent.hashCode());
		result = prime * result + ((userId == null) ? 0 : userId.hashCode());
		return result;
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		TokenAnalysisResult other = (TokenAnalysisResult) obj;
		if (isValid != other.isValid)
			return false;
		if (reason == null) {
			if (other.reason != null)
				return false;
		} else if (!reason.equals(other.reason))
			return false;
		if (tokenContent == null) {
			if (other.tokenContent != null)
				return false;
		} else if (!tokenContent.equals(other.tokenContent))
			return false;
		if (userId == null) {
			if (other.userId != null)
				return false;
		} else if (!userId.equals(other.userId))
			return false;
		return true;
	}
	@Override
	public String toString() {
		return "TokenAnalysisResult [tokenContent=" + tokenContent
				+ ", isValid=" + isValid + ", userId=" + userId + ", reason="
				+ reason + "]";
	}
	
	

}
