package org.sagebionetworks;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

public class Util {
	private static Properties properties = null;

	
	private static void initProperties() {
		if (properties!=null) return;
		properties = new Properties();
		InputStream is = null;
		try {
			is = NRGRSynapseGlue.class.getClassLoader().getResourceAsStream("global.properties");
			properties.load(is);
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			if (is!=null) try {
				is.close();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public static String getProperty(String key) {
		return getProperty(key, false);
	}
		
	public static String getProperty(String key, boolean missingIsOK) {
		initProperties();
		String commandlineOption = System.getProperty(key);
		if (commandlineOption!=null) return commandlineOption;
		String embeddedProperty = properties.getProperty(key);
		if (embeddedProperty!=null) return embeddedProperty;
		if (!missingIsOK) throw new RuntimeException("Cannot find value for "+key);
		return null;
	}	

	public static Map<String,DatasetSettings> getDatasetSettings() {
		return parseDatasetSetting(getProperty("SETTINGS"));
	}
		public static Map<String,DatasetSettings> parseDatasetSetting(String s) {
		JSONArray a = (JSONArray)JSONValue.parse(s);
		Map<String,DatasetSettings> result = new HashMap<String,DatasetSettings>();
		for (Object elem : a) {
			JSONObject o = (JSONObject)elem;
			DatasetSettings ds = new DatasetSettings();
			String teamId = (String)o.get("applicationTeamId");
			ds.setApplicationTeamId(teamId);
			ds.setApprovalEmailSynapseId((String)o.get("approvalEmailSynapseId"));
			ds.setDataDescriptor((String)o.get("dataDescriptor"));
			ds.setTokenEmailSynapseId((String)o.get("tokenEmailSynapseId"));
			ds.setTokenLabel((String)o.get("tokenLabel"));
			List<Long> requirementIds = new ArrayList<Long>();
			for (Object listElem : (JSONArray)o.get("accessRequirementIds")) {
				requirementIds.add(Long.parseLong((String)listElem));
			}
			ds.setAccessRequirementIds(requirementIds);
			result.put(teamId, ds);
		}
		return result;
	}

}
