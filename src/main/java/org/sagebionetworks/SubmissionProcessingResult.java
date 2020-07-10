package org.sagebionetworks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.sagebionetworks.evaluation.model.SubmissionStatus;

public class SubmissionProcessingResult {
	private List<SubmissionStatus> processedSubmissions;
	private List<MimeMessageAndReason> messagesToSender;
	private Set<TokenContent> validTokens;

	public SubmissionProcessingResult() {
		processedSubmissions = new ArrayList<SubmissionStatus>();
		messagesToSender = new ArrayList<MimeMessageAndReason>();
		validTokens = new HashSet<TokenContent>();
	}

	public List<SubmissionStatus> getProcessedSubmissions() {
		return processedSubmissions;
	}

	public void setProcessedSubmissions(List<SubmissionStatus> processedSubmissions) {
		this.processedSubmissions = processedSubmissions;
	}

	public void addProcessedSubmission(SubmissionStatus toAdd) {
		this.processedSubmissions.add(toAdd);
	}

	public List<MimeMessageAndReason> getMessagesToSender() {
		return messagesToSender;
	}

	public void setMessagesToSender(List<MimeMessageAndReason> messagesToSender) {
		this.messagesToSender = messagesToSender;
	}

	public void addMessageToSender(MimeMessageAndReason toAdd) {
		this.messagesToSender.add(toAdd);
	}

	public Set<TokenContent> getValidTokens() {
		return validTokens;
	}

	public void setValidTokens(Set<TokenContent> validTokens) {
		this.validTokens = validTokens;
	}
	
	public void addValidToken(TokenContent validToken) {
		this.validTokens.add(validToken);
	}

}
