package io.github.fkxiaobu;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

final class IdentityVerifier {
    static JSONObject verify(String jwt, JSONObject jwks, String issuer, String audience, String nonce,
                             String expectedSubject, long nowSeconds) throws Exception {
        String[] parts = jwt.split("\\.", -1);
        require(parts.length == 3, "ID token 格式不正确");
        JSONObject header = decode(parts[0]);
        require("RS256".equals(header.optString("alg")), "不支持的 ID token 签名算法");
        require(!header.has("crit"), "不支持的 ID token 扩展");
        String kid = header.getString("kid");
        JSONObject selected = null;
        JSONArray keys = jwks.getJSONArray("keys");
        for (int i = 0; i < keys.length(); i++) {
            JSONObject candidate = keys.getJSONObject(i);
            if (kid.equals(candidate.optString("kid")) && "RSA".equals(candidate.optString("kty"))) {
                require(selected == null, "重复的签名密钥");
                selected = candidate;
            }
        }
        require(selected != null, "找不到签名密钥");
        require(!selected.has("use") || "sig".equals(selected.getString("use")), "密钥用途不匹配");
        require(!selected.has("alg") || "RS256".equals(selected.getString("alg")), "密钥算法不匹配");
        BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(selected.getString("n")));
        BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(selected.getString("e")));
        require(n.bitLength() >= 2048, "签名密钥长度不足");
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e)));
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        require(signature.verify(Base64.getUrlDecoder().decode(parts[2])), "ID token 签名验证失败");
        JSONObject claims = decode(parts[1]);
        require(issuer.equals(claims.getString("iss")), "ID token issuer 不匹配");
        Object aud = claims.get("aud");
        boolean matched = audience.equals(aud);
        if (aud instanceof JSONArray) {
            JSONArray array = (JSONArray) aud;
            for (int i = 0; i < array.length(); i++) matched |= audience.equals(array.getString(i));
            if (array.length() > 1) require(audience.equals(claims.optString("azp")), "ID token azp 不匹配");
        }
        require(matched, "ID token audience 不匹配");
        require(claims.getLong("exp") > nowSeconds, "ID token 已过期");
        require(claims.optLong("nbf", 0) <= nowSeconds + 60, "ID token 尚未生效");
        require(claims.optLong("iat", 0) <= nowSeconds + 60, "ID token 签发时间不正确");
        if (nonce != null) require(nonce.equals(claims.optString("nonce")), "ID token nonce 不匹配");
        String subject = claims.getString("sub");
        require(!subject.isEmpty(), "缺少账户标识");
        if (expectedSubject != null) require(expectedSubject.equals(subject), "返回账户与所选账户不一致");
        return claims;
    }
    private static JSONObject decode(String text) throws Exception {
        return new JSONObject(new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8));
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
