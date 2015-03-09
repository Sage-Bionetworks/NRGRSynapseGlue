/* Copyright 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * Note:  This file is a modified version of the original.
 */

package org.sagebionetworks;

import static javax.mail.Folder.READ_ONLY;
import static org.sagebionetworks.Util.getProperty;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.TreeMap;
import java.util.logging.Logger;

import javax.mail.Flags;
import javax.mail.Flags.Flag;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.URLName;

import org.apache.commons.io.IOUtils;
import org.apache.http.Consts;
import org.apache.http.HttpException;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import com.sun.mail.imap.IMAPSSLStore;
import com.sun.mail.imap.IMAPStore;

/*
 * This class has methods to connect to a gmail account, retrieve messages
 * from a given folder, and move messages between folders.
 */
public class IMAPClient {
	private static final Logger logger = Logger.getLogger(IMAPClient.class.getName());

	static {
		/**
		 * Installs the OAuth2 SASL provider. This must be called exactly once before
		 * calling other methods on this class.
		 */
		Security.addProvider(new OAuth2Provider());	  
	}

	private IMAPStore imapStore;
	
//	public static void main(String[] args) throws Exception {
//		IMAPClient imapClient = new IMAPClient();
//		imapClient.processNewMessages(new MessageHandler() {
//			
//
//			@Override
//			public void handleMessageContent(byte[] messageContent) {
//				Random random = new Random();
//				FileOutputStream fos = null;
//				try {
//					System.out.println("Found message of length "+messageContent.length);
//					fos = new FileOutputStream(
//							"/Users/bhoff/Documents/NRGRSynapseGlue/NRGRSynapseGlue/src/test/resources/"+
//							Math.abs(random.nextLong()));
//					IOUtils.write(messageContent, fos);
//				} catch (IOException e) {
//					throw new RuntimeException(e);
//				} finally {
//					try {
//						if (fos!=null) fos.close();
//					} catch (IOException e) {
//						throw new RuntimeException(e);
//					}
//				}
//			}});
//	}

	/**
	 * Check for new messages in the 'in-folder'.
	 * Retrieve all the new messages and pass each one to the given 
	 * 'handler' for processing.  Then move the messages to the output 
	 * folder
	 */
	public void processNewMessages(MessageHandler handler) throws Exception {
		String inFolder = getProperty("MAIL_IN_FOLDER");
		String outFolder = getProperty("MAIL_OUT_FOLDER");
		if (imapStore==null) {
			imapStore = connectToImap("imap.gmail.com", 993, false);
		}
		Map<Integer, byte[]> newMessages = getMessages(inFolder);
		int[] msgNumbers = new int[newMessages.size()];
		int i = 0;
		for (Integer msgNum : newMessages.keySet()) {
			byte[] message = newMessages.get(msgNum);
			handler.handleMessageContent(message);
			msgNumbers[i++] = msgNum;
		}
//		if (outFolder!=null) moveMessages(inFolder, outFolder, msgNumbers); // TODO reenable this!
	}


	/**
	 * Connects and authenticates to an IMAP server with OAuth2. You must have
	 * called {@code initialize}.
	 *
	 * @param host Hostname of the imap server, for example {@code
	 *     imap.googlemail.com}.
	 * @param port Port of the imap server, for example 993.
	 * @param userEmail Email address of the user to authenticate, for example
	 *     {@code oauth@gmail.com}.
	 * @param oauthToken The user's OAuth token.
	 * @param debug Whether to enable debug logging on the IMAP connection.
	 *
	 * @return An authenticated IMAPStore that can be used for IMAP operations.
	 * @throws IOException 
	 * @throws HttpException 
	 * @throws MessagingException 
	 */
	public static IMAPStore connectToImap(String host,
			int port,
			boolean debug) throws HttpException, IOException, MessagingException {
		String userEmail = getProperty("GMAIL_ADDRESS");

		String oauthToken = getGmailOAuthAccessToken();

		Properties props = new Properties();
		props.put("mail.imaps.sasl.enable", "true");
		props.put("mail.imaps.sasl.mechanisms", "XOAUTH2");
		props.put(OAuth2SaslClientFactory.OAUTH_TOKEN_PROP, oauthToken);
		Session session = Session.getInstance(props);
		session.setDebug(debug);
		session.getProperties().setProperty("mail.mime.cachemultipart", Boolean.FALSE.toString());

		final URLName unusedUrlName = null;
		IMAPSSLStore store = new IMAPSSLStore(session, unusedUrlName);

		final String emptyPassword = "";
		store.connect(host, port, userEmail, emptyPassword);
		return store;
	}

	private static String getGmailOAuthAccessToken() throws HttpException, IOException {
		CloseableHttpClient httpclient = HttpClients.createDefault();
		HttpPost post = new HttpPost("https://accounts.google.com/o/oauth2/token");
		String clientId = getProperty("GOOGLE_OAUTH_CLIENT_ID");
		String clientSecret = getProperty("GOOGLE_OAUTH_CLIENT_SECRET");
		String refreshToken = getProperty("GOOGLE_OAUTH_REFRESH_TOKEN");
		List<NameValuePair> formparams = new ArrayList<NameValuePair>();
		formparams.add(new BasicNameValuePair("client_id", clientId));
		formparams.add(new BasicNameValuePair("client_secret", clientSecret));
		formparams.add(new BasicNameValuePair("refresh_token", refreshToken));
		formparams.add(new BasicNameValuePair("grant_type", "refresh_token"));
		UrlEncodedFormEntity entity = new UrlEncodedFormEntity(formparams, Consts.UTF_8);
		post.setEntity(entity);
		CloseableHttpResponse response = httpclient.execute(post);
		try {
			JSONObject obj=(JSONObject)JSONValue.parse(
					new InputStreamReader(response.getEntity().getContent()));
			return (String)obj.get("access_token");
		} finally {
			response.close();
		} 
	}

	/**
	 * Connects to Gmail account and downloads pdf files
	 */
	public Map<Integer, byte[]> getMessages(String emailFolder) throws Exception {
		Folder folder = imapStore.getFolder(emailFolder);
		folder.open(READ_ONLY);  
		Map<Integer, byte[]> ans = new TreeMap<Integer, byte[]>();
		System.out.println("getMessages: messageCount="+folder.getMessageCount());
		try {
			for (int i=0; i<folder.getMessageCount(); i++) {
				int msgNum = i+1;
				Message message = folder.getMessage(msgNum);
				// write the content to a byte array
				{
					ByteArrayOutputStream baos = new ByteArrayOutputStream();
					try {
						message.writeTo(baos);
						baos.flush();
						byte[] bytes = baos.toByteArray();
						ans.put(new Integer(i), bytes);
					} finally {
						baos.close();
					}
				}
			} // end for i (iterating through messages)
		} finally {
			folder.close(false);
		}
		return ans;
	}

	public void moveMessages(String fromFolderName, String toFolderName, int[] messageIndices) throws HttpException, IOException, MessagingException {
		Folder fromFolder = imapStore.getFolder(fromFolderName);
		Folder toFolder = imapStore.getFolder(toFolderName);
		fromFolder.open(Folder.READ_WRITE);    
		toFolder.open(Folder.READ_WRITE);  
		try {
			Message[] messagesToMove = fromFolder.getMessages(messageIndices);
			toFolder.appendMessages(messagesToMove); // this 'tags' messages as being in *both* folders

			Flags flags = new Flags();
			flags.add(Flag.DELETED);
			fromFolder.setFlags(messagesToMove, flags, true); // this removes the 'tag' from the 'from' folder
		} finally {
			fromFolder.close(true);
			toFolder.close(true);
		}
	}


}
