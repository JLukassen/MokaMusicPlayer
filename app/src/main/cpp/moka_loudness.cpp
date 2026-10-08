#include <jni.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace {
constexpr int PCM_16 = 2;
constexpr int PCM_8 = 3;
constexpr int PCM_FLOAT = 4;
constexpr int PCM_24 = 21;
constexpr int PCM_32 = 22;
constexpr double PI_D = 3.1415926535897932384626433832795;

struct Coeff { double b0, b1, b2, a1, a2; };

Coeff highPass(double fs, double f0, double q) {
    const double w0 = 2.0 * PI_D * f0 / fs;
    const double cw = std::cos(w0);
    const double sw = std::sin(w0);
    const double alpha = sw / (2.0 * q);
    const double b0 = (1.0 + cw) / 2.0;
    const double b1 = -(1.0 + cw);
    const double b2 = (1.0 + cw) / 2.0;
    const double a0 = 1.0 + alpha;
    const double a1 = -2.0 * cw;
    const double a2 = 1.0 - alpha;
    return {b0/a0, b1/a0, b2/a0, a1/a0, a2/a0};
}

Coeff highShelf(double fs, double f0, double gainDb) {
    const double a = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * PI_D * f0 / fs;
    const double cw = std::cos(w0);
    const double sw = std::sin(w0);
    const double alpha = sw / 2.0 * std::sqrt(2.0);
    const double two = 2.0 * std::sqrt(a) * alpha;
    const double b0 = a * ((a + 1.0) + (a - 1.0) * cw + two);
    const double b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cw);
    const double b2 = a * ((a + 1.0) + (a - 1.0) * cw - two);
    const double a0 = (a + 1.0) - (a - 1.0) * cw + two;
    const double a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cw);
    const double a2 = (a + 1.0) - (a - 1.0) * cw - two;
    return {b0/a0, b1/a0, b2/a0, a1/a0, a2/a0};
}

class Biquad {
public:
    explicit Biquad(Coeff c) : c(c) {}
    inline double process(double x) {
        const double y = c.b0 * x + z1;
        z1 = c.b1 * x - c.a1 * y + z2;
        z2 = c.b2 * x - c.a2 * y;
        return y;
    }
private:
    Coeff c;
    double z1 = 0.0, z2 = 0.0;
};

int bytesPerSample(int encoding) {
    switch (encoding) {
        case PCM_8: return 1;
        case PCM_16: return 2;
        case PCM_24: return 3;
        case PCM_32:
        case PCM_FLOAT: return 4;
        default: return 0;
    }
}

inline float readSample(const uint8_t*& p, int encoding) {
    switch (encoding) {
        case PCM_8: return (static_cast<int>(*p++) - 128) / 128.0f;
        case PCM_16: {
            int16_t v; std::memcpy(&v, p, 2); p += 2;
            return static_cast<float>(v) / 32768.0f;
        }
        case PCM_24: {
            int32_t v = static_cast<int32_t>(p[0]) |
                (static_cast<int32_t>(p[1]) << 8) |
                (static_cast<int32_t>(p[2]) << 16);
            p += 3;
            if (v & 0x00800000) v |= static_cast<int32_t>(0xff000000);
            return static_cast<float>(v) / 8388608.0f;
        }
        case PCM_32: {
            int32_t v; std::memcpy(&v, p, 4); p += 4;
            return static_cast<float>(static_cast<double>(v) / 2147483648.0);
        }
        case PCM_FLOAT: {
            float v; std::memcpy(&v, p, 4); p += 4;
            return v;
        }
        default: return 0.0f;
    }
}

class Analyzer {
public:
    Analyzer(int sampleRate, int channels)
        : channels(channels), segmentFrames(std::max(1, sampleRate / 10)),
          history(static_cast<size_t>(channels)) {
        shelf.reserve(channels);
        hp.reserve(channels);
        for (int ch = 0; ch < channels; ++ch) {
            shelf.emplace_back(highShelf(sampleRate, 1681.974450955533, 3.999843853973347));
            hp.emplace_back(highPass(sampleRate, 38.13547087602444, 0.5003270373238773));
        }
    }

    bool process(const uint8_t* data, size_t byteCount, int encoding, int samples) {
        const int bps = bytesPerSample(encoding);
        if (!data || bps <= 0 || samples <= 0) return false;
        const int frames = samples / channels;
        const int usableSamples = frames * channels;
        if (frames <= 0 || static_cast<size_t>(usableSamples * bps) > byteCount) return false;
        const uint8_t* p = data;

        for (int frame = 0; frame < frames; ++frame) {
            double power = 0.0;
            for (int ch = 0; ch < channels; ++ch) {
                float x = readSample(p, encoding);
                if (!std::isfinite(x)) x = 0.0f;
                samplePeak = std::max(samplePeak, std::abs(x));
                pushPeak(ch, x);
                const double y = hp[ch].process(shelf[ch].process(static_cast<double>(x)));
                power += y * y;
            }
            segmentEnergy += power;
            if (++segmentFrameCount >= segmentFrames) flush();
        }
        return true;
    }

    bool finish(float& integrated, float& peakDb) {
        flush();
        if (segments.empty()) return false;

        std::vector<double> blocks;
        if (segments.size() < 4) {
            double sum = 0.0;
            for (double e : segments) sum += e;
            blocks.push_back(sum / segments.size());
        } else {
            blocks.reserve(segments.size() - 3);
            for (size_t i = 3; i < segments.size(); ++i) {
                blocks.push_back((segments[i] + segments[i-1] + segments[i-2] + segments[i-3]) / 4.0);
            }
        }

        std::vector<double> first;
        first.reserve(blocks.size());
        for (double e : blocks) if (lufs(e) >= -70.0) first.push_back(e);
        if (first.empty()) first = blocks;

        double sum = 0.0;
        for (double e : first) sum += e;
        const double ungated = lufs(sum / first.size());
        const double gate = std::max(-70.0, ungated - 10.0);

        double gatedSum = 0.0;
        size_t gatedCount = 0;
        for (double e : first) {
            if (lufs(e) >= gate) { gatedSum += e; ++gatedCount; }
        }
        const double finalEnergy = gatedCount
            ? gatedSum / gatedCount
            : sum / first.size();

        integrated = static_cast<float>(lufs(finalEnergy));
        const float peak = std::max(samplePeak, intersamplePeak);
        peakDb = peak <= 1e-9f ? -120.0f : static_cast<float>(20.0 * std::log10(peak));
        return true;
    }

private:
    void pushPeak(int ch, float value) {
        auto& h = history[ch];
        h[0] = h[1]; h[1] = h[2]; h[2] = h[3]; h[3] = value;
        if (historyCount >= 3) {
            const float ym1=h[0], y0=h[1], y1=h[2], y2=h[3];
            for (int step=1; step<=3; ++step) {
                const float t=step*0.25f, t2=t*t, t3=t2*t;
                const float y=0.5f*(2.0f*y0 + (-ym1+y1)*t +
                    (2.0f*ym1-5.0f*y0+4.0f*y1-y2)*t2 +
                    (-ym1+3.0f*y0-3.0f*y1+y2)*t3);
                intersamplePeak = std::max(intersamplePeak, std::abs(y));
            }
        }
        if (ch == channels - 1) ++historyCount;
    }

    void flush() {
        if (segmentFrameCount <= 0) return;
        segments.push_back(segmentEnergy / segmentFrameCount);
        segmentEnergy = 0.0;
        segmentFrameCount = 0;
    }

    static double lufs(double e) {
        return e <= 1e-20 ? -120.0 : -0.691 + 10.0 * std::log10(e);
    }

    int channels;
    int segmentFrames;
    int segmentFrameCount = 0;
    int64_t historyCount = 0;
    double segmentEnergy = 0.0;
    float samplePeak = 0.0f, intersamplePeak = 0.0f;
    std::vector<double> segments;
    std::vector<Biquad> shelf, hp;
    std::vector<std::array<float,4>> history;
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mokamusic_player_audio_NativeLoudnessBridge_nativeCreate(
        JNIEnv*, jobject, jint sampleRate, jint channels) {
    if (sampleRate <= 0 || channels < 1 || channels > 2) return 0;
    try { return reinterpret_cast<jlong>(new Analyzer(sampleRate, channels)); }
    catch (...) { return 0; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mokamusic_player_audio_NativeLoudnessBridge_nativeProcessPcm(
        JNIEnv* env, jobject, jlong handle, jobject pcm, jint encoding, jint sampleCount) {
    auto* analyzer = reinterpret_cast<Analyzer*>(handle);
    if (!analyzer || !pcm) return JNI_FALSE;
    void* address = env->GetDirectBufferAddress(pcm);
    const jlong capacity = env->GetDirectBufferCapacity(pcm);
    if (!address || capacity <= 0) return JNI_FALSE;
    return analyzer->process(static_cast<const uint8_t*>(address),
        static_cast<size_t>(capacity), encoding, sampleCount) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_mokamusic_player_audio_NativeLoudnessBridge_nativeFinish(
        JNIEnv* env, jobject, jlong handle) {
    auto* analyzer = reinterpret_cast<Analyzer*>(handle);
    if (!analyzer) return nullptr;
    float integrated = 0.0f, peak = 0.0f;
    if (!analyzer->finish(integrated, peak)) return nullptr;
    const jfloat values[2] = {integrated, peak};
    jfloatArray out = env->NewFloatArray(2);
    if (!out) return nullptr;
    env->SetFloatArrayRegion(out, 0, 2, values);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mokamusic_player_audio_NativeLoudnessBridge_nativeRelease(
        JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Analyzer*>(handle);
}
