package org.sagebionetworks;

import java.util.List;

public class DatasetSettings {
	private String applicationTeamId;
	private List<Long> accessRequirementIds;
	private String tokenLabel;
	private String tokenEmailSynapseId;
	private String approvalEmailSynapseId;
	private String dataDescriptor;
	
	public String getApplicationTeamId() {
		return applicationTeamId;
	}
	public void setApplicationTeamId(String applicationTeamId) {
		this.applicationTeamId = applicationTeamId;
	}
	public List<Long> getAccessRequirementIds() {
		return accessRequirementIds;
	}
	public void setAccessRequirementIds(List<Long> accessRequirementIds) {
		this.accessRequirementIds = accessRequirementIds;
	}
	public String getTokenLabel() {
		return tokenLabel;
	}
	public void setTokenLabel(String tokenLabel) {
		this.tokenLabel = tokenLabel;
	}
	public String getTokenEmailSynapseId() {
		return tokenEmailSynapseId;
	}
	public void setTokenEmailSynapseId(String tokenEmailSynapseId) {
		this.tokenEmailSynapseId = tokenEmailSynapseId;
	}
	public String getApprovalEmailSynapseId() {
		return approvalEmailSynapseId;
	}
	public void setApprovalEmailSynapseId(String approvalEmailSynapseId) {
		this.approvalEmailSynapseId = approvalEmailSynapseId;
	}
	public String getDataDescriptor() {
		return dataDescriptor;
	}
	public void setDataDescriptor(String dataDescriptor) {
		this.dataDescriptor = dataDescriptor;
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
				+ ((approvalEmailSynapseId == null) ? 0
						: approvalEmailSynapseId.hashCode());
		result = prime * result
				+ ((dataDescriptor == null) ? 0 : dataDescriptor.hashCode());
		result = prime
				* result
				+ ((tokenEmailSynapseId == null) ? 0 : tokenEmailSynapseId
						.hashCode());
		result = prime * result
				+ ((tokenLabel == null) ? 0 : tokenLabel.hashCode());
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
		DatasetSettings other = (DatasetSettings) obj;
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
		if (approvalEmailSynapseId == null) {
			if (other.approvalEmailSynapseId != null)
				return false;
		} else if (!approvalEmailSynapseId.equals(other.approvalEmailSynapseId))
			return false;
		if (dataDescriptor == null) {
			if (other.dataDescriptor != null)
				return false;
		} else if (!dataDescriptor.equals(other.dataDescriptor))
			return false;
		if (tokenEmailSynapseId == null) {
			if (other.tokenEmailSynapseId != null)
				return false;
		} else if (!tokenEmailSynapseId.equals(other.tokenEmailSynapseId))
			return false;
		if (tokenLabel == null) {
			if (other.tokenLabel != null)
				return false;
		} else if (!tokenLabel.equals(other.tokenLabel))
			return false;
		return true;
	}
	@Override
	public String toString() {
		return "DatasetSettings [applicationTeamId=" + applicationTeamId
				+ ", accessRequirementIds=" + accessRequirementIds
				+ ", tokenLabel=" + tokenLabel + ", tokenEmailSynapseId="
				+ tokenEmailSynapseId + ", approvalEmailSynapseId="
				+ approvalEmailSynapseId + ", dataDescriptor=" + dataDescriptor
				+ "]";
	}




}