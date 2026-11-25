package com.feiniu.carmusic.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Saved login passwords are authenticated ciphertext, never plaintext preferences or logs. */
public final class AccountStore {
    interface KeyProvider { byte[] material() throws Exception; }
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;
    private final KeyProvider keys;
    private final Context context;
    public AccountStore(final Context context) {
        this(context, new KeyProvider() {
            @Override public byte[] material() throws Exception { return loadKey(context.getApplicationContext()); }
        });
    }
    AccountStore(Context context, KeyProvider keys) {
        this.context = context.getApplicationContext();
        prefs = context.getApplicationContext().getSharedPreferences("saved_accounts", 0);
        this.keys = keys;
    }

    public List<Account> list() throws Exception {
        synchronized (LOCK) {
            String saved = prefs.getString("encrypted", "");
            List<Account> accounts = new ArrayList<Account>();
            if (saved.length() == 0) return accounts;
            byte[] envelope = Base64.decode(saved, Base64.NO_WRAP);
            if (envelope.length < 65 || envelope[0] != 1) throw new GeneralSecurityException("账户文件损坏");
            byte[] material = keys.material();
            byte[] authenticated = Arrays.copyOf(envelope, envelope.length - 32);
            if (!MessageDigest.isEqual(mac(material, authenticated), Arrays.copyOfRange(envelope, envelope.length - 32, envelope.length)))
                throw new GeneralSecurityException("账户文件校验失败");
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOf(material, 32), "AES"),
                    new IvParameterSpec(Arrays.copyOfRange(envelope, 1, 17)));
            JSONArray data = new JSONArray(new String(cipher.doFinal(Arrays.copyOfRange(envelope, 17, envelope.length - 32)), "UTF-8"));
            for (int i = 0; i < data.length(); i++) {
                JSONObject row = data.getJSONObject(i);
                accounts.add(new Account(row.getString("id"), row.getString("server"), row.getString("username"), row.getString("password")));
            }
            return accounts;
        }
    }

    public void save(String server, String username, String password) throws Exception {
        synchronized (LOCK) {
            String id = id(server, username);
            List<Account> accounts = list();
            for (Iterator<Account> i = accounts.iterator(); i.hasNext();) if (i.next().id.equals(id)) i.remove();
            if (accounts.size() >= 20) throw new IOException("最多保存 20 个账户，请先删除不再使用的账户");
            accounts.add(0, new Account(id, server, username, password));
            write(accounts);
        }
    }

    public void delete(String id) throws Exception {
        synchronized (LOCK) {
            List<Account> accounts = list();
            for (Iterator<Account> i = accounts.iterator(); i.hasNext();) if (i.next().id.equals(id)) i.remove();
            write(accounts);
        }
    }
    public void clear() {
        synchronized (LOCK) {
            prefs.edit().clear().commit();
            new File(context.getFilesDir(), "saved-account.key").delete();
            new File(context.getFilesDir(), "saved-account.key.tmp").delete();
            if (Build.VERSION.SDK_INT >= 18) try { AccountKeyStoreApi18.delete(); } catch (Exception ignored) { }
        }
    }

    private void write(List<Account> accounts) throws Exception {
        JSONArray data = new JSONArray();
        for (Account account : accounts) data.put(new JSONObject().put("id", account.id).put("server", account.server)
                .put("username", account.username).put("password", account.password));
        byte[] material = keys.material();
        byte[] iv = new byte[16]; new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOf(material, 32), "AES"), new IvParameterSpec(iv));
        ByteArrayOutputStream envelope = new ByteArrayOutputStream();
        envelope.write(1); envelope.write(iv); envelope.write(cipher.doFinal(data.toString().getBytes("UTF-8")));
        envelope.write(mac(material, envelope.toByteArray()));
        if (!prefs.edit().putString("encrypted", Base64.encodeToString(envelope.toByteArray(), Base64.NO_WRAP)).commit())
            throw new IOException("无法保存账户");
    }

    private static byte[] mac(byte[] key, byte[] bytes) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Arrays.copyOfRange(key, 32, 64), "HmacSHA256"));
        return mac.doFinal(bytes);
    }
    private static String id(String server, String username) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest((server.trim() + "\n" + username.trim()).getBytes("UTF-8"));
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
    private static byte[] loadKey(Context context) throws Exception {
        File file = new File(context.getFilesDir(), "saved-account.key");
        if (file.isFile()) {
            if (file.length() > 4096 || file.length() < 65) throw new GeneralSecurityException("账户密钥损坏");
            DataInputStream input = new DataInputStream(new FileInputStream(file));
            byte[] data;
            int mode;
            try { mode = input.readUnsignedByte(); data = new byte[(int) file.length() - 1]; input.readFully(data); }
            finally { input.close(); }
            if (mode == 1 && Build.VERSION.SDK_INT >= 18) data = AccountKeyStoreApi18.transform(context, data, false);
            else if (mode != 0) throw new GeneralSecurityException("账户密钥不可用");
            if (data.length != 64) throw new GeneralSecurityException("账户密钥长度错误");
            return data;
        }
        byte[] material = new byte[64]; new SecureRandom().nextBytes(material);
        boolean wrapped = Build.VERSION.SDK_INT >= 18;
        byte[] data = wrapped ? AccountKeyStoreApi18.transform(context, material, true) : material;
        File temporary = new File(file.getPath() + ".tmp");
        FileOutputStream output = new FileOutputStream(temporary);
        try { output.write(wrapped ? 1 : 0); output.write(data); output.getFD().sync(); }
        finally { output.close(); }
        if (!temporary.renameTo(file)) { temporary.delete(); throw new IOException("无法保存账户密钥"); }
        return material;
    }

    public static final class Account {
        public final String id, server, username;
        private final String password;
        Account(String id, String server, String username, String password) {
            this.id = id; this.server = server; this.username = username; this.password = password;
        }
        public String password() { return password; }
    }
}
