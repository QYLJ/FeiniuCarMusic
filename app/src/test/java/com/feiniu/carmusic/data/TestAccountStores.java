package com.feiniu.carmusic.data;
import android.content.Context;
/** Deterministic test-only material; production always loads the protected per-install key. */
public final class TestAccountStores {
    public static AccountStore create(Context context) {
        return new AccountStore(context, () -> { byte[] key=new byte[64]; for(int i=0;i<key.length;i++)key[i]=(byte)(i+1);return key; });
    }
}
