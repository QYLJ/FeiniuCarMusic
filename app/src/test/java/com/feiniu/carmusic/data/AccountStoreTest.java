package com.feiniu.carmusic.data;
import android.content.Context;
import android.util.Base64;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, manifest=Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AccountStoreTest {
    private Context context;
    private AccountStore store;
    @Before public void before() {
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("saved_accounts",0).edit().clear().commit();
        store=TestAccountStores.create(context);
    }
    @Test public void savedPasswordsRoundTripAcrossRecreationWithoutPlaintextPreferences() throws Exception {
        store.save("testnas","alice"," secret-with-spaces ");
        store.save("anothernas","bob","second-secret");
        String disk=context.getSharedPreferences("saved_accounts",0).getString("encrypted","");
        assertFalse(disk.contains("alice")); assertFalse(disk.contains("secret"));
        AccountStore reloaded=TestAccountStores.create(context);
        assertEquals(2,reloaded.list().size());
        assertEquals(" secret-with-spaces ",reloaded.list().get(1).password());
        store.save("testnas","alice","updated-password");
        assertEquals(2,store.list().size()); assertEquals("updated-password",store.list().get(0).password());
    }
    @Test public void tamperingIsRejectedWithoutSilentlyDeletingAccounts() throws Exception {
        store.save("testnas","alice","secret");
        byte[] bytes=Base64.decode(context.getSharedPreferences("saved_accounts",0).getString("encrypted",""),Base64.NO_WRAP);
        bytes[24]^=1;
        String changed=Base64.encodeToString(bytes,Base64.NO_WRAP);
        context.getSharedPreferences("saved_accounts",0).edit().putString("encrypted",changed).commit();
        try { store.list(); fail(); } catch (java.security.GeneralSecurityException expected) { }
        assertEquals(changed,context.getSharedPreferences("saved_accounts",0).getString("encrypted",""));
        store.clear(); assertTrue(store.list().isEmpty());
        store.save("testnas","alice","re-added"); assertEquals("re-added",store.list().get(0).password());
    }
    @Test public void deletingOneAccountRetainsOtherCredentials() throws Exception {
        store.save("testnas","alice","secret1"); store.save("testnas","bob","secret2");
        store.delete(store.list().get(1).id);
        assertEquals(1,store.list().size()); assertEquals("bob",store.list().get(0).username);
    }
}
