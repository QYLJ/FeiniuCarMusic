package com.feiniu.carmusic.player;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Extracts a single, unencrypted NAS HLS audio stream into a normal audio file.
 * This is a demuxer, not a decoder: FLAC frames, MPEG Layer III frames and AAC-LC
 * access units retain their original audio. AAC access units receive ADTS headers.
 * An incomplete/failed assembly is deleted by close(); only finish() keeps it.
 * Segments must be complete, in order, and from the same rendition/session.
 */
public final class HlsAudioAssembler implements Closeable {
    private static final int MAX_INPUT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_BOXES = 10000;
    private static final int MAX_SAMPLES = 100000;
    private static final long MAX_FLAC_SAMPLES = (1L << 36) - 1;
    private static final int[] AAC_RATES = {
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350
    };

    private final File target;
    private final String requestedCodec;
    private final RandomAccessFile output;
    private String codec = "";
    private int sampleRate;
    private int bitDepth;
    private int channels;
    private int aacRateIndex = -1;
    private int mode; // 0: undecided, 1: fragmented MP4, 2: packed audio
    private long trackId;
    private long timescale;
    private long defaultDuration;
    private long defaultSize;
    private long expectedDecodeTime = -1;
    private long totalSamples;
    private long frameCount;
    private long writtenBytes;
    private byte[] streamInfo;
    private boolean finished;
    private boolean closed;
    private boolean failed;

    public HlsAudioAssembler(File target, String requestedCodec) throws IOException {
        if (target == null) throw new IOException("缺少转码缓存文件");
        this.target = target;
        this.requestedCodec = normalizeCodec(requestedCodec);
        if (!this.requestedCodec.isEmpty() && !isSupported(this.requestedCodec)) {
            throw new IOException("不支持的 NAS 转码编码：" + requestedCodec);
        }
        output = new RandomAccessFile(target, "rw");
        output.setLength(0);
    }

    public String getCodec() { return codec; }
    public int getSampleRate() { return sampleRate; }
    public int getBitDepth() { return bitDepth; }
    public int getChannels() { return channels; }
    public long getTotalSamples() { return totalSamples; }
    public long getWrittenBytes() { return writtenBytes; }

    /** Average encoded MP3 frame bitrate; available after the first complete segment. */
    public int getBitrateKbps() {
        if (!"mp3".equals(codec) || totalSamples == 0 || sampleRate == 0) return 0;
        // ID3 metadata is stripped, so writtenBytes contains only validated MPEG frames.
        // Use double arithmetic to avoid overflowing when a long stream is cached.
        long bitrate = Math.round(writtenBytes * 8.0 * sampleRate / totalSamples / 1000.0);
        return bitrate > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bitrate;
    }

    public void acceptInitialization(byte[] data) throws IOException {
        requireOpen();
        try {
            checkInput(data);
            if (mode != 0) throw invalid("重复或变更的 HLS 初始化数据");
            if (startsWith(data, 0, "fLaC")) {
                int start = readFlacMetadata(data, 4, data.length);
                mode = 2;
                if (start != data.length) appendPackedFlac(data, start);
            } else {
                readMp4Initialization(data);
                mode = 1;
            }
        } catch (IOException e) {
            failed = true;
            throw e;
        }
    }

    public void appendSegment(byte[] data) throws IOException {
        requireOpen();
        try {
            checkInput(data);
            if (mode == 1) {
                appendMp4(data);
            } else {
                if (mode == 0 && looksLikeMp4(data)) {
                    throw invalid("fMP4 分片缺少 EXT-X-MAP 初始化数据");
                }
                appendPacked(data);
                mode = 2;
            }
        } catch (IOException e) {
            failed = true;
            throw e;
        }
    }

    /** Fixes FLAC STREAMINFO, flushes and closes the completed audio file. */
    public void finish() throws IOException {
        requireOpen();
        try {
            if (frameCount == 0 || totalSamples == 0) throw invalid("转码结果不包含音频帧");
            if ("flac".equals(codec)) {
                if (totalSamples > MAX_FLAC_SAMPLES) throw invalid("FLAC 采样数超出格式限制");
                long parameters = read64(streamInfo, 10, streamInfo.length);
                parameters = (parameters & ~MAX_FLAC_SAMPLES) | totalSamples;
                output.seek(18); // fLaC (4) + metadata header (4) + STREAMINFO (10)
                output.writeLong(parameters);
            }
            output.getFD().sync();
            output.close();
            closed = true;
            finished = true;
        } catch (IOException e) {
            failed = true;
            throw e;
        }
    }

    @Override public void close() throws IOException {
        try {
            if (!closed) output.close();
        } finally {
            closed = true;
            if (!finished && target.exists() && !target.delete()) {
                throw new IOException("无法删除未完成的转码缓存");
            }
        }
    }

    private void requireOpen() throws IOException {
        if (closed || finished || failed) throw invalid("转码组装器已结束或失败");
    }

    private void readMp4Initialization(byte[] data) throws IOException {
        List<Box> top = boxes(data, 0, data.length);
        Box moov = unique(top, "moov", true);
        List<Box> movie = children(data, moov);
        Box trak = unique(movie, "trak", true); // Intentionally rejects multi-track renditions.
        List<Box> track = children(data, trak);
        Box tkhd = unique(track, "tkhd", true);
        int version = unsigned(data, tkhd.payload, tkhd.end);
        if (version > 1) throw invalid("未知 tkhd 版本");
        trackId = read32(data, tkhd.payload + (version == 1 ? 20 : 12), tkhd.end);
        if (trackId == 0) throw invalid("无效的音轨 ID");
        Box mdia = unique(track, "mdia", true);
        List<Box> media = children(data, mdia);
        Box handler = unique(media, "hdlr", true);
        requireRange(handler.payload, 12, handler.end);
        if (!startsWith(data, handler.payload + 8, "soun")) throw invalid("HLS 不是单音频流");
        Box mdhd = unique(media, "mdhd", true);
        version = unsigned(data, mdhd.payload, mdhd.end);
        if (version > 1) throw invalid("未知 mdhd 版本");
        timescale = read32(data, mdhd.payload + (version == 1 ? 20 : 12), mdhd.end);
        if (timescale == 0) throw invalid("无效的音轨时间基准");
        Box minf = unique(media, "minf", true);
        Box stbl = unique(children(data, minf), "stbl", true);
        Box stsd = unique(children(data, stbl), "stsd", true);
        if (read32(data, stsd.payload, stsd.end) != 0
                || read32(data, stsd.payload + 4, stsd.end) != 1) {
            throw invalid("不支持多种或变更的音轨编码");
        }
        List<Box> entries = boxes(data, stsd.payload + 8, stsd.end);
        if (entries.size() != 1) throw invalid("无效的音频编码描述");
        Box entry = entries.get(0);
        requireRange(entry.payload, 28, entry.end);
        if (read16(data, entry.payload + 8, entry.end) != 0) {
            throw invalid("不支持 QuickTime 扩展音频描述");
        }
        channels = read16(data, entry.payload + 16, entry.end);
        bitDepth = read16(data, entry.payload + 18, entry.end);
        sampleRate = (int) (read32(data, entry.payload + 24, entry.end) >>> 16);
        List<Box> configuration = boxes(data, entry.payload + 28, entry.end);
        if ("fLaC".equals(entry.type)) {
            Box dfla = unique(configuration, "dfLa", true);
            if (read32(data, dfla.payload, dfla.end) != 0) throw invalid("未知 dfLa 版本");
            if (readFlacMetadata(data, dfla.payload + 4, dfla.end) != dfla.end) {
                throw invalid("dfLa 元数据含有多余数据");
            }
        } else if ("mp4a".equals(entry.type)) {
            Box esds = unique(configuration, "esds", true);
            if (read32(data, esds.payload, esds.end) != 0) throw invalid("未知 esds 版本");
            readEsds(data, esds.payload + 4, esds.end);
        } else if (".mp3".equals(entry.type) || "mp3 ".equals(entry.type)) {
            selectCodec("mp3");
        } else {
            throw invalid("不支持的 fMP4 音频编码：" + entry.type);
        }
        Box mvex = unique(movie, "mvex", true);
        Box trex = unique(children(data, mvex), "trex", true);
        if (read32(data, trex.payload, trex.end) != 0
                || read32(data, trex.payload + 4, trex.end) != trackId
                || read32(data, trex.payload + 8, trex.end) != 1) {
            throw invalid("初始化音轨与分片默认描述不一致");
        }
        defaultDuration = read32(data, trex.payload + 12, trex.end);
        defaultSize = read32(data, trex.payload + 16, trex.end);
        read32(data, trex.payload + 20, trex.end);
        if (channels < 1 || channels > 8 || sampleRate <= 0) throw invalid("无效的音频参数");
    }

    private void readEsds(byte[] data, int start, int end) throws IOException {
        Descriptor es = descriptor(data, start, end);
        if (es.tag != 3) throw invalid("缺少 ES 编码描述");
        requireRange(es.start, 3, es.end);
        int flags = unsigned(data, es.start + 2, es.end);
        int position = es.start + 3;
        if ((flags & 0x80) != 0) position += 2;
        if ((flags & 0x40) != 0) {
            int urlLength = unsigned(data, position, es.end);
            position += 1 + urlLength;
        }
        if ((flags & 0x20) != 0) position += 2;
        Descriptor decoder = descriptor(data, position, es.end);
        if (decoder.tag != 4) throw invalid("缺少音频 DecoderConfig");
        requireRange(decoder.start, 13, decoder.end);
        int objectType = unsigned(data, decoder.start, decoder.end);
        if ((unsigned(data, decoder.start + 1, decoder.end) >>> 2) != 5) {
            throw invalid("ES 描述不是音频流");
        }
        if (objectType == 0x6b || objectType == 0x69) {
            selectCodec("mp3");
        } else if (objectType == 0x40) {
            Descriptor specific = descriptor(data, decoder.start + 13, decoder.end);
            if (specific.tag != 5) throw invalid("缺少 AAC AudioSpecificConfig");
            Bits bits = new Bits(data, specific.start, specific.end);
            int audioObject = bits.read(5);
            aacRateIndex = bits.read(4);
            int channelConfig = bits.read(4);
            if (audioObject != 2 || aacRateIndex >= AAC_RATES.length
                    || channelConfig < 1 || channelConfig > 2
                    || bits.read(1) != 0 || bits.read(1) != 0 || bits.read(1) != 0) {
                throw invalid("仅支持标准 AAC-LC 单声道或双声道");
            }
            if (bits.remaining() >= 17 && bits.read(11) == 0x2b7) {
                if (bits.read(5) != 5 || bits.read(1) != 0) throw invalid("不支持 HE-AAC/SBR");
            }
            sampleRate = AAC_RATES[aacRateIndex];
            channels = channelConfig;
            bitDepth = 16;
            selectCodec("aac");
        } else {
            throw invalid("不支持的 MPEG 音频 ObjectType：" + objectType);
        }
    }

    private void appendMp4(byte[] data) throws IOException {
        List<Box> top = boxes(data, 0, data.length);
        if (unique(top, "moov", false) != null) throw invalid("分片中出现新的初始化数据");
        List<Box> mdats = matching(top, "mdat");
        List<Box> moofs = matching(top, "moof");
        if (moofs.isEmpty()) {
            // FFmpeg can emit an empty trailing HLS segment containing only styp.
            if (top.size() == 1 && "styp".equals(top.get(0).type)) return;
            throw invalid("HLS 分片没有 moof 音频索引");
        }
        long previousEnd = -1;
        for (Box moof : moofs) {
            Box traf = unique(children(data, moof), "traf", true);
            List<Box> track = children(data, traf);
            Box tfhd = unique(track, "tfhd", true);
            int full = (int) read32(data, tfhd.payload, tfhd.end);
            int flags = full & 0xffffff;
            if ((full >>> 24) != 0 || (flags & ~0x03003b) != 0
                    || read32(data, tfhd.payload + 4, tfhd.end) != trackId) {
                throw invalid("分片音轨 ID 或 tfhd 描述无效");
            }
            int position = tfhd.payload + 8;
            long baseOffset = moof.start;
            if ((flags & 1) != 0) {
                baseOffset = read64(data, position, tfhd.end);
                position += 8;
                if (baseOffset < 0 || baseOffset > data.length) throw invalid("分片引用外部数据偏移");
            }
            if ((flags & 2) != 0) {
                if (read32(data, position, tfhd.end) != 1) throw invalid("分片变更了音频编码");
                position += 4;
            }
            long duration = defaultDuration;
            long size = defaultSize;
            if ((flags & 8) != 0) { duration = read32(data, position, tfhd.end); position += 4; }
            if ((flags & 16) != 0) { size = read32(data, position, tfhd.end); position += 4; }
            if ((flags & 32) != 0) { read32(data, position, tfhd.end); position += 4; }
            if (position != tfhd.end) throw invalid("无效的 tfhd 长度");
            Box tfdt = unique(track, "tfdt", true);
            int tfdtVersion = unsigned(data, tfdt.payload, tfdt.end);
            if (read24(data, tfdt.payload + 1, tfdt.end) != 0) throw invalid("未知 tfdt 标记");
            long decodeTime;
            if (tfdtVersion == 0) decodeTime = read32(data, tfdt.payload + 4, tfdt.end);
            else if (tfdtVersion == 1) decodeTime = read64(data, tfdt.payload + 4, tfdt.end);
            else throw invalid("未知 tfdt 版本");
            if (decodeTime < 0 || (expectedDecodeTime >= 0 && decodeTime != expectedDecodeTime)) {
                throw invalid("HLS 音频分片缺失、重复或顺序错误");
            }
            List<Box> runs = matching(track, "trun");
            if (runs.isEmpty()) throw invalid("分片缺少 trun 音频索引");
            long nextOffset = baseOffset;
            for (Box run : runs) {
                int runFull = (int) read32(data, run.payload, run.end);
                int runFlags = runFull & 0xffffff;
                if ((runFull >>> 24) > 1 || (runFlags & ~0xf05) != 0) throw invalid("未知 trun 标记");
                long count = read32(data, run.payload + 4, run.end);
                if (count > MAX_SAMPLES || ((flags & 0x10000) != 0 && count != 0)) {
                    throw invalid("分片音频帧数量异常");
                }
                position = run.payload + 8;
                if ((runFlags & 1) != 0) {
                    nextOffset = baseOffset + (int) read32(data, position, run.end);
                    position += 4;
                }
                if ((runFlags & 4) != 0) { read32(data, position, run.end); position += 4; }
                for (int i = 0; i < count; i++) {
                    long sampleDuration = duration;
                    long sampleSize = size;
                    if ((runFlags & 0x100) != 0) { sampleDuration = read32(data, position, run.end); position += 4; }
                    if ((runFlags & 0x200) != 0) { sampleSize = read32(data, position, run.end); position += 4; }
                    if ((runFlags & 0x400) != 0) { read32(data, position, run.end); position += 4; }
                    if ((runFlags & 0x800) != 0) {
                        if (read32(data, position, run.end) != 0) throw invalid("音频包含不支持的重排时间戳");
                        position += 4;
                    }
                    if (sampleSize <= 0 || sampleDuration <= 0 || sampleSize > MAX_INPUT_BYTES
                            || nextOffset < previousEnd || !insideMdat(mdats, nextOffset, sampleSize)) {
                        throw invalid("trun 音频帧引用超出 mdat 范围");
                    }
                    int samples = appendAccessUnit(data, (int) nextOffset, (int) sampleSize);
                    if ("flac".equals(codec) && samples * timescale != sampleDuration * sampleRate) {
                        throw invalid("FLAC 帧采样数与分片时长不一致");
                    }
                    nextOffset += sampleSize;
                    previousEnd = nextOffset;
                    if (Long.MAX_VALUE - decodeTime < sampleDuration) throw invalid("音频时间戳溢出");
                    decodeTime += sampleDuration;
                }
                if (position != run.end) throw invalid("无效的 trun 长度");
            }
            expectedDecodeTime = decodeTime;
        }
    }

    private int appendAccessUnit(byte[] data, int start, int size) throws IOException {
        int samples;
        if ("flac".equals(codec)) {
            samples = validateFlacFrame(data, start, start + size);
        } else if ("mp3".equals(codec)) {
            AudioFrame frame = mp3Frame(data, start, start + size);
            if (frame.size != size) throw invalid("fMP4 MP3 样本不是完整的一帧");
            checkAudioParameters(frame);
            samples = frame.samples;
        } else if ("aac".equals(codec)) {
            if (size > 8184) throw invalid("AAC 音频帧超过 ADTS 大小限制");
            int length = size + 7;
            byte[] header = {(byte) 0xff, (byte) 0xf1,
                    (byte) ((1 << 6) | (aacRateIndex << 2) | (channels >> 2)),
                    (byte) (((channels & 3) << 6) | (length >> 11)),
                    (byte) (length >> 3), (byte) (((length & 7) << 5) | 0x1f), (byte) 0xfc};
            write(header, 0, header.length);
            samples = 1024;
        } else {
            throw invalid("缺少受支持的音频编码配置");
        }
        write(data, start, size);
        addSamples(samples);
        return samples;
    }

    private void appendPacked(byte[] data) throws IOException {
        int start = skipId3(data, 0);
        if (startsWith(data, start, "fLaC")) {
            if (frameCount > 0 || streamInfo != null) throw invalid("不支持重新开始计数的独立 FLAC 分片");
            start = readFlacMetadata(data, start + 4, data.length);
        }
        if ("flac".equals(codec)) {
            if (streamInfo == null) throw invalid("FLAC 分片缺少 STREAMINFO");
            appendPackedFlac(data, start);
            return;
        }
        if (start == data.length) throw invalid("分片只有元数据，没有音频");
        if (codec.isEmpty()) {
            // MPEG Layer III and ADTS headers are disjoint once the layer bits are checked.
            if ((unsigned(data, start + 1, data.length) & 6) == 2) selectCodec("mp3");
            else selectCodec("aac");
        }
        while (start < data.length) {
            if (startsWith(data, start, "TAG") && data.length - start == 128) return;
            AudioFrame frame;
            if ("mp3".equals(codec)) frame = mp3Frame(data, start, data.length);
            else if ("aac".equals(codec)) frame = adtsFrame(data, start, data.length);
            else throw invalid("不支持的 packed 音频分片");
            checkAudioParameters(frame);
            write(data, start, frame.size);
            addSamples(frame.samples);
            start += frame.size;
        }
    }

    private void appendPackedFlac(byte[] data, int start) throws IOException {
        while (start < data.length) {
            FlacHeader header = flacHeader(data, start, data.length);
            int crc = 0;
            int end = start;
            boolean found = false;
            while (end < data.length) {
                crc = crc16(crc, data[end++] & 0xff);
                if (end >= header.end + 2 && crc == 0 && (end == data.length || isFlacHeader(data, end))) {
                    found = true;
                    break;
                }
            }
            if (!found) throw invalid("FLAC 分片缺帧、被截断或 CRC 错误");
            appendAccessUnit(data, start, end - start);
            start = end;
        }
    }

    private int readFlacMetadata(byte[] data, int start, int end) throws IOException {
        selectCodec("flac");
        boolean last = false;
        boolean first = true;
        while (!last) {
            requireRange(start, 4, end);
            int kind = data[start] & 0x7f;
            last = (data[start] & 0x80) != 0;
            int size = read24(data, start + 1, end);
            start += 4;
            requireRange(start, size, end);
            if (kind == 127 || (first && (kind != 0 || size != 34)) || (!first && kind == 0)) {
                throw invalid("FLAC STREAMINFO 元数据无效");
            }
            if (first) {
                streamInfo = Arrays.copyOfRange(data, start, start + size);
                long parameters = read64(streamInfo, 10, streamInfo.length);
                sampleRate = (int) (parameters >>> 44);
                channels = (int) ((parameters >>> 41) & 7) + 1;
                bitDepth = (int) ((parameters >>> 36) & 31) + 1;
                int minBlock = read16(streamInfo, 0, streamInfo.length);
                int maxBlock = read16(streamInfo, 2, streamInfo.length);
                if (sampleRate == 0 || bitDepth < 4 || minBlock < 16 || maxBlock < minBlock) {
                    throw invalid("FLAC 音频参数无效");
                }
                streamInfo[13] &= (byte) 0xf0;
                Arrays.fill(streamInfo, 14, 34, (byte) 0); // Unknown totalSamples and MD5 while assembling.
                // Android 4.0's FLAC sniffer compares all eight initial bytes with
                // fLaC 00 00 00 22 and rejects an otherwise valid final STREAMINFO
                // (80 00 00 22). End the metadata with a legal empty PADDING block.
                write(new byte[]{'f', 'L', 'a', 'C', 0, 0, 0, 34}, 0, 8);
                write(streamInfo, 0, streamInfo.length);
                write(new byte[]{(byte) 0x81, 0, 0, 0}, 0, 4);
            }
            first = false;
            start += size;
        }
        return start;
    }

    private int validateFlacFrame(byte[] data, int start, int end) throws IOException {
        FlacHeader header = flacHeader(data, start, end);
        if (end < header.end + 2) throw invalid("FLAC 音频帧被截断");
        if (header.number != (header.variable ? totalSamples : frameCount)) {
            throw invalid("FLAC 音频帧缺失、重复或顺序错误");
        }
        int crc = 0;
        for (int i = start; i < end; i++) crc = crc16(crc, data[i] & 0xff);
        if (crc != 0) throw invalid("FLAC 音频帧 CRC 校验失败");
        return header.samples;
    }

    private FlacHeader flacHeader(byte[] data, int start, int end) throws IOException {
        requireRange(start, 6, end);
        int second = data[start + 1] & 0xff;
        int codes = data[start + 2] & 0xff;
        int format = data[start + 3] & 0xff;
        if ((data[start] & 0xff) != 0xff || (second & 0xfe) != 0xf8 || (format & 1) != 0) {
            throw invalid("无效的 FLAC 音频帧头");
        }
        int channelCode = format >>> 4;
        int frameChannels = channelCode <= 7 ? channelCode + 1 : channelCode <= 10 ? 2 : 0;
        int[] depths = {0, 8, 12, -1, 16, 20, 24, 32};
        int depth = depths[(format >>> 1) & 7];
        if (frameChannels != channels || depth < 0 || (depth != 0 && depth != bitDepth)) {
            throw invalid("FLAC 分片变更了音频参数");
        }
        int position = start + 4;
        int lead = unsigned(data, position++, end);
        long number;
        if (lead < 0x80) number = lead;
        else {
            int bytes = 0;
            for (int mask = 0x80; (lead & mask) != 0 && mask != 0; mask >>>= 1) bytes++;
            if (bytes < 2 || bytes > 7) throw invalid("无效的 FLAC 帧序号");
            number = lead & (0x7f >>> bytes);
            for (int i = 1; i < bytes; i++) {
                int next = unsigned(data, position++, end);
                if ((next & 0xc0) != 0x80) throw invalid("无效的 FLAC 帧序号");
                number = (number << 6) | (next & 0x3f);
            }
            long minimum = bytes == 2 ? 0x80 : 1L << (5 * bytes - 4);
            if (number < minimum) throw invalid("FLAC 帧序号包含过长编码");
        }
        if (number > ((second & 1) != 0 ? MAX_FLAC_SAMPLES : Integer.MAX_VALUE)) {
            throw invalid("FLAC 帧序号溢出");
        }
        int blockCode = codes >>> 4;
        int samples;
        if (blockCode == 0) throw invalid("未知的 FLAC 块大小");
        else if (blockCode == 1) samples = 192;
        else if (blockCode <= 5) samples = 576 << (blockCode - 2);
        else if (blockCode == 6) samples = unsigned(data, position++, end) + 1;
        else if (blockCode == 7) { samples = read16(data, position, end) + 1; position += 2; }
        else samples = 256 << (blockCode - 8);
        if (samples > read16(streamInfo, 2, streamInfo.length)) throw invalid("FLAC 帧超出声明的块大小");
        int rateCode = codes & 15;
        int[] rates = {0, 88200, 176400, 192000, 8000, 16000, 22050, 24000, 32000, 44100, 48000, 96000};
        int rate;
        if (rateCode < rates.length) rate = rates[rateCode];
        else if (rateCode == 12) rate = unsigned(data, position++, end) * 1000;
        else if (rateCode == 13 || rateCode == 14) {
            rate = read16(data, position, end) * (rateCode == 14 ? 10 : 1);
            position += 2;
        } else throw invalid("未知的 FLAC 采样率");
        if (rate != 0 && rate != sampleRate) throw invalid("FLAC 分片变更了采样率");
        int crc = 0;
        requireRange(position, 1, end);
        for (int i = start; i <= position; i++) {
            crc ^= data[i] & 0xff;
            for (int bit = 0; bit < 8; bit++) crc = ((crc << 1) ^ ((crc & 0x80) != 0 ? 7 : 0)) & 0xff;
        }
        if (crc != 0) throw invalid("FLAC 帧头 CRC 校验失败");
        return new FlacHeader(number, (second & 1) != 0, samples, position + 1);
    }

    private boolean isFlacHeader(byte[] data, int start) {
        try { flacHeader(data, start, data.length); return true; }
        catch (IOException ignored) { return false; }
    }

    private static AudioFrame mp3Frame(byte[] data, int start, int end) throws IOException {
        int header = (int) read32(data, start, end);
        int version = (header >>> 19) & 3;
        int layer = (header >>> 17) & 3;
        int bitrateIndex = (header >>> 12) & 15;
        int rateIndex = (header >>> 10) & 3;
        if ((header & 0xffe00000) != 0xffe00000 || version == 1 || layer != 1
                || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) {
            throw invalid("不支持或无效的 MP3 音频帧");
        }
        int[] rates = {44100, 48000, 32000};
        int[] high = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
        int[] low = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
        int rate = rates[rateIndex] / (version == 3 ? 1 : version == 2 ? 2 : 4);
        int size = (version == 3 ? 144000 : 72000) * (version == 3 ? high[bitrateIndex] : low[bitrateIndex])
                / rate + ((header >>> 9) & 1);
        requireRange(start, size, end);
        return new AudioFrame(size, version == 3 ? 1152 : 576, rate,
                ((header >>> 6) & 3) == 3 ? 1 : 2);
    }

    private static AudioFrame adtsFrame(byte[] data, int start, int end) throws IOException {
        requireRange(start, 7, end);
        int second = data[start + 1] & 0xff;
        int third = data[start + 2] & 0xff;
        int index = (third >>> 2) & 15;
        int channels = ((third & 1) << 2) | ((data[start + 3] >>> 6) & 3);
        int size = ((data[start + 3] & 3) << 11) | ((data[start + 4] & 0xff) << 3)
                | ((data[start + 5] >>> 5) & 7);
        int headerSize = (second & 1) == 0 ? 9 : 7;
        if ((data[start] & 0xff) != 0xff || (second & 0xf6) != 0xf0 || (third >>> 6) != 1
                || index >= AAC_RATES.length || channels < 1 || channels > 2
                || (data[start + 6] & 3) != 0 || size <= headerSize) {
            throw invalid("仅支持完整的 AAC-LC ADTS 音频帧");
        }
        requireRange(start, size, end);
        return new AudioFrame(size, 1024, AAC_RATES[index], channels);
    }

    private void checkAudioParameters(AudioFrame frame) throws IOException {
        if ((sampleRate != 0 && sampleRate != frame.rate) || (channels != 0 && channels != frame.channels)) {
            throw invalid("转码音频在分片间变更了参数");
        }
        sampleRate = frame.rate;
        channels = frame.channels;
        bitDepth = 16;
    }

    private void selectCodec(String value) throws IOException {
        if ((!codec.isEmpty() && !codec.equals(value))
                || (!requestedCodec.isEmpty() && !requestedCodec.equals(value))) {
            throw invalid("NAS 返回的编码与所选转码格式不一致：" + value);
        }
        codec = value;
    }

    private void addSamples(int samples) throws IOException {
        if (samples <= 0 || Long.MAX_VALUE - totalSamples < samples) throw invalid("音频采样数溢出");
        totalSamples += samples;
        frameCount++;
    }

    private void write(byte[] data, int start, int length) throws IOException {
        output.write(data, start, length);
        writtenBytes += length;
    }

    private static List<Box> children(byte[] data, Box parent) throws IOException {
        return boxes(data, parent.payload, parent.end);
    }

    private static List<Box> boxes(byte[] data, int start, int end) throws IOException {
        List<Box> result = new ArrayList<Box>();
        requireRange(start, end - start, data.length);
        while (start < end) {
            requireRange(start, 8, end);
            long size = read32(data, start, end);
            String type = new String(data, start + 4, 4, "US-ASCII");
            int headerSize = 8;
            if (size == 1) { size = read64(data, start + 8, end); headerSize = 16; }
            else if (size == 0) size = end - start;
            if (size < headerSize || size > end - start || result.size() >= MAX_BOXES) {
                throw invalid("MP4 box 长度或数量异常");
            }
            if ("senc".equals(type) || "saiz".equals(type) || "saio".equals(type)
                    || "pssh".equals(type) || "sinf".equals(type) || "enca".equals(type)) {
                throw invalid("不支持加密的 HLS 音频");
            }
            result.add(new Box(start, start + headerSize, start + (int) size, type));
            start += (int) size;
        }
        return result;
    }

    private static Box unique(List<Box> boxes, String type, boolean required) throws IOException {
        Box result = null;
        for (Box box : boxes) if (type.equals(box.type)) {
            if (result != null) throw invalid("不支持重复或多个 " + type + " box");
            result = box;
        }
        if (required && result == null) throw invalid("缺少 " + type + " box");
        return result;
    }

    private static List<Box> matching(List<Box> boxes, String type) {
        List<Box> result = new ArrayList<Box>();
        for (Box box : boxes) if (type.equals(box.type)) result.add(box);
        return result;
    }

    private static boolean insideMdat(List<Box> mdats, long start, long size) {
        for (Box box : mdats) if (start >= box.payload && start <= box.end && size <= box.end - start) return true;
        return false;
    }

    private static Descriptor descriptor(byte[] data, int start, int end) throws IOException {
        int tag = unsigned(data, start++, end);
        int length = 0;
        for (int i = 0; i < 4; i++) {
            int next = unsigned(data, start++, end);
            length = (length << 7) | (next & 0x7f);
            if ((next & 0x80) == 0) {
                requireRange(start, length, end);
                return new Descriptor(tag, start, start + length);
            }
        }
        throw invalid("ES 描述长度溢出");
    }

    private static int skipId3(byte[] data, int start) throws IOException {
        if (!startsWith(data, start, "ID3")) return start;
        requireRange(start, 10, data.length);
        int version = data[start + 3] & 0xff;
        if (version < 2 || version > 4) throw invalid("未知的 ID3 元数据版本");
        int size = 0;
        for (int i = start + 6; i < start + 10; i++) {
            if ((data[i] & 0x80) != 0) throw invalid("ID3 长度无效");
            size = (size << 7) | data[i];
        }
        size += 10 + (version == 4 && (data[start + 5] & 0x10) != 0 ? 10 : 0);
        requireRange(start, size, data.length);
        return start + size;
    }

    private static boolean looksLikeMp4(byte[] data) {
        return startsWith(data, 4, "ftyp") || startsWith(data, 4, "styp")
                || startsWith(data, 4, "moof") || startsWith(data, 4, "moov");
    }

    private static boolean startsWith(byte[] data, int start, String text) {
        if (start < 0 || start > data.length - text.length()) return false;
        for (int i = 0; i < text.length(); i++) if (data[start + i] != (byte) text.charAt(i)) return false;
        return true;
    }

    private static int crc16(int crc, int value) {
        crc ^= value << 8;
        for (int i = 0; i < 8; i++) crc = ((crc << 1) ^ ((crc & 0x8000) != 0 ? 0x8005 : 0)) & 0xffff;
        return crc;
    }

    private static void checkInput(byte[] data) throws IOException {
        if (data == null || data.length == 0 || data.length > MAX_INPUT_BYTES) throw invalid("HLS 分片长度异常");
    }

    private static void requireRange(int start, int length, int end) throws IOException {
        if (start < 0 || length < 0 || start > end || length > end - start) throw invalid("音频数据被截断或长度溢出");
    }

    private static int unsigned(byte[] data, int position, int end) throws IOException {
        requireRange(position, 1, end);
        return data[position] & 0xff;
    }

    private static int read16(byte[] data, int position, int end) throws IOException {
        requireRange(position, 2, end);
        return ((data[position] & 0xff) << 8) | (data[position + 1] & 0xff);
    }

    private static int read24(byte[] data, int position, int end) throws IOException {
        requireRange(position, 3, end);
        return ((data[position] & 0xff) << 16) | ((data[position + 1] & 0xff) << 8) | (data[position + 2] & 0xff);
    }

    private static long read32(byte[] data, int position, int end) throws IOException {
        requireRange(position, 4, end);
        return ((long) read16(data, position, end) << 16) | read16(data, position + 2, end);
    }

    private static long read64(byte[] data, int position, int end) throws IOException {
        requireRange(position, 8, end);
        return (read32(data, position, end) << 32) | read32(data, position + 4, end);
    }

    private static String normalizeCodec(String value) {
        if (value == null) return "";
        String result = value.trim().toLowerCase(Locale.US);
        return "aac-lc".equals(result) ? "aac" : result;
    }

    private static boolean isSupported(String value) {
        return "flac".equals(value) || "mp3".equals(value) || "aac".equals(value);
    }

    private static IOException invalid(String message) { return new IOException(message); }

    private static final class Box {
        final int start, payload, end;
        final String type;
        Box(int start, int payload, int end, String type) {
            this.start = start; this.payload = payload; this.end = end; this.type = type;
        }
    }

    private static final class Descriptor {
        final int tag, start, end;
        Descriptor(int tag, int start, int end) { this.tag = tag; this.start = start; this.end = end; }
    }

    private static final class FlacHeader {
        final long number;
        final boolean variable;
        final int samples, end;
        FlacHeader(long number, boolean variable, int samples, int end) {
            this.number = number; this.variable = variable; this.samples = samples; this.end = end;
        }
    }

    private static final class AudioFrame {
        final int size, samples, rate, channels;
        AudioFrame(int size, int samples, int rate, int channels) {
            this.size = size; this.samples = samples; this.rate = rate; this.channels = channels;
        }
    }

    private static final class Bits {
        final byte[] data;
        final int end;
        int position;
        Bits(byte[] data, int start, int end) { this.data = data; this.position = start * 8; this.end = end * 8; }
        int remaining() { return end - position; }
        int read(int count) throws IOException {
            if (count > remaining()) throw invalid("AAC 配置被截断");
            int result = 0;
            for (int i = 0; i < count; i++, position++) {
                result = (result << 1) | ((data[position >>> 3] >>> (7 - (position & 7))) & 1);
            }
            return result;
        }
    }
}
