package org.sagebionetworks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import org.junit.Test;

public class SMIMEValidatorTest {
	
	private MimeMessage readMimeMessageFromFile(String name) throws IOException, MessagingException {
		InputStream is = null;
		try {
			is = MessageUtilTest.class.getClassLoader().getResourceAsStream(name);
			return MessageUtil.readMessageFromInputStream(is);
		} finally {
			is.close();
		}
	}

	@Test
	public void testSMIMEValidator() throws Exception {
		MimeMessage message = readMimeMessageFromFile("sample_smime.txt");
		assertTrue(SMIMEValidator.isAValidSMIMESignedMessage(message));
	}

	@Test
	public void testSMIMEValidatorCorrupt() throws Exception {
		MimeMessage message = readMimeMessageFromFile("sample_smime.txt");
		// changing content of the message should cause validation to fail
		MimeMultipart mmp = (MimeMultipart)message.getContent();
		for (int i=0; i<mmp.getCount(); i++) {
			if (mmp.getBodyPart(i).getContentType().contains("multipart/related")) {
				mmp.getBodyPart(i).setText("some rogue content");
			}
		}
		assertFalse(SMIMEValidator.isAValidSMIMESignedMessage(message));
	}

	@Test
	public void testSMIMEValidatorNotSigned() throws Exception {
		MimeMessage message = readMimeMessageFromFile("sample_mime.txt");
		assertFalse(SMIMEValidator.isAValidSMIMESignedMessage(message));
	}

}
