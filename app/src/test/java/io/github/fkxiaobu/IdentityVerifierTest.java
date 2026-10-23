package io.github.fkxiaobu;

import org.junit.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.math.BigInteger;
import java.util.Base64;
import static org.junit.Assert.*;

public class IdentityVerifierTest {
    private static KeyPair key;
    private static JSONObject jwks;
    @BeforeClass public static void setup() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key = generator.generateKeyPair();
        RSAPublicKey pub = (RSAPublicKey) key.getPublic();
        JSONObject jwk = new JSONObject().put("kty", "RSA").put("kid", "test-key").put("alg", "RS256").put("use", "sig")
                .put("n", number(pub.getModulus())).put("e", number(pub.getPublicExponent()));
        jwks = new JSONObject().put("keys", new JSONArray().put(jwk));
    }
    private static String number(BigInteger number) {
        byte[] value = number.toByteArray();
        if (value[0] == 0) value = java.util.Arrays.copyOfRange(value, 1, value.length);
        return b64(value);
    }
    private static String b64(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private JSONObject claims() throws Exception { return new JSONObject().put("iss", "https://auth.openai.com").put("aud", "oaiapp_test")
            .put("sub", "account-1").put("exp", 2000).put("iat", 900).put("nonce", "nonce-1"); }
    private String token(JSONObject claims) throws Exception {
        String content = b64(new JSONObject().put("alg", "RS256").put("kid", "test-key").toString().getBytes(StandardCharsets.UTF_8)) + "." +
                b64(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA"); signature.initSign(key.getPrivate());
        signature.update(content.getBytes(StandardCharsets.US_ASCII)); return content + "." + b64(signature.sign());
    }
    private JSONObject verify(String value, String expected) throws Exception {
        return IdentityVerifier.verify(value, jwks, "https://auth.openai.com", "oaiapp_test", "nonce-1", expected, 1000);
    }
    private void rejects(JSONObject claims, String expected) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> verify(token(claims), expected));
    }
    @Test public void acceptsValidSignedIdentity() throws Exception { assertEquals("account-1", verify(token(claims()), "account-1").getString("sub")); }
    @Test public void rejectsWrongIssuer() throws Exception { rejects(claims().put("iss", "https://attacker.invalid"), null); }
    @Test public void rejectsWrongAudience() throws Exception { rejects(claims().put("aud", "other-client"), null); }
    @Test public void rejectsExpiredToken() throws Exception { rejects(claims().put("exp", 999), null); }
    @Test public void rejectsWrongNonce() throws Exception { rejects(claims().put("nonce", "other-attempt"), null); }
    @Test public void rejectsAccountSwitchDuringReauth() throws Exception { rejects(claims(), "account-2"); }
    @Test public void rejectsFutureToken() throws Exception { rejects(claims().put("nbf", 1100), null); }
    @Test public void rejectsAmbiguousAudienceWithoutAzp() throws Exception { rejects(claims().put("aud", new JSONArray().put("oaiapp_test").put("other")), null); }
    @Test public void acceptsMultipleAudiencesWithCorrectAzp() throws Exception {
        assertEquals("account-1", verify(token(claims().put("aud", new JSONArray().put("oaiapp_test").put("other")).put("azp", "oaiapp_test")), null).getString("sub"));
    }
    @Test public void rejectsPayloadTampering() throws Exception {
        String valid = token(claims()); String[] parts = valid.split("\\.");
        String changed = parts[0] + "." + b64(claims().put("sub", "attacker").toString().getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertThrows(IllegalArgumentException.class, () -> verify(changed, null));
    }
    @Test public void rejectsUnsignedAlgorithm() throws Exception {
        String unsigned = b64("{\"alg\":\"none\",\"kid\":\"test-key\"}".getBytes(StandardCharsets.UTF_8)) + "." +
                b64(claims().toString().getBytes(StandardCharsets.UTF_8)) + ".";
        assertThrows(IllegalArgumentException.class, () -> verify(unsigned, null));
    }
}
