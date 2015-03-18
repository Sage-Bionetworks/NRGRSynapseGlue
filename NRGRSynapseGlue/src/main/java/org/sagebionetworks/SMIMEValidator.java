package org.sagebionetworks;

import static org.sagebionetworks.Util.getProperty;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Security;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.util.Collection;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.bouncycastle.cert.CertException;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedData;
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

	static {
		Security.addProvider(new BouncyCastleProvider());
	}

	private static X509CertificateHolder[] CERTIFICATE_CHAIN;

	private static boolean isCertificateChainValidated = false;

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

	private static void initializeAndValidateCertificateChain() throws IOException {
		String certChainString = getProperty("CERTIFICATE_CHAIN");
		String[] certChainStringUrls = certChainString.split(",");
		CERTIFICATE_CHAIN = new X509CertificateHolder[certChainStringUrls.length];
		for (int i= 0; i<certChainStringUrls.length; i++) {
			CERTIFICATE_CHAIN[i] = new X509CertificateHolder(getFromURL(certChainStringUrls[i]));
			if (i>0) {
				boolean isCertificateValid = isCertificateValid(CERTIFICATE_CHAIN[i], CERTIFICATE_CHAIN[i-1]);
				if (!isCertificateValid) throw new RuntimeException("Certificate chain validation failed at step "+i);
			}
		}
		isCertificateChainValidated=true;
	}

	public static boolean isAValidSMIMESignedMessage(MimeMessage msg) throws IOException, MessagingException, CMSException, SMIMEException, OperatorCreationException, CertificateException, NoSuchAlgorithmException, SignatureException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException, NoSuchPaddingException, NoSuchProviderException {
		if (!isCertificateChainValidated) {
			// after validating the certificate chain, we can use the last link in the chain
			// to validate certificates in actual messages
			initializeAndValidateCertificateChain();
		}

		SMIMESigned signedMessage=null;

		// make sure this was a multipart/signed message - there should be
		// two parts as we have one part for the content that was signed and
		// one part for the actual signature.
		if (msg.isMimeType("multipart/signed")) {
			MimeMultipart mmp = (MimeMultipart)msg.getContent();
			signedMessage = new SMIMESigned(mmp);
		} else if (msg.isMimeType("application/pkcs7-mime") || msg.isMimeType("application/x-pkcs7-mime")) {
			// in this case the content is wrapped in the signature block.
			signedMessage = new SMIMESigned(msg);
		} else {
			// Message is not a signed message
		}

		if (signedMessage==null) {
			return false;
		} else {
			return hasValidSignature(signedMessage);	
		}
	}

	/**
	 * verify the signature (assuming the cert is contained in the message)
	 * @throws CertificateException 
	 * @throws OperatorCreationException 
	 * @throws CMSException 
	 * @throws MessagingException 
	 * @throws NoSuchAlgorithmException 
	 * @throws SignatureException 
	 * @throws BadPaddingException 
	 * @throws IllegalBlockSizeException 
	 * @throws InvalidKeyException 
	 * @throws NoSuchPaddingException 
	 * @throws NoSuchProviderException 
	 */
	private static boolean hasValidSignature(CMSSignedData signedMessage) throws OperatorCreationException, CertificateException, CMSException, IOException, MessagingException, NoSuchAlgorithmException, SignatureException, IllegalBlockSizeException, BadPaddingException, InvalidKeyException, NoSuchPaddingException, NoSuchProviderException {
		boolean messageIsValidated = false;

		Store certs = signedMessage.getCertificates();
		SignerInformationStore signers = signedMessage.getSignerInfos();
		JcaSimpleSignerInfoVerifierBuilder ivBuilder = new JcaSimpleSignerInfoVerifierBuilder();
		ivBuilder.setProvider("BC");

		// Per RFC 4853, if any signature is valid, then message is verified
		// see http://www.faqs.org/rfcs/rfc4853.html
		for(Object signerObj : signers.getSigners()) {
			SignerInformation signer = (SignerInformation) signerObj;
			Collection<X509CertificateHolder> certCollection = certs.getMatches(signer.getSID());
			for (X509CertificateHolder certificateHolder : certCollection) {
				// First we validate the certificate (not the signature on the message itself)
				boolean isCertificateValid = isCertificateValid(certificateHolder, CERTIFICATE_CHAIN[CERTIFICATE_CHAIN.length-1]);
				if (!isCertificateValid) continue;
				// Now verify that the sig is correct and that it was generated
				// when the certificate was current
				SignerInformationVerifier signerInformationVerifier = ivBuilder.build(certificateHolder);
				try {
					if(signer.verify(signerInformationVerifier)) {
						messageIsValidated=true; // no need to check anything more
						break;
					}
				} catch (CMSException e) {
					System.out.println(e.getMessage());
					// check the next certificate
				}
			}

			if (messageIsValidated) break; // no need to check anything more
		}

		return messageIsValidated;
	}
}
