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
	
	
//	private static final int MSG_PREFIX_LENGTH = 100;
//	
//	private static String extractSingleContext(Object cont, String prefix) throws IOException, MessagingException {
//		if (cont instanceof String) {
//			return prefix + ((String) cont).substring(0, MSG_PREFIX_LENGTH)+"...";
//		} else if (cont instanceof InputStream) {
//			return prefix + IOUtils.toString((InputStream)cont).substring(0, MSG_PREFIX_LENGTH)+"...";
//		} else if (cont instanceof MimeMultipart) {
//			return extractContent(cont, prefix+"\t");
//		} else {
//			throw new IllegalArgumentException("Unknown content object " + cont.getClass());
//		}
//	}
//	private static String extractContent(Object cont, String prefix) throws MessagingException, IOException {
//		StringBuilder result = new StringBuilder();
//		if (cont instanceof Multipart) {
//			Multipart mp = (Multipart) cont;
//			int count = mp.getCount();
//			result.append("Found a multipart message having "+ count + " parts:\n");
//			for (int i = 0; i < count; i++) {
//				BodyPart m = mp.getBodyPart(i);
//				Object part = m.getContent();
//				if (m.getContentType().toLowerCase().contains("image")) {
//					result.append(prefix+m.getContentType()+"\n");
//				} else {
//					result.append(prefix+extractSingleContext(part, prefix)+"\n");					
//				}
//			}
//		} else {
//			result.append(extractSingleContext(cont, prefix)+"\n");
//		}
//		return result.toString();
//	}

}
