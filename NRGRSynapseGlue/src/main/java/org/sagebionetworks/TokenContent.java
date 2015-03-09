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

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime
				* result
				+ ((accessRequirementIds == null) ? 0 : accessRequirementIds
						.hashCode());
		result = prime * result
				+ ((timestamp == null) ? 0 : timestamp.hashCode());
		result = prime * result + (int) (userId ^ (userId >>> 32));
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
		TokenContent other = (TokenContent) obj;
		if (accessRequirementIds == null) {
			if (other.accessRequirementIds != null)
				return false;
		} else if (!accessRequirementIds.equals(other.accessRequirementIds))
			return false;
		if (timestamp == null) {
			if (other.timestamp != null)
				return false;
		} else if (!timestamp.equals(other.timestamp))
			return false;
		if (userId != other.userId)
			return false;
		return true;
	}

	@Override
	public String toString() {
		return "TokenContent [userId=" + userId + ", accessRequirementIds="
				+ accessRequirementIds + ", timestamp=" + timestamp + "]";
	}
	
	
	
}
