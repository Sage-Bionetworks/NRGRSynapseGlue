package org.sagebionetworks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;

import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import org.junit.Ignore;
import org.junit.Test;

public class SMIMEValidatorTest {
	
	private MimeMessage readMimeMessageFromFile(String name) throws IOException, MessagingException {
		InputStream is = null;
		try {
			is = SMIMEValidatorTest.class.getClassLoader().getResourceAsStream(name);
			return MessageUtil.readMessageFromInputStream(is);
		} finally {
			if (is!=null) is.close();
		}
	}

	@Ignore
	@Test
	public void testSMIMEValidatorWithTwoAttachments() throws Exception {
		String name = "nimh_with_two_attachments_smime.txt";
		MimeMessage message = readMimeMessageFromFile(name);
		assertTrue(SMIMEValidator.isAValidSMIMESignedMessage(message));
	}

	@Test
	public void testSMIMEValidatorJOSH() throws Exception {
		String name = "josh_smime.txt";
		MimeMessage message = readMimeMessageFromFile(name);
		assertTrue(SMIMEValidator.isAValidSMIMESignedMessage(message));
	}

	@Test
	public void testSMIMEValidatorNIKKI() throws Exception {
		String name = "nikki_smime.txt";
		MimeMessage message = readMimeMessageFromFile(name);
		assertTrue(SMIMEValidator.isAValidSMIMESignedMessage(message));
	}

	@Test
	public void testSMIMEValidatorCorrupt() throws Exception {
		MimeMessage message = readMimeMessageFromFile("nimh_with_two_attachments_smime.txt");
		// changing content of the message should cause validation to fail
		MimeMultipart mmp = (MimeMultipart)message.getContent();
		// an smime signed message has two parts, the first is the content and the second the signature
		assertEquals(2, mmp.getCount());
		BodyPart content = mmp.getBodyPart(0);
		content.setText("some rogue content");
		assertFalse(SMIMEValidator.isAValidSMIMESignedMessage(message));
	}

	@Test
	public void testSMIMEValidatorNotSigned() throws Exception {
		MimeMessage message = readMimeMessageFromFile("sample_mime.txt");
		assertFalse(SMIMEValidator.isAValidSMIMESignedMessage(message));
	}

}
