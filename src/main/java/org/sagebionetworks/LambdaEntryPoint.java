package org.sagebionetworks;

import static org.sagebionetworks.repo.model.AuthorizationConstants.BEARER_TOKEN_HEADER;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.api.client.util.Base64;

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
 *  this status doesn't mean the tokens were correct, just that the process completed.  
 *  The returned message will contain results about specific tokens.
 * 401 status - problem with authentication
 * 500 status - internal error
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
		responseHeaders.put("Access-Control-Allow-Headers", "Content-Type,sessionToken,Authorization");
		responseHeaders.put("Access-Control-Allow-Origin", "*");
		responseHeaders.put("Access-Control-Allow-Methods", "OPTIONS,POST");
		if (event.getHttpMethod()!=null && event.getHttpMethod().toLowerCase().equals("options")) {
			responseHeaders.put("Allow", "OPTIONS,POST");
			result.setStatusCode(200);
			result.setBody("OPTIONS response from Lambda");
			return result;
		}
		if (event.getHttpMethod()==null || !event.getHttpMethod().toLowerCase().equals("post")) {
			result.setStatusCode(405);
			result.setBody("Method not supported: "+event.getHttpMethod());
			return result;
		}
		try {
			String data = event.getBody();
			if (BooleanUtils.isTrue(event.getIsBase64Encoded())) {
				data = new String(Base64.decodeBase64(data));
			}
			String sessionToken = event.getHeaders().get("sessiontoken"); // something (API Gateway or Lambda) converts headers to lowercase
			String authorizationHeader = event.getHeaders().get("authorization"); // something (API Gateway or Lambda) converts headers to lowercase
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
				result.setStatusCode(401);
				result.setBody("Must include either session token or access token to authenticate with Synapse.\n");
				return result;
			}
			
			String mySynapseUserId;
			try {
				mySynapseUserId = synapseClient.getMyProfile().getOwnerId();
			} catch (SynapseException e) {
				result.setStatusCode(401);
				result.setBody("Session/access token is invalid.  Unable to authenticate to Synapse.\n");
				return result;
			}
			
			NRGRSynapseGlue sg = new NRGRSynapseGlue();
			result.setBody(sg.processSubmittedToken(data, mySynapseUserId));
			result.setStatusCode(201);
		} catch (Exception e) {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintWriter pw = new PrintWriter(baos);
			pw.println(e.getMessage());
			pw.println();
			e.printStackTrace(pw);
			result.setStatusCode(500);
			result.setBody(baos.toString());
		}
		return result;
	}
	
	private String eventAsString(APIGatewayProxyRequestEvent event) {
		StringBuilder sb = new StringBuilder();
		sb.append("Headers:\n");
		sb.append(event.getHeaders());
		sb.append("\nMulti-value Headers:\n");
		sb.append(event.getMultiValueHeaders());
		sb.append("\nBody:\n");
		sb.append(event.getBody());
		return sb.toString();
	}
}
