package com.wizaord.boursycrypto.gdax.service;

import com.wizaord.boursycrypto.gdax.config.properties.ApplicationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.management.RuntimeErrorException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Component
public class SignatureService {

  private static final Logger LOG = LoggerFactory.getLogger(SignatureService.class);

  @Autowired
  private ApplicationProperties applicationProperties;

  /**
   * The CB-ACCESS-SIGN header is generated by creating a sha256 HMAC using
   * the base64-decoded secret key on the prehash string for:
   * timestamp + method + requestPath + body (where + represents string concatenation)
   * and base64-encode the output.
   * The timestamp value is the same as the CB-ACCESS-TIMESTAMP header.
   * @param requestPath
   * @param method
   * @param body
   * @param timestamp
   * @return
   */
  public String generate(String requestPath, String method, String body, String timestamp) {
    LOG.info("Generate Signature service with body : " + body);
    try {
      String prehash = timestamp + method.toUpperCase() + requestPath + body;
      byte[] secretDecoded = Base64.getDecoder().decode(applicationProperties.getAuth().getApisecretkey());
      SecretKeySpec keyspec = new SecretKeySpec(secretDecoded, "HmacSHA256");
      Mac sha256 = Mac.getInstance("HmacSHA256");
      sha256.init(keyspec);
      return Base64.getEncoder().encodeToString(sha256.doFinal(prehash.getBytes()));
    } catch (InvalidKeyException e) {
      e.printStackTrace();
      throw new RuntimeErrorException(new Error("Cannot set up authentication headers."));
    }
    catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
      throw new RuntimeErrorException(new Error("Algorithme HmacSHA256 not implemented"));
    }
  }
}
