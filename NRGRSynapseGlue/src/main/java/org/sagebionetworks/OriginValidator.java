package org.sagebionetworks;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;

import org.apache.commons.net.util.SubnetUtils;


public class OriginValidator {

	private static final String X_ORIGINATING_IP = "X-Originating-IP";
	
	public static boolean isOriginatingIPInSubnet(MimeMessage msg, String subnetCidr) {
		try {
			SubnetUtils subnetUtils = new SubnetUtils(subnetCidr);
			String[] headers = msg.getHeader(X_ORIGINATING_IP);
			if (headers==null) return false;
			for (String originatingIP : headers) {
				if (originatingIP.startsWith("[")) originatingIP=originatingIP.substring(1);
				if (originatingIP.endsWith("]")) originatingIP=originatingIP.substring(0, originatingIP.length()-1);
				if (subnetUtils.getInfo().isInRange(originatingIP)) return true;
			}
			return false;
		} catch (MessagingException e) {
			throw new RuntimeException(e);
		}
	}

}
