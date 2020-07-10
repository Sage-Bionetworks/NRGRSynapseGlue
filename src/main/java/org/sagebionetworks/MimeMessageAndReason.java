package org.sagebionetworks;

import javax.mail.internet.MimeMessage;

public class MimeMessageAndReason {
	private MimeMessage mimeMessage;
	private String reason;
	public MimeMessageAndReason(MimeMessage mimeMessage, String reason) {
		super();
		this.mimeMessage = mimeMessage;
		this.reason = reason;
	}
	public MimeMessage getMimeMessage() {
		return mimeMessage;
	}
	public void setMimeMessage(MimeMessage mimeMessage) {
		this.mimeMessage = mimeMessage;
	}
	public String getReason() {
		return reason;
	}
	public void setReason(String reason) {
		this.reason = reason;
	}


}
