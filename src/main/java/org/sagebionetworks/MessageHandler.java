package org.sagebionetworks;

import java.io.IOException;

public interface MessageHandler {
	void handleMessageContent(byte[] messageContent) throws IOException;
}
