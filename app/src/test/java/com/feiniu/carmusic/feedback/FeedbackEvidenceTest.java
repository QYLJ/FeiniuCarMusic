package com.feiniu.carmusic.feedback;

import android.content.Context;

import com.feiniu.carmusic.diagnostics.AppLog;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class FeedbackEvidenceTest {
    @Test public void evidenceIncludesUsefulContextButNoStableDeviceIdentifier() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        AppLog.initialize(context);
        AppLog.info("test.event", "token=very-secret-token normal=kept");
        FeedbackEvidence.Report report = FeedbackEvidence.create(context, "播放问题", "声音突然停止", "", true);
        String json = new String(report.json, "UTF-8");
        assertTrue(json.contains("声音突然停止"));
        assertTrue(json.contains("runtimeLog"));
        assertTrue(json.contains("<redacted>"));
        assertFalse(json.contains("very-secret-token"));
        assertFalse(json.toLowerCase().contains("androidid"));
        assertFalse(json.toLowerCase().contains("serialnumber"));
    }
}
