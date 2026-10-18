package com.feiniu.carmusic.feedback;

import android.util.Base64;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class FeedbackCryptoTest {
    @Test public void encryptedEnvelopeAuthenticatesAndRoundTrips() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        byte[] plain = "带中文的反馈证据\npassword=<redacted>".getBytes("UTF-8");
        byte[] envelope = FeedbackCrypto.encrypt(plain,
                Base64.encodeToString(keys.getPublic().getEncoded(), Base64.NO_WRAP));

        DataInputStream input = new DataInputStream(new ByteArrayInputStream(envelope));
        byte[] magic = new byte[FeedbackCrypto.MAGIC.length];
        input.readFully(magic);
        assertArrayEquals(FeedbackCrypto.MAGIC, magic);
        int wrappedLength = input.readUnsignedShort();
        byte[] iv = new byte[16];
        input.readFully(iv);
        byte[] wrapped = new byte[wrappedLength];
        input.readFully(wrapped);
        int header = magic.length + 2 + iv.length + wrapped.length;
        byte[] cipherText = Arrays.copyOfRange(envelope, header, envelope.length - 32);
        byte[] expectedMac = Arrays.copyOfRange(envelope, envelope.length - 32, envelope.length);

        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.DECRYPT_MODE, keys.getPrivate());
        byte[] material = rsa.doFinal(wrapped);
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(material, 32, 32, "HmacSHA256"));
        assertTrueConstantTime(expectedMac, hmac.doFinal(Arrays.copyOf(envelope, envelope.length - 32)));

        Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(material, 0, 32, "AES"), new IvParameterSpec(iv));
        assertArrayEquals(plain, aes.doFinal(cipherText));
        assertEquals(64, FeedbackCrypto.sha256(envelope).length());
    }

    private static void assertTrueConstantTime(byte[] expected, byte[] actual) {
        assertEquals(true, MessageDigest.isEqual(expected, actual));
    }
}
