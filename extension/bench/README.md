# Acoustic sync test bench

Runs the radio's acoustic sync code (`AcousticFeatures`, `AcousticCorrelator`, `AcousticDecider`) on
recorded audio with a known offset, on a PC. Not part of the app: `build.sh` only compiles `src/`.

Used on 2026-11-14 to choose the side-signal measurement and the thresholds in `AcousticDecider`:
two audio tracks of one race replay (different commentary over the same circuit sound), one of them
compressed and MP3-coded like a radio stream; and pairs of unrelated stretches as the "nothing in
common" case, where every decision is a false one.

```bash
cd extension/bench
javac -d out android/os/SystemClock.java com/f1tvpatch/radio/*.java \
    ../src/com/f1tvpatch/radio/AcousticFeatures.java ../src/com/f1tvpatch/radio/AcousticCorrelator.java \
    ../src/com/f1tvpatch/radio/AcousticDecider.java
# f1.wav and radio.wav: 16-bit stereo WAV; 3000 = the radio is heard 3 s after the F1 audio
java -Xmx4g -cp out com.f1tvpatch.radio.Bench2 f1.wav radio.wav 3000            # closed loop, as the app
java -Xmx4g -cp out com.f1tvpatch.radio.Bench2 f1.wav radio.wav 3000 radioMono  # station with mono sound
java -Xmx3g -cp out com.f1tvpatch.radio.Bench f1.wav radio.wav 1 1 3000         # single estimates (1 = side, 0 = mono mix)
```
