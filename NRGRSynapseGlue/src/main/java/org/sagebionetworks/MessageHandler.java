package org.sagebionetworks;

public interface MessageHandler {
	void handleMessageContent(byte[] messageContent);
}
