package org.sagebionetworks;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;

public class MessageUtil {

	public static MimeMessage readMessageFromInputStream(InputStream is) throws IOException, MessagingException {
		Properties props = new Properties();
		props.setProperty("mail.mime.cachemultipart", "false");
		Session session = Session.getInstance(props);
		return new MimeMessage(session, is);
	}
	
	public static MimeMessage readMessageFromFile(File file) throws IOException, MessagingException {
		InputStream is = new FileInputStream(file);
		try {
			return readMessageFromInputStream(is);
		} finally {
			is.close();
		}
	}


}
