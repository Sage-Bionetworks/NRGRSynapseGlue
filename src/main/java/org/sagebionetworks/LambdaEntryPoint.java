package org.sagebionetworks;

import static org.sagebionetworks.repo.model.AuthorizationConstants.BEARER_TOKEN_HEADER;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.sagebionetworks.client.SynapseClient;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

/*
 * This is the entrypoint to call from AWS Lambda.
 * 
 * Request body is the data to be processed.
 * Authorization is either:
 * (1) sessionToken header, which is taken to be a Synapse session token, or
 * (2) Authorization header, which is taken to be a Synapse access token, passed as a bearer token
 * 
 * Response:
 * 201 status - success
 * 	response body is plain text, with a message for the user 
 * 4xx status - problem with authentication, authorization, or the data passed in to be processed
 * 	response body is plain text, an error message
 * 
 */
public class LambdaEntryPoint implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
		APIGatewayProxyResponseEvent result = new APIGatewayProxyResponseEvent();
		result.setIsBase64Encoded(false);
		Map<String,String> responseHeaders = new HashMap<String,String>();
		result.setHeaders(responseHeaders);
		responseHeaders.put("Content-Type", "text/plain");
		try {
			String data = event.getBody();
			String sessionToken = event.getHeaders().get("sessionToken");
			String authorizationHeader = event.getHeaders().get("Authorization");
			String accessToken = null;
			if (StringUtils.isNotEmpty(authorizationHeader) && authorizationHeader.toLowerCase().startsWith(BEARER_TOKEN_HEADER.toLowerCase())) {
				accessToken = authorizationHeader.substring(BEARER_TOKEN_HEADER.length());
			}
			// authenticate with Synapse
			SynapseClient synapseClient = SynapseClientFactory.createSynapseClient();
			if (StringUtils.isNotEmpty(sessionToken)) {
				synapseClient.setSessionToken(sessionToken);
			} else if (StringUtils.isNotEmpty(accessToken)) {
				synapseClient.setBearerAuthorizationToken(accessToken);
			} else {
				throw new RuntimeException("Must have either sessionToken or accessToken to authenticate with Synapse.");
			}
			
			NRGRSynapseGlue sg = new NRGRSynapseGlue(synapseClient);
			result.setBody(sg.processSubmittedToken(data));
			result.setStatusCode(201);
		} catch (Exception e) {
			result.setStatusCode(400);
			result.setBody(e.getMessage());
		}
		return result;
	}
}
