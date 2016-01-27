package org.sagebionetworks;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Properties;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;

import org.apache.commons.io.IOUtils;
import org.apache.http.entity.ContentType;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.UserProfile;
import org.sagebionetworks.repo.model.message.MessageToUser;
import org.sagebionetworks.repo.model.message.multipart.Attachment;
import org.sagebionetworks.repo.model.message.multipart.MessageBody;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;

public class MessageUtil {
	
	private SynapseClient synapseClient;
	
	public MessageUtil(SynapseClient synapseClient) {
		this.synapseClient=synapseClient;
	}

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
	
	private static ContentType MESSAGE_JSON_CONTENT_TYPE = ContentType.APPLICATION_JSON;

	public void sendMessage(MessageToUser messageToUser, String messageBodyContent, String messageAttachment) throws SynapseException {
		MessageBody messageBody = new MessageBody();
		messageBody.setPlain(messageBodyContent);
		Attachment attachment = new Attachment();
		attachment.setContent(messageAttachment);
		messageBody.setAttachments(Collections.singletonList(attachment));
		String messageBodyString;
		try {
			messageBodyString = EntityFactory.createJSONStringForEntity(messageBody);
		} catch (JSONObjectAdapterException e) {
			throw new RuntimeException(e);
		}
		String fileHandleId = synapseClient.uploadToFileHandle(
				messageBodyString.getBytes(MESSAGE_JSON_CONTENT_TYPE.getCharset()),
				MESSAGE_JSON_CONTENT_TYPE);
		messageToUser.setFileHandleId(fileHandleId);

		synapseClient.sendMessage(messageToUser);
	}

	public void sendMessage(MessageToUser messageToUser, String messageBody) throws SynapseException {
		synapseClient.sendStringMessage(messageToUser, messageBody);
	}

	public static String salutation(UserProfile up) {
		StringBuilder sb = new StringBuilder();
		sb.append("Dear ");
		if ((up.getFirstName()!=null && up.getFirstName().length()>0) || 
				(up.getLastName()!=null && up.getLastName().length()>0)) {
			sb.append(up.getFirstName()+" "+up.getLastName());
		} else {
			sb.append(up.getUserName());
		}
		sb.append(":\n");
		return sb.toString();
	}

	public String createGenericMessage(UserProfile userProfile, String synapseTemplateId) throws IOException, SynapseException {
		File temp = File.createTempFile("temp", null);
		synapseClient.downloadFromFileEntityCurrentVersion(synapseTemplateId, temp);
		InputStream is = new FileInputStream(temp);
		try {
			String message = IOUtils.toString(is);
			StringBuilder sb = new StringBuilder();
			sb.append(salutation(userProfile));
			sb.append("\n");
			sb.append(message);
			return sb.toString();
		} finally {
			is.close();
		}
	}


}
