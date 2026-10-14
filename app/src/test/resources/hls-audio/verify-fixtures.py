"""Independent decode verification; outputs/compiled classes live in a temp directory."""

import hashlib
import json
from pathlib import Path
import subprocess
import tempfile


FIXTURES = Path(__file__).resolve().parent
PROJECT = FIXTURES.parents[4]
SOURCE = PROJECT / "app/src/main/java/com/feiniu/carmusic/player/HlsAudioAssembler.java"
EXPECTED_PCM = {
    "flac": (44100, "dd0f318ea31da92d3c138a52176a5c5b5239d9dfde28701e7ef950700817c718"),
    "mp3": (46080, "1f7bb01c0c064f0d23fb005bde59bff64a2539379b52b191473297d57868262b"),
    "aac": (46080, "cd0cca5607da23bd8464dd56111b4878dacb628cfb4bc6c6433202b36bfab844"),
}
HARNESS = """
import java.io.File;
import java.nio.file.Files;
import com.feiniu.carmusic.player.HlsAudioAssembler;
public class VerifyAssembler {
 public static void main(String[] args) throws Exception {
  File root = new File(args[0]);
  for (String codec : new String[]{"flac", "mp3", "aac"}) {
   File result = new File(args[1], codec + "." + codec);
   try (HlsAudioAssembler a = new HlsAudioAssembler(result, codec)) {
    a.acceptInitialization(Files.readAllBytes(new File(root, codec + "-init.mp4").toPath()));
    for (int i = 0; i < 5; i++)
     a.appendSegment(Files.readAllBytes(new File(root, codec + "-" + i + ".m4s").toPath()));
    a.finish();
   }
  }
 }
}
"""


def decode(path):
    return subprocess.check_output(["ffmpeg", "-v", "error", "-i", str(path), "-f", "s16le", "-"])


with tempfile.TemporaryDirectory(prefix="feiniu-hls-verify-") as temporary:
    output = Path(temporary)
    harness = output / "VerifyAssembler.java"
    harness.write_text(HARNESS, encoding="utf-8")
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(output), str(SOURCE), str(harness)], check=True)
    subprocess.run(["java", "-cp", str(output), "VerifyAssembler", str(FIXTURES), str(output)], check=True)
    for codec, (samples, pcm_hash) in EXPECTED_PCM.items():
        audio = output / f"{codec}.{codec}"
        pcm = decode(audio)
        reference = decode(FIXTURES / f"packed.{codec}")
        assert pcm == reference, f"{codec} decoded samples differ from FFmpeg demux"
        assert any(pcm), f"{codec} decoded silence"
        assert len(pcm) // 4 == samples, f"{codec} decoded sample count changed"
        assert hashlib.sha256(pcm).hexdigest() == pcm_hash, f"{codec} PCM hash changed"
        probe = json.loads(subprocess.check_output([
            "ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(audio)
        ]))
        assert probe["streams"][0]["codec_name"] == codec
        assert probe["streams"][0]["sample_rate"] == "44100"
        assert probe["streams"][0]["channels"] == 2
        duration = float(probe["format"]["duration"])
        assert abs(duration - samples / 44100.0) < 0.001
        print(f"{codec}: {samples} nonzero PCM sample frames, {duration:.6f} s, reference PCM identical")
