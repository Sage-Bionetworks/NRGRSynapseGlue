package org.sagebionetworks;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

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



}
