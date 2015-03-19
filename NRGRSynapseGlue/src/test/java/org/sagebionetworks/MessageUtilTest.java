package org.sagebionetworks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;

import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

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
			}
		} finally {
			if (is!=null) is.close();
		}
	}

}
