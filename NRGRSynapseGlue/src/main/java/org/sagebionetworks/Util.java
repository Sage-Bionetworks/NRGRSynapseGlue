package org.sagebionetworks;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.SynapseClientImpl;
import org.sagebionetworks.client.SynapseProfileProxy;

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

	/*
	 * return a map whose key is signup team ID and value is the collection of settings for that team
	 */
	public static Map<String,DatasetSettings> getDatasetSettings() {
		return parseDatasetSetting(getProperty("SETTINGS"));
	}
		
	
	/*
	 * return a map whose key is signup team ID and value is the collection of settings for that team
	 */
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
	
	public static SynapseClient createSynapseClient() {
		SynapseClientImpl scIntern = new SynapseClientImpl();
		scIntern.setAuthEndpoint("https://repo-prod.prod.sagebase.org/auth/v1");
		scIntern.setRepositoryEndpoint("https://repo-prod.prod.sagebase.org/repo/v1");
		scIntern.setFileEndpoint("https://repo-prod.prod.sagebase.org/file/v1");
		return SynapseProfileProxy.createProfileProxy(scIntern);
	}
	
	public static Date cleanDate(Date d) {
		if (d==null) return d;
		if (d.getTime()==0L) return null;
		return d;
	}
	
	public static boolean datesEqualNoMillis(Date d1, Date d2) {
		if (d1==null && d2==null) return true;
		if (d1==null || d2==null) return false;
		long d1Sec = d1.getTime()/1000L;
		long d2Sec = d2.getTime()/1000L;
		return d1Sec==d2Sec;
	}


}
