package org.sagebionetworks;

import org.junit.Test;

public class NRGRSynapseGlueTest {

  @Test
  public void testParseTokenFromFileContent() throws Exception {
	  String unsignedToken = NRGRSynapseGlue.createUnsignedToken("273995", "3248760", "1425074994612");
	  String signedToken = unsignedToken+NRGRSynapseGlue.hmac(unsignedToken)+"|";
	  String fileContent= "===========================================================================\\\n"+
					  signedToken+
			  "\n===========================================================================}";
	  NRGRSynapseGlue.parseTokensFromFileContent(fileContent);
	  fileContent = 
			  "===========================================================================\\\n"+
					  signedToken+
			  "\\\n===========================================================================}";
	  NRGRSynapseGlue.parseTokensFromFileContent(fileContent);
	  System.out.println(fileContent);
  }

}
