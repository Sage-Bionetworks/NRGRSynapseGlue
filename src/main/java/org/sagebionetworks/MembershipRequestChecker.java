package org.sagebionetworks;

import org.sagebionetworks.client.exceptions.SynapseException;

public interface MembershipRequestChecker {
	public boolean doesMembershipRequestExist(String teamId, String userId) throws SynapseException;

}
