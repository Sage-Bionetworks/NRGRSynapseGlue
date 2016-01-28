package org.sagebionetworks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;

import org.junit.Test;

public class OriginValidatorTest {
	
	private MimeMessage readMimeMessageFromFile(String name) throws IOException, MessagingException {
		InputStream is = null;
		try {
			is = OriginValidatorTest.class.getClassLoader().getResourceAsStream(name);
			return MessageUtil.readMessageFromInputStream(is);
		} finally {
			if (is!=null) is.close();
		}
	}
	
	private static final String NIH_SUBNET_CIDR = "156.40.0.0/16";

	@Test
	public void testOriginValidatorWithTwoAttachments() throws Exception {
		String name = "nimh_with_two_attachments_smime.txt";
		MimeMessage message = readMimeMessageFromFile(name);
		assertTrue(OriginValidator.isOriginatingIPInSubnet(message, NIH_SUBNET_CIDR));
	}

	@Test
	public void testOriginValidatorNIKKI() throws Exception {
		String name = "nikki_smime.txt";
		MimeMessage message = readMimeMessageFromFile(name);
		assertTrue(OriginValidator.isOriginatingIPInSubnet(message, NIH_SUBNET_CIDR));
	}

	@Test
	public void testOriginValidatorNoOriginatingIP() throws Exception {
		MimeMessage message = readMimeMessageFromFile("no_originating_ip_mime.txt");
		assertFalse(OriginValidator.isOriginatingIPInSubnet(message, NIH_SUBNET_CIDR));
	}

	@Test
	public void testOriginValidatorWrongOriginatingIP() throws Exception {
		MimeMessage message = readMimeMessageFromFile("wrong_originating_ip_mime.txt");
		assertFalse(OriginValidator.isOriginatingIPInSubnet(message, NIH_SUBNET_CIDR));
	}

}
