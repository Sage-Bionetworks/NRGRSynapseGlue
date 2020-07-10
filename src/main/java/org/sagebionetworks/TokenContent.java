package org.sagebionetworks;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class TokenContent {
    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("MMM/dd/yyyy");
	private long userId;
	private List<Long> accessRequirementIds;
	private Date timestamp;
	private String tokenLabel;
	private String applicationTeamId;
	private Date membershipRequestExpiration;
	
	public TokenContent(long userId, List<Long> accessRequirementIds,
			Date timestamp, String tokenLabel, String applicationTeamId,
			Date membershipRequestExpiration) {
		super();
		this.userId = userId;
		this.accessRequirementIds = accessRequirementIds;
		this.timestamp = timestamp;
		this.tokenLabel = tokenLabel;
		this.applicationTeamId = applicationTeamId;
		this.membershipRequestExpiration = membershipRequestExpiration;
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

	public String getTokenLabel() {
		return tokenLabel;
	}

	public void setTokenLabel(String tokenLabel) {
		this.tokenLabel = tokenLabel;
	}

	public String getApplicationTeamId() {
		return applicationTeamId;
	}

	public void setApplicationTeamId(String applicationTeamId) {
		this.applicationTeamId = applicationTeamId;
	}

	public Date getMembershipRequestExpiration() {
		return membershipRequestExpiration;
	}

	public void setMembershipRequestExpiration(Date membershipRequestExpiration) {
		this.membershipRequestExpiration = membershipRequestExpiration;
	}

	public void setUserId(long userId) {
		this.userId = userId;
	}

	public void setAccessRequirementIds(List<Long> accessRequirementIds) {
		this.accessRequirementIds = accessRequirementIds;
	}

	public void setTimestamp(Date timestamp) {
		this.timestamp = timestamp;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime
				* result
				+ ((accessRequirementIds == null) ? 0 : accessRequirementIds
						.hashCode());
		result = prime
				* result
				+ ((applicationTeamId == null) ? 0 : applicationTeamId
						.hashCode());
		result = prime
				* result
				+ ((membershipRequestExpiration == null) ? 0
						: membershipRequestExpiration.hashCode());
		result = prime * result
				+ ((timestamp == null) ? 0 : timestamp.hashCode());
		result = prime * result
				+ ((tokenLabel == null) ? 0 : tokenLabel.hashCode());
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
		if (applicationTeamId == null) {
			if (other.applicationTeamId != null)
				return false;
		} else if (!applicationTeamId.equals(other.applicationTeamId))
			return false;
		if (membershipRequestExpiration == null) {
			if (other.membershipRequestExpiration != null)
				return false;
		} else if (!membershipRequestExpiration
				.equals(other.membershipRequestExpiration))
			return false;
		if (timestamp == null) {
			if (other.timestamp != null)
				return false;
		} else if (!timestamp.equals(other.timestamp))
			return false;
		if (tokenLabel == null) {
			if (other.tokenLabel != null)
				return false;
		} else if (!tokenLabel.equals(other.tokenLabel))
			return false;
		if (userId != other.userId)
			return false;
		return true;
	}

	@Override
	public String toString() {
		return "tokenLabel=" + tokenLabel
				+ ", userId=" + userId 
				+ ", applicationTeamId="+ applicationTeamId
				+", accessRequirementIds="+ accessRequirementIds
				+ ", expiration="+ DATE_FORMAT.format(membershipRequestExpiration)
				+ ", createdOn=" + DATE_FORMAT.format(timestamp);
	}
	
	
	
}
