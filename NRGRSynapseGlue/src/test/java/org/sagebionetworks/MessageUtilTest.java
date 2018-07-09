package org.sagebionetworks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import org.apache.http.entity.ContentType;
import org.junit.Test;

public class MessageUtilTest {

	// although the code for doing this is simple, we need to test that
	// we can deserialize a binary file back into a MimeMessage
	@Test
	public void testReadMessageFromInputStream() throws Exception {
		InputStream is = null;
		try {
			is = MessageUtilTest.class.getClassLoader().getResourceAsStream("no_originating_ip_mime.txt");
			MimeMessage message = MessageUtil.readMessageFromInputStream(is);
			assertTrue(message.getContent() instanceof MimeMultipart);
			MimeMultipart mmp = (MimeMultipart)message.getContent();
			assertEquals(2, mmp.getCount());
			// one is the text, the other html
			for (int i=0; i<mmp.getCount(); i++) {
				String content = (String)mmp.getBodyPart(i).getContent();
				// this is the content of our test message:
				assertTrue(content.indexOf("this is a test MIME message")>=0);
				System.out.println(content);
			}
		} finally {
			if (is!=null) is.close();
		}
	}
	
	private static void createAndPrintMultiPartMIMEMessage(String plainText) throws IOException, MessagingException {
		Message message = new MimeMessage((Session)null);
		MimeMultipart content = new MimeMultipart();
		MimeBodyPart bodyPart = new MimeBodyPart();
		bodyPart.setContent(plainText, ContentType.TEXT_PLAIN.getMimeType());
		content.addBodyPart(bodyPart);
		message.setContent(content);
		message.writeTo(System.out);
	}
	
	@Test
	public void testEncodeMessage() throws Exception {
		System.out.println("-------------------------- Result for a short string --------------------------");
		createAndPrintMultiPartMIMEMessage("My dog has Fleas.");
		System.out.println("\n\n\n-------------------------- Result for a long string --------------------------");
		StringBuilder sb = new StringBuilder();
		for (int i=0; i<58; i++) sb.append("my dog has fleas. ");
		createAndPrintMultiPartMIMEMessage(sb.toString());
	}

}
