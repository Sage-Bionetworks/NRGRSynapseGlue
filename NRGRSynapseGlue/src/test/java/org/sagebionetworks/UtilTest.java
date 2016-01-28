package org.sagebionetworks;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import java.util.Map;

import org.junit.Test;

public class UtilTest {

	@Test
	public void testDatasetSettings() {
		String s = "[{\"applicationTeamId\":\"3324934\",\"accessRequirementIds\":[\"3345489\"],\"tokenLabel\":\"COMMON_MIND\",\"tokenEmailSynapseId\":\"syn4597308\",\"approvalEmailSynapseId\":\"syn4597309\",\"dataDescriptor\":\"CommonMind\"}]";
		Map<String, DatasetSettings> settings = Util.parseDatasetSetting(s);
		assertEquals(1, settings.size());
		assertEquals("3324934", settings.keySet().iterator().next());
		DatasetSettings value = settings.values().iterator().next();
		assertEquals(Collections.singletonList(3345489L), value.getAccessRequirementIds());
		assertEquals("3324934", value.getApplicationTeamId());
		assertEquals("CommonMind", value.getDataDescriptor());
		assertEquals("syn4597308", value.getTokenEmailSynapseId());
		assertEquals("COMMON_MIND", value.getTokenLabel());
		assertEquals("syn4597309", value.getApprovalEmailSynapseId());
	}

}
