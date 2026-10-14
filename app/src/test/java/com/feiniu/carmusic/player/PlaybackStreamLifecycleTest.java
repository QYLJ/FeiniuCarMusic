package com.feiniu.carmusic.player;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.android.controller.ServiceController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 25)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlaybackStreamLifecycleTest {
    @Test public void lateDownloadReadyAfterServiceDestructionClosesSourceWithoutReopeningPlayer() throws Exception {
        ServiceController<PlaybackService> controller = Robolectric.buildService(PlaybackService.class).create();
        PlaybackService service = controller.get();
        Field generation = PlaybackService.class.getDeclaredField("loadGeneration");
        generation.setAccessible(true);
        int oldGeneration = generation.getInt(service);
        File file = File.createTempFile("late-audio", ".mp3");
        AudioFile audio = new AudioFile(file, "mp3", 44100, 0, 2, true);
        ProgressiveAudioFile stream = new ProgressiveAudioFile(audio);
        try {
            controller.destroy();
            Method open = PlaybackService.class.getDeclaredMethod("openAudio", int.class, AudioFile.class, ProgressiveAudioFile.class);
            open.setAccessible(true);
            open.invoke(service, oldGeneration, audio, stream);
            try { stream.readAt(0, new byte[1], 0, 1); fail("Late source must be released"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("关闭")); }
        } finally { stream.close(); file.delete(); }
    }
}
