package com.feiniu.carmusic.ui;
import android.content.*;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import com.feiniu.carmusic.R;
import com.feiniu.carmusic.data.*;
import com.feiniu.carmusic.model.Track;
import com.feiniu.carmusic.player.PlaybackService;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import android.app.AlertDialog;
import android.content.DialogInterface;
import java.lang.reflect.*;
import java.util.Collections;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SavedAccountsUiTest {
    @Test public void savedAccountClickAuthenticatesWithoutTypingAndClearsOldQueueBeforeActivating()throws Exception{
        Context context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("saved_accounts",0).edit().clear().commit();
        FnApiClient api=FnApiClient.get(context);api.session().saveLogin("http://old.example","old-token","alice","oldnas");
        MockWebServer nas=new MockWebServer();nas.start();
        AccountStore store=TestAccountStores.create(context);
        store.save(nas.url("/").toString().replaceAll("/$",""),"bob","saved-password");
        String id=store.list().get(0).id;
        ServiceController<PlaybackService> service=Robolectric.buildService(PlaybackService.class).create();
        Track old=new Track();old.guid="old-account-song";old.accessStatus=3;service.get().setQueueAndPlay(Collections.singletonList(old),0);
        Intent intent=new Intent(context,LoginActivity.class).putExtra("manage_accounts",true);
        ActivityController<LoginActivity> controller=Robolectric.buildActivity(LoginActivity.class,intent).create();
        try{
            LoginActivity activity=controller.get();Field accounts=LoginActivity.class.getDeclaredField("accounts");accounts.setAccessible(true);accounts.set(activity,store);
            Method refresh=LoginActivity.class.getDeclaredMethod("refreshAccounts");refresh.setAccessible(true);refresh.invoke(activity);
            View button=null;
            for(int i=0;i<200&&button==null;i++){shadowOf(Looper.getMainLooper()).idle();button=activity.findViewById(R.id.saved_accounts).findViewWithTag("account_"+id);Thread.sleep(10);}
            assertNotNull(button);
            nas.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{\"userToken\":\"new-token\"}}"));button.performClick();
            for(int i=0;i<300&&!"bob".equals(api.session().getUsername());i++){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}
            assertEquals("bob",api.session().getUsername());assertEquals("new-token",api.session().getToken());
            assertTrue(service.get().getQueueSnapshot().isEmpty());
            assertEquals("",((EditText)activity.findViewById(R.id.password_input)).getText().toString());
            assertEquals(1,nas.getRequestCount());assertFalse(nas.takeRequest().getBody().readUtf8().contains("saved-password"));
        }finally{controller.destroy();service.destroy();nas.shutdown();}
    }

    @Test public void addClearsFormAndDeletionRequiresConfirmationAndPreservesOtherAccounts() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("saved_accounts", 0).edit().clear().commit();
        AccountStore store = TestAccountStores.create(context);
        store.save("https://example.invalid", "alice", "secret-a");
        store.save("https://example.invalid", "bob", "secret-b");
        String id = store.list().get(0).id;
        Intent intent = new Intent(context, LoginActivity.class).putExtra("manage_accounts", true);
        ActivityController<LoginActivity> controller = Robolectric.buildActivity(LoginActivity.class, intent).create();
        try {
            LoginActivity activity = controller.get();
            Field accounts = LoginActivity.class.getDeclaredField("accounts");
            accounts.setAccessible(true); accounts.set(activity, store);
            Method refresh = LoginActivity.class.getDeclaredMethod("refreshAccounts");
            refresh.setAccessible(true); refresh.invoke(activity);
            View delete = waitForTag(activity, "delete_account_" + id);
            assertNotNull(delete);
            ((EditText) activity.findViewById(R.id.username_input)).setText("draft-user");
            ((EditText) activity.findViewById(R.id.password_input)).setText("draft-password");
            waitForTag(activity, "add_account").performClick();
            assertEquals("", ((EditText) activity.findViewById(R.id.username_input)).getText().toString());
            assertEquals("", ((EditText) activity.findViewById(R.id.password_input)).getText().toString());
            delete.performClick();
            ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
            assertEquals(2, store.list().size());
            delete.performClick();
            ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            for (int i = 0; i < 200 && store.list().size() != 1; i++) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10);
            }
            assertEquals(1, store.list().size());
            assertEquals("alice", store.list().get(0).username);
        } finally { controller.destroy(); }
    }

    private static View waitForTag(LoginActivity activity, String tag) throws Exception {
        View result = null;
        for (int i = 0; i < 200 && result == null; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            result = activity.findViewById(R.id.saved_accounts).findViewWithTag(tag);
            if (result == null) Thread.sleep(10);
        }
        return result;
    }
}
