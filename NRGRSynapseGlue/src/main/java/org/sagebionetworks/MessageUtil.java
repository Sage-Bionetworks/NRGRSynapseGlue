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

	public static MimeMessage readMessageFromInputStream(InputStream is) throws IOException {
		Properties props = new Properties();
		Session session = Session.getInstance(props);
		try {
			return new MimeMessage(session, is);
		} catch (MessagingException e) {
			throw new RuntimeException(e);
		}
	}
	
	public static MimeMessage readMessageFromFile(File file) throws IOException {
		InputStream is = new FileInputStream(file);
		try {
			return readMessageFromInputStream(is);
		} finally {
			is.close();
		}
	}


}
