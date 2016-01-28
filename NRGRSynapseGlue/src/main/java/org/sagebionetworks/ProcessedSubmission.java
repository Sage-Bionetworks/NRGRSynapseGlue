package org.sagebionetworks;

import org.sagebionetworks.evaluation.model.SubmissionStatus;

public class ProcessedSubmission {
	private SubmissionStatus status; // status, updated based on result of processing
	private MimeMessageAndReason mmr; // if message is invalid this contains a copy of the message and the reason it was rejected


	public ProcessedSubmission() {
	}


	public SubmissionStatus getStatus() {
		return status;
	}


	public void setStatus(SubmissionStatus status) {
		this.status = status;
	}


	public MimeMessageAndReason getMmr() {
		return mmr;
	}


	public void setMmr(MimeMessageAndReason mmr) {
		this.mmr = mmr;
	}
	
	

}
