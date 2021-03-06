package org.sagebionetworks;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;

public class Util {
	private static Properties properties = null;

	private static void initProperties() {
		if (properties!=null) return;
		properties = new Properties();
		InputStream is = null;
		try {
			is = NRGRSynapseGlue.class.getClassLoader().getResourceAsStream("global.properties");
			if (is!=null) properties.load(is);
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
		
	public static String getProperty(String key, boolean missingOK) {
		initProperties();
		String commandlineOption = System.getProperty(key);
		if (!StringUtils.isEmpty(commandlineOption)) return commandlineOption;
		String embeddedProperty = properties.getProperty(key);
		if (!StringUtils.isEmpty(embeddedProperty)) return embeddedProperty;
		String envVar = System.getenv(key);
		if (!StringUtils.isEmpty(envVar)) return envVar;
		if (missingOK) return null; else throw new RuntimeException("Cannot find value for "+key);
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
