package com.feiniu.carmusic.feedback;

import android.content.Context;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Explicitly gated smoke test that leaves one encrypted acceptance report on the live AList. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class LiveAlistFeedbackTest {
    @Test public void encryptedEvidenceReachesLiveAlist() throws Exception {
        Assume.assumeTrue("run with -PliveFeedbackTest=true",
                Boolean.parseBoolean(System.getProperty("feiniu.liveFeedback", "false")));
        Context context = RuntimeEnvironment.getApplication();
        FeedbackRepository.Pending pending = FeedbackRepository.prepare(context, "其他",
                "反馈通道自动验收：验证运行日志、证据加密、AList 上传和回执链路。",
                "", true);
        AlistFeedbackClient.UploadReceipt receipt = FeedbackRepository.upload(context, pending);
        assertEquals(pending.id, receipt.reportId);
        assertTrue(receipt.remotePath.startsWith("/feedback/FeiniuCarMusic-feedback-"));
        assertTrue(receipt.remotePath.endsWith(".fnfeedback"));
        assertTrue(receipt.bytes > 300);
        assertEquals(64, receipt.sha256.length());
        assertFalse(pending.file.exists());
        System.out.println("LIVE_FEEDBACK_REPORT_ID=" + receipt.reportId);
        System.out.println("LIVE_FEEDBACK_REMOTE_PATH=" + receipt.remotePath);
        System.out.println("LIVE_FEEDBACK_BYTES=" + receipt.bytes);
        System.out.println("LIVE_FEEDBACK_SHA256=" + receipt.sha256);
    }
}
