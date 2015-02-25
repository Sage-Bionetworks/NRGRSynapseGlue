package org.sagebionetworks;

public class UserLinkedException extends IllegalArgumentException {
	private String userId = null;
	
	public String getUserId() {return userId;}

	public UserLinkedException(String userId) {
		this.userId=userId;
	}

	public UserLinkedException(String userId, String s) {
		super(s);
		this.userId=userId;
	}

	public UserLinkedException(String userId, Throwable cause) {
		super(cause);
		this.userId=userId;
	}

	public UserLinkedException(String userId, String message, Throwable cause) {
		super(message, cause);
		this.userId=userId;
	}

}
