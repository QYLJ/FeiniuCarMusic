package com.feiniu.carmusic.player;

import java.io.IOException;
import java.io.RandomAccessFile;

/** Repackages MP4 Opus packets as Ogg Opus; no lossy audio conversion. RFC 7845 / dOps. */
final class OggOpusWriter {
    private final RandomAccessFile output;
    private int sequence;
    private long granule;
    private long lastPage = -1;
    private final int preSkip;
    private int lastPacketSamples;

    OggOpusWriter(RandomAccessFile output, byte[] config) throws IOException {
        this.output = output;
        if (config.length < 11 || config[0] != 0) throw new IOException("无效的 Opus dOps 配置");
        int channels = config[1] & 255, family = config[10] & 255;
        preSkip = ((config[2] & 255) << 8) | (config[3] & 255);
        if (channels < 1 || channels > 8 || family > 1 || family == 0 && (channels > 2 || config.length != 11))
            throw new IOException("不支持的 Opus 声道映射");
        if (family == 1) {
            if (config.length != 13 + channels) throw new IOException("Opus 声道映射被截断");
            int streams = config[11] & 255, coupled = config[12] & 255;
            if (streams == 0 || coupled > streams || streams + coupled > channels) throw new IOException("无效的 Opus 声道配置");
            for (int i = 13; i < config.length; i++) {
                int map = config[i] & 255;
                if (map != 255 && map >= streams + coupled) throw new IOException("无效的 Opus 声道索引");
            }
        }
        byte[] head = new byte[config.length + 8];
        System.arraycopy(new byte[] {'O','p','u','s','H','e','a','d'}, 0, head, 0, 8);
        head[8] = 1; head[9] = config[1];
        head[10] = config[3]; head[11] = config[2]; // pre-skip: MP4 big endian -> Ogg little endian
        for (int i = 0; i < 4; i++) head[12 + i] = config[7 - i];
        head[16] = config[9]; head[17] = config[8]; head[18] = config[10];
        if (family == 1) System.arraycopy(config, 11, head, 19, config.length - 11);
        page(head, 0, head.length, 2, 0);
        byte[] tags = new byte[] {'O','p','u','s','T','a','g','s', 0,0,0,0, 0,0,0,0};
        page(tags, 0, tags.length, 0, 0);
        lastPage = -1;
    }

    int append(byte[] packet, int start, int length) throws IOException {
        int samples = packetSamples(packet, start, length);
        lastPacketSamples = samples;
        granule += samples;
        page(packet, start, length, 0, granule);
        return samples;
    }

    long finish(long endGranule) throws IOException {
        if (lastPage < 0) throw new IOException("Opus 转码没有音频包");
        if (endGranule <= granule - lastPacketSamples || endGranule > granule || endGranule < preSkip)
            throw new IOException("无效的 Opus 尾部裁剪时间");
        long end = output.getFilePointer();
        int length = (int) (end - lastPage);
        byte[] page = new byte[length];
        output.seek(lastPage); output.readFully(page);
        page[5] |= 4; // Last audio page is EOS. Recalculate its checksum after changing flags.
        little(page, 6, endGranule, 8);
        checksum(page);
        output.seek(lastPage); output.write(page); output.seek(end);
        return endGranule - preSkip;
    }

    private void page(byte[] packet, int start, int size, int flags, long position) throws IOException {
        if (size > 65024) throw new IOException("Opus 音频包过大");
        int segments = size / 255 + 1;
        byte[] page = new byte[27 + segments + size];
        page[0] = 'O'; page[1] = 'g'; page[2] = 'g'; page[3] = 'S'; page[5] = (byte) flags;
        little(page, 6, position, 8);
        little(page, 14, 0x464e4f50L, 4); // A file contains exactly one logical Opus stream.
        little(page, 18, sequence++, 4);
        page[26] = (byte) segments;
        for (int i = 0; i < segments - 1; i++) page[27 + i] = (byte) 255;
        page[27 + segments - 1] = (byte) (size % 255);
        System.arraycopy(packet, start, page, 27 + segments, size);
        checksum(page);
        lastPage = output.getFilePointer();
        output.write(page);
    }

    static int packetSamples(byte[] data, int start, int size) throws IOException {
        if (size < 1 || start < 0 || start > data.length - size) throw new IOException("Opus 音频包被截断");
        int toc = data[start] & 255, config = toc >>> 3;
        int frames = (toc & 3) == 0 ? 1 : (toc & 3) == 3 ? (size > 1 ? data[start + 1] & 63 : 0) : 2;
        int perFrame = config >= 16 ? 120 << (config & 3) : config >= 12 ? 480 << (config & 1)
                : (config & 3) == 3 ? 2880 : 480 << (config & 3);
        int samples = perFrame * frames;
        if (frames == 0 || samples > 5760) throw new IOException("无效的 Opus 音频包时长");
        return samples;
    }
    private static void little(byte[] data, int start, long value, int size) {
        for (int i = 0; i < size; i++) data[start + i] = (byte) (value >>> (i * 8));
    }
    private static void checksum(byte[] page) {
        for (int i = 22; i < 26; i++) page[i] = 0;
        int crc = 0;
        for (byte b : page) {
            crc ^= (b & 255) << 24;
            for (int i = 0; i < 8; i++) crc = (crc << 1) ^ (crc < 0 ? 0x04c11db7 : 0);
        }
        little(page, 22, crc & 0xffffffffL, 4);
    }
}
