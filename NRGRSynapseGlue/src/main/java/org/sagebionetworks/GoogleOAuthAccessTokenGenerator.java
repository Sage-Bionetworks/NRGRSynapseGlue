package org.sagebionetworks;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;

import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;

/*
 * This is not part of the active application, but a utility to do the initial
 * part of the OAuth handshake and create an access token suitable for connecting
 * to a GMAIL account.
 * 
 */
// see also https://developers.google.com/google-apps/spreadsheets/#authorizing_requests_with_oauth_20s
public class GoogleOAuthAccessTokenGenerator {

  private static final String REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob";
  private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
  private static final JsonFactory JSON_FACTORY = new JacksonFactory();
  
  public static GoogleCredential createGoogleCredential() throws IOException {
	  JSONObject clientSecrets = getClientSecrets();	  
	  String clientId = (String)clientSecrets.get("client_id");
	  String clientSecret = (String)clientSecrets.get("client_secret");
	  return (new GoogleCredential.Builder()).
	  	setJsonFactory(JSON_FACTORY).
	  	setTransport(HTTP_TRANSPORT).
	  	setClientSecrets(clientId, clientSecret).
	  	build();
  }
  
  private static JSONObject getClientSecrets() throws IOException {
	  // client_secrets.json is on the class path but does not go into the source repository
	  InputStream is = GoogleOAuthAccessTokenGenerator.class.getClassLoader().getResourceAsStream("client_secrets.json");
	  if (is==null) throw new NullPointerException("Failed to find client_secrets.json on classpath");
	  JSONObject clientSecrets = (JSONObject)JSONValue.parse(new InputStreamReader(is));
	  is.close();
	  return clientSecrets;
  }
  
  private static void getToken() throws IOException {
	  JSONObject clientSecrets = getClientSecrets();
	  String clientId = (String)clientSecrets.get("client_id");
	  String clientSecret = (String)clientSecrets.get("client_secret");

	  GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
			  HTTP_TRANSPORT, JSON_FACTORY, clientId, clientSecret, 
			  Arrays.asList("https://mail.google.com/"))
	  .setAccessType("offline")
	  .setApprovalPrompt("force").build();
	  // I read that accessType==offline and approvalPrompt=force are needed for non-expiring access

	  String url = flow.newAuthorizationUrl().setRedirectUri(REDIRECT_URI).build();
	  System.out.println("Please open the following URL in your browser then type the authorization code:");
	  System.out.println("  " + url);
	  BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
	  String code = br.readLine();
	  
	  GoogleTokenResponse gtr = flow.newTokenRequest(code).setRedirectUri(REDIRECT_URI).execute();
	  GoogleCredential credential = createGoogleCredential();
			  
	  credential.setFromTokenResponse(gtr);
	  System.out.println("RefreshToken  " + credential.getRefreshToken());
	  System.out.println("AccessToken  " + credential.getAccessToken());
  }
  
  public static GoogleCredential createCredentialForRefreshToken(boolean isProduction) throws IOException {
	  // client_secrets.json is on the class path but does not go into the source repository
	  InputStream is = GoogleOAuthAccessTokenGenerator.class.getClassLoader().getResourceAsStream("client_secrets.json");
	  if (is==null) throw new NullPointerException("Failed to find client_secrets.json on classpath");
	  JSONObject clientSecrets = (JSONObject)JSONValue.parse(new InputStreamReader(is));
	  is.close();
	  String refreshToken;
	  if (isProduction) {
		  refreshToken = (String)clientSecrets.get("prod_refresh_token");
	  } else {					
		  refreshToken = (String)clientSecrets.get("dev_refresh_token");
	  }
	  GoogleCredential credential = createGoogleCredential();
	  credential.setRefreshToken(refreshToken);
	  return credential;
  }

  public static void main(String[] args) throws Exception {
	  getToken();
  }
}
