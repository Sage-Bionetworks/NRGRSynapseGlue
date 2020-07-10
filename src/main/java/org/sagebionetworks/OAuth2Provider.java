package org.sagebionetworks;

import java.security.Provider;

public class OAuth2Provider  extends Provider {
    private static final long serialVersionUID = 1L;

    public OAuth2Provider() {
      super("Google OAuth2 Provider", 1.0,
            "Provides the XOAUTH2 SASL Mechanism");
      put("SaslClientFactory.XOAUTH2",
          "org.sagebionetworks.OAuth2SaslClientFactory");
    }
}


