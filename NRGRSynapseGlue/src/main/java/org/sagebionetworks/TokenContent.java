package org.sagebionetworks;

import java.util.Date;
import java.util.List;

public class TokenContent {
	private long userId;
	private List<Long> accessRequirementIds;
	private Date timestamp;
	
	public TokenContent(long userId, List<Long> accessRequirementIds,
			Date timestamp) {
		super();
		this.userId = userId;
		this.accessRequirementIds = accessRequirementIds;
		this.timestamp = timestamp;
	}
	
	public long getUserId() {
		return userId;
	}
	
	public List<Long> getAccessRequirementIds() {
		return accessRequirementIds;
	}
	
	public Date getTimestamp() {
		return timestamp;
	}
	
}
