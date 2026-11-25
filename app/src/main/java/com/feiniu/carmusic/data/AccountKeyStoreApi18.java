package com.feiniu.carmusic.data;

import android.content.Context;
import android.security.KeyPairGeneratorSpec;
import java.math.BigInteger;
import java.security.*;
import java.util.Calendar;
import java.util.Date;
import javax.crypto.Cipher;
import javax.security.auth.x500.X500Principal;

@android.annotation.TargetApi(18)
final class AccountKeyStoreApi18 {
    private static final String ALIAS = "FeiniuCarMusic.savedAccounts";
    static void delete() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry(ALIAS);
    }
    static byte[] transform(Context context, byte[] bytes, boolean encrypt) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            if (!encrypt) throw new GeneralSecurityException("账户密钥不可用");
            Calendar end = Calendar.getInstance(); end.add(Calendar.YEAR, 30);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            generator.initialize(new KeyPairGeneratorSpec.Builder(context).setAlias(ALIAS)
                    .setSubject(new X500Principal("CN=FeiniuCarMusic accounts"))
                    .setSerialNumber(BigInteger.ONE).setStartDate(new Date(0)).setEndDate(end.getTime()).build());
            generator.generateKeyPair();
        }
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                encrypt ? store.getCertificate(ALIAS).getPublicKey() : store.getKey(ALIAS, null));
        return cipher.doFinal(bytes);
    }
}
