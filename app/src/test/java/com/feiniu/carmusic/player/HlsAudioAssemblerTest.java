package com.feiniu.carmusic.player;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Offline fixtures are real FFmpeg outputs; their decoded PCM was verified independently. */
public class HlsAudioAssemblerTest {
    private static final String FLAC_SHA = "4a0fcf08a32ec0adcfc995efe50ee09d715e4034f3a0695670d349a7fc13093c";
    private static final String MP3_SHA = "030ff0985af08ddbd5b2694bf1ff0c0c1faae4b11b500d4387ea8351ba54ad35";
    private static final String AAC_SHA = "e3991337c37f7aa5227275dbf30a8bf31c5688a34c41f5526b1de598af5a8409";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void realFlacFragmentsBecomeNativeFlacWithAccurateStreamInfo() throws Exception {
        File target = temporary.newFile();
        try (HlsAudioAssembler assembler = new HlsAudioAssembler(target, "flac")) {
            assemble(assembler, "flac");
            assertEquals("flac", assembler.getCodec());
            assertEquals(44100, assembler.getSampleRate());
            assertEquals(16, assembler.getBitDepth());
            assertEquals(2, assembler.getChannels());
            assertEquals(44100, assembler.getTotalSamples());
            assertEquals(12399, assembler.getWrittenBytes());
            assertEquals(0, assembler.getBitrateKbps());
        }
        byte[] audio = Files.readAllBytes(target.toPath());
        assertEquals(FLAC_SHA, sha256(audio));
        assertEquals("fLaC", new String(audio, 0, 4, "US-ASCII"));
        // Android 4.0.4 SniffFLAC requires this exact eight-byte prefix, including flag 0.
        assertArrayEquals(new byte[]{'f', 'L', 'a', 'C', 0, 0, 0, 34}, Arrays.copyOf(audio, 8));
        assertArrayEquals(new byte[]{(byte) 0x81, 0, 0, 0}, Arrays.copyOfRange(audio, 42, 46));
        assertEquals(44100, read64(audio, 18) & ((1L << 36) - 1));
        assertArrayEquals(new byte[16], Arrays.copyOfRange(audio, 26, 42)); // Unknown PCM MD5.
    }

    @Test public void realMp3FragmentsPreserveEveryMpegFrame() throws Exception {
        File target = temporary.newFile();
        try (HlsAudioAssembler assembler = new HlsAudioAssembler(target, "mp3")) {
            assemble(assembler, "mp3");
            assertEquals("mp3", assembler.getCodec());
            assertEquals(44100, assembler.getSampleRate());
            assertEquals(2, assembler.getChannels());
            assertEquals(46080, assembler.getTotalSamples());
            assertEquals(192, assembler.getBitrateKbps());
        }
        assertEquals(MP3_SHA, sha256(Files.readAllBytes(target.toPath())));
    }

    @Test public void realAacAccessUnitsReceiveValidAdtsHeaders() throws Exception {
        File target = temporary.newFile();
        try (HlsAudioAssembler assembler = new HlsAudioAssembler(target, "aac-lc")) {
            assemble(assembler, "aac");
            assertEquals("aac", assembler.getCodec());
            assertEquals(44100, assembler.getSampleRate());
            assertEquals(2, assembler.getChannels());
            assertEquals(46080, assembler.getTotalSamples());
        }
        assertEquals(AAC_SHA, sha256(Files.readAllBytes(target.toPath())));
    }

    @Test public void actualMp3BitrateIsAvailableBeforeFinishAfterTheFirstSegment() throws Exception {
        File target = temporary.newFile();
        try (HlsAudioAssembler assembler = new HlsAudioAssembler(target, "mp3")) {
            assertEquals(0, assembler.getBitrateKbps());
            assembler.acceptInitialization(fixture("mp3-init.mp4"));
            assertEquals(0, assembler.getBitrateKbps());
            assembler.appendSegment(fixture("mp3-0.m4s"));
            assertEquals(192, assembler.getBitrateKbps());
            assertEquals(11520, assembler.getTotalSamples());
            assertEquals(6269, assembler.getWrittenBytes());
        }
        assertFalse(target.exists()); // Reading progress metadata cannot mark the cache complete.
    }

    @Test public void packedNativeFlacWithAdditionalMetadataIsNormalized() throws Exception {
        File target = temporary.newFile();
        try (HlsAudioAssembler assembler = new HlsAudioAssembler(target, "flac")) {
            assembler.appendSegment(fixture("packed.flac"));
            assembler.finish();
            assertEquals(44100, assembler.getTotalSamples());
        }
        assertEquals(FLAC_SHA, sha256(Files.readAllBytes(target.toPath())));
    }

    @Test public void flacMetadataMapAndPackedContinuationFramesAreAccepted() throws Exception {
        byte[] nativeFlac = fixture("packed.flac");
        int audioStart = nativeFlacAudioStart(nativeFlac);
        File target = temporary.newFile();
        try (HlsAudioAssembler assembler = new HlsAudioAssembler(target, "flac")) {
            assembler.acceptInitialization(Arrays.copyOf(nativeFlac, audioStart));
            assembler.appendSegment(Arrays.copyOfRange(nativeFlac, audioStart, nativeFlac.length));
            assembler.finish();
        }
        assertEquals(FLAC_SHA, sha256(Files.readAllBytes(target.toPath())));
    }

    @Test public void packedMp3SkipsPerSegmentId3Metadata() throws Exception {
        byte[] mp3 = fixture("packed.mp3");
        int firstFrameSize = 626; // The fixture starts with MPEG1 192 kbps / 44.1 kHz.
        byte[] id3 = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 3, 't', 'a', 'g'};
        File target = temporary.newFile();
        try (HlsAudioAssembler assembler = new HlsAudioAssembler(target, "mp3")) {
            assembler.appendSegment(concat(id3, Arrays.copyOf(mp3, firstFrameSize)));
            assertEquals(192, assembler.getBitrateKbps());
            assembler.appendSegment(concat(id3, Arrays.copyOfRange(mp3, firstFrameSize, mp3.length)));
            assembler.finish();
            assertEquals(192, assembler.getBitrateKbps());
        }
        assertEquals(MP3_SHA, sha256(Files.readAllBytes(target.toPath())));
    }

    @Test public void packedAacIsPreservedAndDetectedWithoutCodecHint() throws Exception {
        File target = temporary.newFile();
        try (HlsAudioAssembler assembler = new HlsAudioAssembler(target, null)) {
            assembler.appendSegment(fixture("packed.aac"));
            assembler.finish();
            assertEquals("aac", assembler.getCodec());
        }
        assertEquals(AAC_SHA, sha256(Files.readAllBytes(target.toPath())));
    }

    @Test public void interruptedAssemblyIsDeletedAndCannotBeMistakenForCompleteCache() throws Exception {
        File target = temporary.newFile();
        HlsAudioAssembler assembler = new HlsAudioAssembler(target, "flac");
        assembler.acceptInitialization(fixture("flac-init.mp4"));
        assembler.appendSegment(fixture("flac-0.m4s"));
        assertTrue(target.length() > 42);
        assembler.close();
        assertFalse(target.exists());
    }

    @Test public void missingInitializationAndCodecMismatchFailWithoutLeavingAudio() throws Exception {
        expectFailure("flac", null, fixture("flac-0.m4s"));
        expectFailure("mp3", fixture("flac-init.mp4"), null);
        expectFailure("flac", null, fixture("packed.mp3"));
    }

    @Test public void malformedBoxesAndSampleRangesAreRejected() throws Exception {
        byte[] oversized = fixture("flac-init.mp4");
        put32(oversized, 0, 0xffffffffL);
        expectFailure("flac", oversized, null);
        byte[] truncated = Arrays.copyOf(fixture("flac-init.mp4"), 19);
        expectFailure("flac", truncated, null);
        byte[] badOffset = fixture("flac-0.m4s");
        put32(badOffset, findType(badOffset, "trun") + 12, 0x7fffffff);
        expectFailure("flac", fixture("flac-init.mp4"), badOffset);
        byte[] badSize = fixture("flac-0.m4s");
        put32(badSize, findType(badSize, "trun") + 16, 0x7fffffff);
        expectFailure("flac", fixture("flac-init.mp4"), badSize);
        expectFailure("flac", fixture("flac-init.mp4"), Arrays.copyOf(fixture("flac-0.m4s"), 200));
    }

    @Test public void trackMismatchEncryptionAndUnsupportedCodecAreRejected() throws Exception {
        byte[] wrongTrack = fixture("flac-0.m4s");
        put32(wrongTrack, findType(wrongTrack, "tfhd") + 8, 2);
        expectFailure("flac", fixture("flac-init.mp4"), wrongTrack);
        byte[] encrypted = fixture("flac-init.mp4");
        System.arraycopy("enca".getBytes("US-ASCII"), 0, encrypted, findType(encrypted, "fLaC"), 4);
        expectFailure("flac", encrypted, null);
        byte[] unsupported = fixture("flac-init.mp4");
        System.arraycopy("Opus".getBytes("US-ASCII"), 0, unsupported, findType(unsupported, "fLaC"), 4);
        expectFailure(null, unsupported, null);
        byte[] encryptedSegment = fixture("flac-0.m4s");
        System.arraycopy("senc".getBytes("US-ASCII"), 0, encryptedSegment, findType(encryptedSegment, "tfdt"), 4);
        expectFailure("flac", fixture("flac-init.mp4"), encryptedSegment);
    }

    @Test public void multipleTracksAndHeAacConfigurationAreRejected() throws Exception {
        byte[] initialization = fixture("flac-init.mp4");
        int trak = findType(initialization, "trak") - 4;
        int trakSize = read32(initialization, trak);
        byte[] multiTrack = new byte[initialization.length + trakSize];
        System.arraycopy(initialization, 0, multiTrack, 0, trak + trakSize);
        System.arraycopy(initialization, trak, multiTrack, trak + trakSize, trakSize);
        System.arraycopy(initialization, trak + trakSize, multiTrack, trak + 2 * trakSize,
                initialization.length - trak - trakSize);
        int moov = findType(initialization, "moov") - 4;
        put32(multiTrack, moov, read32(initialization, moov) + trakSize);
        expectFailure("flac", multiTrack, null);

        byte[] aac = fixture("aac-init.mp4");
        int asc = -1;
        for (int i = findType(aac, "esds"); i < aac.length - 4; i++) {
            if (aac[i] == 0x12 && aac[i + 1] == 0x10 && aac[i + 2] == 0x56) { asc = i; break; }
        }
        assertTrue(asc > 0);
        aac[asc] = (byte) ((aac[asc] & 7) | (5 << 3)); // HE-AAC AudioObjectType = 5.
        expectFailure("aac", aac, null);
    }

    @Test public void missingOrRepeatedSegmentsCannotFinishSuccessfully() throws Exception {
        File target = temporary.newFile();
        try (HlsAudioAssembler assembler = new HlsAudioAssembler(target, "mp3")) {
            assembler.acceptInitialization(fixture("mp3-init.mp4"));
            assembler.appendSegment(fixture("mp3-0.m4s"));
            assertThrows(IOException.class, () -> assembler.appendSegment(fixture("mp3-2.m4s")));
            assertThrows(IOException.class, assembler::finish);
        }
        assertFalse(target.exists());
        target = temporary.newFile();
        try (HlsAudioAssembler assembler = new HlsAudioAssembler(target, "flac")) {
            assembler.acceptInitialization(fixture("flac-init.mp4"));
            assembler.appendSegment(fixture("flac-0.m4s"));
            assertThrows(IOException.class, () -> assembler.appendSegment(fixture("flac-0.m4s")));
            assertThrows(IOException.class, assembler::finish);
        }
        assertFalse(target.exists());
    }

    @Test public void corruptedFlacCannotProduceASuccessfulFile() throws Exception {
        byte[] corrupted = fixture("flac-0.m4s");
        corrupted[corrupted.length - 3] ^= 1;
        expectFailure("flac", fixture("flac-init.mp4"), corrupted);
        byte[] packed = fixture("packed.flac");
        packed[packed.length - 3] ^= 1;
        expectFailure("flac", null, packed);
    }

    @Test public void independentFlacStreamsAndTruncatedPackedFramesAreRejected() throws Exception {
        File target = temporary.newFile();
        try (HlsAudioAssembler assembler = new HlsAudioAssembler(target, "flac")) {
            assembler.appendSegment(fixture("packed.flac"));
            assertThrows(IOException.class, () -> assembler.appendSegment(fixture("packed.flac")));
            assertThrows(IOException.class, assembler::finish);
        }
        assertFalse(target.exists());
        byte[] mp3 = fixture("packed.mp3");
        expectFailure("mp3", null, Arrays.copyOf(mp3, mp3.length - 1));
        byte[] aac = fixture("packed.aac");
        expectFailure("aac", null, Arrays.copyOf(aac, aac.length - 1));
    }

    @Test public void emptyTranscodeResultsCannotCreateCompletedCache() throws Exception {
        File target = temporary.newFile();
        try (HlsAudioAssembler assembler = new HlsAudioAssembler(target, "flac")) {
            assembler.acceptInitialization(fixture("flac-init.mp4"));
            assembler.appendSegment(fixture("flac-4.m4s"));
            assertThrows(IOException.class, assembler::finish);
        }
        assertFalse(target.exists());
    }

    @Test public void inputsOverEightMebibytesAreRejectedBeforeParsing() throws Exception {
        expectFailure("flac", new byte[8 * 1024 * 1024 + 1], null);
        expectFailure("mp3", null, new byte[8 * 1024 * 1024 + 1]);
    }

    private void expectFailure(String codec, byte[] initialization, byte[] segment) throws Exception {
        File target = temporary.newFile();
        try (HlsAudioAssembler assembler = new HlsAudioAssembler(target, codec)) {
            assertThrows(IOException.class, () -> {
                if (initialization != null) assembler.acceptInitialization(initialization);
                if (segment != null) assembler.appendSegment(segment);
                assembler.finish();
            });
            assertThrows(IOException.class, assembler::finish);
        }
        assertFalse("Failed assembly must be removed", target.exists());
    }

    private static void assemble(HlsAudioAssembler assembler, String codec) throws Exception {
        assembler.acceptInitialization(fixture(codec + "-init.mp4"));
        for (int i = 0; i < 5; i++) assembler.appendSegment(fixture(codec + "-" + i + ".m4s"));
        assembler.finish();
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream input = HlsAudioAssemblerTest.class.getResourceAsStream("/hls-audio/" + name)) {
            if (input == null) throw new IOException("Missing fixture: " + name);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] result = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }

    private static int nativeFlacAudioStart(byte[] data) {
        int position = 4;
        boolean last;
        do {
            last = (data[position] & 0x80) != 0;
            int size = ((data[position + 1] & 0xff) << 16) | ((data[position + 2] & 0xff) << 8)
                    | (data[position + 3] & 0xff);
            position += 4 + size;
        } while (!last);
        return position;
    }

    private static int findType(byte[] data, String type) {
        for (int i = 4; i <= data.length - 4; i++) {
            boolean equal = true;
            for (int j = 0; j < 4; j++) if (data[i + j] != type.charAt(j)) equal = false;
            if (equal) return i;
        }
        throw new AssertionError("Fixture has no " + type);
    }

    private static void put32(byte[] data, int position, long value) {
        for (int i = 3; i >= 0; i--, value >>>= 8) data[position + i] = (byte) value;
    }

    private static long read64(byte[] data, int position) {
        long value = 0;
        for (int i = 0; i < 8; i++) value = (value << 8) | (data[position + i] & 0xff);
        return value;
    }

    private static int read32(byte[] data, int position) {
        int value = 0;
        for (int i = 0; i < 4; i++) value = (value << 8) | (data[position + i] & 0xff);
        return value;
    }
}
