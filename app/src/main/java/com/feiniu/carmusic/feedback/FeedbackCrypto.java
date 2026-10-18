package com.feiniu.carmusic.feedback;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Hybrid envelope: RSA-wrapped AES/HMAC keys, AES-CBC payload, encrypt-then-MAC. */
public final class FeedbackCrypto {
    static final byte[] MAGIC = new byte[] {'F', 'N', 'C', 'F', 'B', '1'};

    private FeedbackCrypto() { }

    public static byte[] encrypt(byte[] plainText, String publicKeyBase64) throws Exception {
        if (plainText == null || plainText.length == 0) throw new IllegalArgumentException("反馈证据为空");
        byte[] encoded = Base64.decode(publicKeyBase64, Base64.DEFAULT);
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));

        SecureRandom random = new SecureRandom();
        byte[] keyMaterial = new byte[64];
        byte[] iv = new byte[16];
        random.nextBytes(keyMaterial);
        random.nextBytes(iv);

        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, publicKey, random);
        byte[] wrappedKeys = rsa.doFinal(keyMaterial);

        Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyMaterial, 0, 32, "AES"), new IvParameterSpec(iv), random);
        byte[] cipherText = aes.doFinal(plainText);

        ByteArrayOutputStream unsignedBytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(unsignedBytes);
        output.write(MAGIC);
        output.writeShort(wrappedKeys.length);
        output.write(iv);
        output.write(wrappedKeys);
        output.write(cipherText);
        output.flush();

        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(keyMaterial, 32, 32, "HmacSHA256"));
        byte[] body = unsignedBytes.toByteArray();
        byte[] authentication = hmac.doFinal(body);
        ByteArrayOutputStream result = new ByteArrayOutputStream(body.length + authentication.length);
        result.write(body);
        result.write(authentication);
        return result.toByteArray();
    }

    public static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte value : hash) result.append(String.format(java.util.Locale.US, "%02x", value));
        return result.toString();
    }
}
