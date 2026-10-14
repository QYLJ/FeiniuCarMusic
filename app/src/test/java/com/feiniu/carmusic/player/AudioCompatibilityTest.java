package com.feiniu.carmusic.player;
import com.feiniu.carmusic.model.Track;
import org.junit.Test;
import static org.junit.Assert.*;
public class AudioCompatibilityTest {
    @Test public void androidSevenKeepsStandardAudioAndTranscodesUnsupportedOrHiResInputs() {
        Track track = new Track(); track.channels = 2; track.sampleRate = 44100; track.bitDepth = 16;
        track.codec = "mp3"; track.format = "mp3";
        assertEquals("", AudioCompatibility.transcodeCodec(track, AudioCompatibility.AUTO, 25));
        track.codec = "flac"; track.format = "flac";
        assertEquals("", AudioCompatibility.transcodeCodec(track, AudioCompatibility.AUTO, 25));
        track.format = "m4a";
        assertEquals("mp3", AudioCompatibility.transcodeCodec(track, AudioCompatibility.AUTO, 25));
        track.format = "flac"; track.bitDepth = 24; track.sampleRate = 96000;
        assertEquals("mp3", AudioCompatibility.transcodeCodec(track, AudioCompatibility.AUTO, 25));
        track.codec = "alac"; track.format = "m4a";
        assertEquals("mp3", AudioCompatibility.transcodeCodec(track, AudioCompatibility.AUTO, 25));
        assertEquals("flac", AudioCompatibility.transcodeCodec(track, AudioCompatibility.NAS_FLAC, 25));
    }
    @Test public void oldSystemsPreferCompatibleNasOutputForUnsupportedCodecsAndContainers() {
        Track track = new Track(); track.codec = "opus";
        assertEquals("mp3", AudioCompatibility.transcodeCodec(track, AudioCompatibility.AUTO, 14));
        assertEquals("", AudioCompatibility.transcodeCodec(track, AudioCompatibility.AUTO, 21));
        track.codec = "flac"; track.format = "mp4";
        assertEquals("mp3", AudioCompatibility.transcodeCodec(track, AudioCompatibility.AUTO, 28));
        assertEquals("", AudioCompatibility.transcodeCodec(track, AudioCompatibility.AUTO, 29));
        track.codec = "alac";
        assertEquals("mp3", AudioCompatibility.transcodeCodec(track, AudioCompatibility.AUTO, 35));
    }
    @Test public void multichannelAndHiResCanTranscodeWhileExplicitSourceChoiceIsRespected() {
        Track track = new Track(); track.codec = "flac"; track.bitDepth = 24; track.sampleRate = 96000;
        assertEquals("mp3", AudioCompatibility.transcodeCodec(track, AudioCompatibility.AUTO, 19));
        assertEquals("", AudioCompatibility.transcodeCodec(track, AudioCompatibility.ORIGINAL, 19));
        assertEquals("flac", AudioCompatibility.transcodeCodec(track, AudioCompatibility.NAS_FLAC, 19));
        track.codec = "aac"; track.channels = 6;
        assertEquals("mp3", AudioCompatibility.transcodeCodec(track, AudioCompatibility.AUTO, 35));
    }
}
