package org.sagebionetworks;

import java.io.IOException;
import java.security.Security;
import java.security.cert.CertificateException;
import java.util.Collection;
import java.util.Iterator;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.cert.CertException;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.cms.SignerInformationVerifier;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.mail.smime.SMIMEException;
import org.bouncycastle.mail.smime.SMIMESigned;
import org.bouncycastle.operator.ContentVerifierProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.util.Store;

public class SMIMEValidator {
	
	// From NIH IT:
	// As far as fully validating the signed certificates, you would need the HHS FPKI certificate validation 
	// chain, which can be found here:
	// https://ocio.nih.gov/Smartcard/Pages/PKI_chain.aspx#ecom
	// The ones you would need to validate the NIH’s certificates would be found under the 
	// HHS Entrust FPKI Certificate Chain header:
	private static final String Federal_CP_Root_URL = "https://ocio.nih.gov/Smartcard/Documents/Certificates/Federal_CP_Root_SHA256.cer";
	private static final String Entrust_Managed_Services_Root_URL = "https://ocio.nih.gov/Smartcard/Documents/Certificates/Entrust_Managed_Services_Root.cer";
	private static final String HHS_FPKI_Intermediate_CA_E1_URL = "https://ocio.nih.gov/Smartcard/Documents/Certificates/HHS-FPKI-Intermediate-CA-E1.cer";

	private static X509CertificateHolder[] CERTIFICATE_CHAIN;
	
	private static void initializeAndValidateCertificateChain() throws IOException {
		CERTIFICATE_CHAIN = new X509CertificateHolder[3];
		CERTIFICATE_CHAIN[0] = new X509CertificateHolder(getFromURL(Federal_CP_Root_URL));
		CERTIFICATE_CHAIN[1] = new X509CertificateHolder(getFromURL(Entrust_Managed_Services_Root_URL));
		CERTIFICATE_CHAIN[2] = new X509CertificateHolder(getFromURL(HHS_FPKI_Intermediate_CA_E1_URL));
		
		for (int i=1; i<CERTIFICATE_CHAIN.length; i++) {
			boolean isCertificateValid = isCertificateValid(CERTIFICATE_CHAIN[i], CERTIFICATE_CHAIN[i-1]);
			if (!isCertificateValid) throw new RuntimeException("Certificate chain validation failed at step "+i);
		}
	}
	
	private static byte[] getFromURL(final String urlString) {
		CloseableHttpClient httpclient = HttpClients.createDefault();
		HttpGet get = new HttpGet(urlString);
		CloseableHttpResponse response = null;
		try {
			response = httpclient.execute(get);
			return IOUtils.toByteArray(response.getEntity().getContent());
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			if (response!=null) {
				try {
					response.close();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		} 
	}

	public SMIMEValidator() throws IOException {
		 Security.addProvider(new BouncyCastleProvider());
		 
		 // having validated the certificate chain, we can use the last link in the chain
		 // to validate certificates in actual messages
		 initializeAndValidateCertificateChain();
	}
	
	public static void verifyMIMEMessage(MimeMessage msg) throws IOException, MessagingException, CMSException, SMIMEException, OperatorCreationException, CertificateException {

		SMIMESigned signedMessage=null;

		// make sure this was a multipart/signed message - there should be
		// two parts as we have one part for the content that was signed and
		// one part for the actual signature.
		if (msg.isMimeType("multipart/signed")) {
			signedMessage = new SMIMESigned((MimeMultipart) msg.getContent());
		} else if (msg.isMimeType("application/pkcs7-mime") || msg.isMimeType("application/x-pkcs7-mime")) {
			// in this case the content is wrapped in the signature block.
			signedMessage = new SMIMESigned(msg);
		} else {
			System.out.println("Message  is not a signed message!");
		}
		if (signedMessage!=null) {
			System.out.println("Message:");
			Object content = signedMessage.getContent().getContent();
			//System.out.println("Content: " + extractContent(content, "\t"));
			verify(signedMessage);	
		}
		
	}
	
	/**
	 * verify the signature (assuming the cert is contained in the message)
	 * @throws CertificateException 
	 * @throws OperatorCreationException 
	 * @throws CMSException 
	 */
	private static void verify(SMIMESigned signedMessage) throws OperatorCreationException, CertificateException, CMSException {
		Store certs = signedMessage.getCertificates();
		SignerInformationStore signers = signedMessage.getSignerInfos();
		
		
		System.out.println("verify(): number of signers: "+signers.getSigners().size());
		
		for(Object signerObj : signers.getSigners()) {
			SignerInformation signer = (SignerInformation) signerObj;
			Collection<X509CertificateHolder> certCollection = certs.getMatches(signer.getSID());
			System.out.println("verify(): Retrieved "+certCollection.size()+" number of certificates.");
			Iterator<X509CertificateHolder> certIt = certCollection.iterator();
			X509CertificateHolder certificateHolder = (X509CertificateHolder) certIt.next(); // TODO check 'hasNext'
			System.out.println("Signed by: " + certificateHolder.getSubject());
			
			// Here we just validate the certificate (not the signature on the message itself)
			boolean isCertificateValid = isCertificateValid(certificateHolder, CERTIFICATE_CHAIN[CERTIFICATE_CHAIN.length-1]);
			System.out.println("isCertificateValid "+isCertificateValid);
			// verify that the sig is correct and that it was generated
			// when the certificate was current
			System.out.println("mail.mime.cachemultipart: "+System.getProperty("mail.mime.cachemultipart"));
			JcaSimpleSignerInfoVerifierBuilder ivBuilder = new JcaSimpleSignerInfoVerifierBuilder();
			ivBuilder.setProvider("BC");
			SignerInformationVerifier signerInformationVerifier = ivBuilder.build(certificateHolder);
			
			// 'signer' references the message and verifies the digital signature
			AttributeTable at = signer.getSignedAttributes();
			ASN1EncodableVector v = at.getAll(CMSAttributes.messageDigest);
			for (int vi = 0; vi<v.size(); vi++) {
				Attribute t = (Attribute)v.get(vi);
				ASN1Set attrValues = t.getAttrValues();
				byte[] digest = ((ASN1OctetString)attrValues.getObjectAt(0).getDERObject()).getOctets();
			}

				if (signer.verify(signerInformationVerifier)) {
					System.out.println("signature verified");
				} else {
					System.out.println("signature failed!");
				}

		}
	}
	
	// from http://www.bouncycastle.org/wiki/display/JA1/BC+Version+2+APIs
	private static boolean isCertificateValid(
			X509CertificateHolder certificateToCheck, 
			X509CertificateHolder trustedCertificate) {
		try {
		ContentVerifierProvider cvp = new JcaContentVerifierProviderBuilder().
				setProvider("BC").build(trustedCertificate);
		return certificateToCheck.isSignatureValid(cvp);
		} catch (CertificateException | CertException | OperatorCreationException e) {
			throw new RuntimeException(e);
		}
	}	

}
