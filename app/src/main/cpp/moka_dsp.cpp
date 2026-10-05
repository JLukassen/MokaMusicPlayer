#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <complex>
#include <cstdint>
#include <deque>
#include <limits>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {
constexpr const char* TAG = "MokaNativeDSP";
constexpr float PI_F = 3.14159265358979323846f;

inline float dbToGain(float db) {
    return std::pow(10.0f, db / 20.0f);
}

inline int nextPow2(int v) {
    int n = 1;
    while (n < v) n <<= 1;
    return n;
}

class FftPlan {
public:
    explicit FftPlan(int size) : n(size), bitReverse(size), roots(size / 2) {
        int bits = 0;
        for (int x = n; x > 1; x >>= 1) ++bits;
        for (int i = 0; i < n; ++i) {
            unsigned v = static_cast<unsigned>(i);
            unsigned r = 0;
            for (int b = 0; b < bits; ++b) {
                r = (r << 1u) | (v & 1u);
                v >>= 1u;
            }
            bitReverse[i] = static_cast<int>(r);
        }
        for (int k = 0; k < n / 2; ++k) {
            const float angle = -2.0f * PI_F * static_cast<float>(k) / static_cast<float>(n);
            roots[k] = {std::cos(angle), std::sin(angle)};
        }
    }

    void transform(std::vector<std::complex<float>>& a, bool inverse) const {
        for (int i = 0; i < n; ++i) {
            const int j = bitReverse[i];
            if (i < j) std::swap(a[i], a[j]);
        }
        for (int len = 2; len <= n; len <<= 1) {
            const int half = len >> 1;
            const int step = n / len;
            for (int base = 0; base < n; base += len) {
                int rootIndex = 0;
                for (int k = 0; k < half; ++k, rootIndex += step) {
                    const std::complex<float> w = inverse ? std::conj(roots[rootIndex]) : roots[rootIndex];
                    const auto u = a[base + k];
                    const auto v = a[base + k + half] * w;
                    a[base + k] = u + v;
                    a[base + k + half] = u - v;
                }
            }
        }
        if (inverse) {
            const float scale = 1.0f / static_cast<float>(n);
            for (auto& v : a) v *= scale;
        }
    }

    int size() const { return n; }

private:
    int n;
    std::vector<int> bitReverse;
    std::vector<std::complex<float>> roots;
};

std::shared_ptr<FftPlan> getPlan(int size) {
    static std::mutex mutex;
    static std::unordered_map<int, std::weak_ptr<FftPlan>> plans;
    std::lock_guard<std::mutex> lock(mutex);
    if (auto existing = plans[size].lock()) return existing;
    auto plan = std::make_shared<FftPlan>(size);
    plans[size] = plan;
    return plan;
}

void trimTrailingSilence(std::vector<float>& a) {
    while (a.size() > 1 && std::abs(a.back()) < 1e-10f) a.pop_back();
}

bool findSingleTap(const std::vector<float>& a, int& index, float& gain) {
    int found = -1;
    float value = 0.0f;
    for (int i = 0; i < static_cast<int>(a.size()); ++i) {
        if (std::abs(a[i]) > 1e-9f) {
            if (found >= 0) return false;
            found = i;
            value = a[i];
        }
    }
    if (found < 0) return false;
    index = found;
    gain = value;
    return true;
}

std::vector<float> convolveLinear(const std::vector<float>& a, const std::vector<float>& b) {
    if (a.empty()) return b;
    if (b.empty()) return a;

    int tap = 0;
    float gain = 0.0f;
    if (findSingleTap(b, tap, gain)) {
        std::vector<float> out(a.size() + static_cast<size_t>(tap), 0.0f);
        for (size_t i = 0; i < a.size(); ++i) out[i + tap] = a[i] * gain;
        trimTrailingSilence(out);
        return out;
    }
    if (findSingleTap(a, tap, gain)) {
        std::vector<float> out(b.size() + static_cast<size_t>(tap), 0.0f);
        for (size_t i = 0; i < b.size(); ++i) out[i + tap] = b[i] * gain;
        trimTrailingSilence(out);
        return out;
    }

    const int outLen = static_cast<int>(a.size() + b.size() - 1);
    const int n = nextPow2(outLen);
    auto plan = getPlan(n);
    std::vector<std::complex<float>> fa(n), fb(n);
    for (size_t i = 0; i < a.size(); ++i) fa[i] = {a[i], 0.0f};
    for (size_t i = 0; i < b.size(); ++i) fb[i] = {b[i], 0.0f};
    plan->transform(fa, false);
    plan->transform(fb, false);
    for (int i = 0; i < n; ++i) fa[i] *= fb[i];
    plan->transform(fa, true);
    std::vector<float> out(outLen);
    for (int i = 0; i < outLen; ++i) out[i] = fa[i].real();
    trimTrailingSilence(out);
    return out;
}

struct Sos {
    double b0, b1, b2, a1, a2;
};

class SosCascade {
public:
    SosCascade(std::vector<Sos> sections, int channels)
        : secs(std::move(sections)), channels(channels),
          v1(static_cast<size_t>(channels) * secs.size(), 0.0),
          v2(static_cast<size_t>(channels) * secs.size(), 0.0) {}

    void reset() {
        std::fill(v1.begin(), v1.end(), 0.0);
        std::fill(v2.begin(), v2.end(), 0.0);
    }

    void process(float* interleaved, int frames) {
        if (secs.empty()) return;
        const int nsec = static_cast<int>(secs.size());
        for (int frame = 0; frame < frames; ++frame) {
            for (int ch = 0; ch < channels; ++ch) {
                double x = interleaved[frame * channels + ch];
                const int base = ch * nsec;
                for (int i = 0; i < nsec; ++i) {
                    const auto& s = secs[i];
                    const int st = base + i;
                    const double w = x - s.a1 * v1[st] - s.a2 * v2[st];
                    x = s.b0 * w + s.b1 * v1[st] + s.b2 * v2[st];
                    v2[st] = v1[st];
                    v1[st] = w;
                }
                interleaved[frame * channels + ch] = static_cast<float>(x);
            }
        }
    }

private:
    std::vector<Sos> secs;
    int channels;
    std::vector<double> v1, v2;
};

class MonoPartitionedConvolver {
public:
    MonoPartitionedConvolver(const std::vector<float>& ir, int blockSize)
        : b(blockSize), n(blockSize * 2), plan(getPlan(n)),
          parts(std::max(1, static_cast<int>((ir.size() + b - 1) / b))),
          h(parts, std::vector<std::complex<float>>(n)),
          x(parts, std::vector<std::complex<float>>(n)),
          y(n), overlap(b, 0.0f), head(0) {
        for (int p = 0; p < parts; ++p) {
            const int start = p * b;
            const int len = std::max(0, std::min(b, static_cast<int>(ir.size()) - start));
            for (int i = 0; i < len; ++i) h[p][i] = {ir[start + i], 0.0f};
            plan->transform(h[p], false);
        }
    }

    void reset() {
        for (auto& part : x) std::fill(part.begin(), part.end(), std::complex<float>(0.0f, 0.0f));
        std::fill(y.begin(), y.end(), std::complex<float>(0.0f, 0.0f));
        std::fill(overlap.begin(), overlap.end(), 0.0f);
        head = 0;
    }

    void process(const float* input, float* output) {
        auto& current = x[head];
        std::fill(current.begin(), current.end(), std::complex<float>(0.0f, 0.0f));
        for (int i = 0; i < b; ++i) current[i] = {input[i], 0.0f};
        plan->transform(current, false);

        std::fill(y.begin(), y.end(), std::complex<float>(0.0f, 0.0f));
        for (int p = 0; p < parts; ++p) {
            int hist = head - p;
            if (hist < 0) hist += parts;
            const auto& xp = x[hist];
            const auto& hp = h[p];
            for (int k = 0; k < n; ++k) y[k] += xp[k] * hp[k];
        }
        plan->transform(y, true);
        for (int i = 0; i < b; ++i) {
            output[i] = y[i].real() + overlap[i];
            overlap[i] = y[i + b].real();
        }
        if (++head == parts) head = 0;
    }

private:
    int b, n;
    std::shared_ptr<FftPlan> plan;
    int parts;
    std::vector<std::vector<std::complex<float>>> h, x;
    std::vector<std::complex<float>> y;
    std::vector<float> overlap;
    int head;
};

class StereoConvolver {
public:
    StereoConvolver(std::vector<std::vector<float>> filters, int channels, int blockSize, float gainDb)
        : channels(channels), blockSize(blockSize), gain(dbToGain(gainDb)),
          left(blockSize), right(blockSize), tmp0(blockSize), tmp1(blockSize), tmp2(blockSize), tmp3(blockSize) {
        if (filters.empty()) return;
        if (channels == 1) {
            conv.emplace_back(std::make_unique<MonoPartitionedConvolver>(filters[0], blockSize));
        } else if (filters.size() >= 4) {
            for (int i = 0; i < 4; ++i) conv.emplace_back(std::make_unique<MonoPartitionedConvolver>(filters[i], blockSize));
        } else if (filters.size() >= 2) {
            conv.emplace_back(std::make_unique<MonoPartitionedConvolver>(filters[0], blockSize));
            conv.emplace_back(std::make_unique<MonoPartitionedConvolver>(filters[1], blockSize));
        } else {
            conv.emplace_back(std::make_unique<MonoPartitionedConvolver>(filters[0], blockSize));
            conv.emplace_back(std::make_unique<MonoPartitionedConvolver>(filters[0], blockSize));
        }
    }

    bool empty() const { return conv.empty(); }

    void reset() { for (auto& c : conv) c->reset(); }

    void process(float* data) {
        if (conv.empty()) return;
        if (channels == 1) {
            conv[0]->process(data, tmp0.data());
            for (int i = 0; i < blockSize; ++i) data[i] = tmp0[i] * gain;
            return;
        }
        for (int i = 0; i < blockSize; ++i) {
            left[i] = data[i * 2];
            right[i] = data[i * 2 + 1];
        }
        if (conv.size() == 4) {
            conv[0]->process(left.data(), tmp0.data());
            conv[1]->process(left.data(), tmp1.data());
            conv[2]->process(right.data(), tmp2.data());
            conv[3]->process(right.data(), tmp3.data());
            for (int i = 0; i < blockSize; ++i) {
                data[i * 2] = (tmp0[i] + tmp2[i]) * gain;
                data[i * 2 + 1] = (tmp1[i] + tmp3[i]) * gain;
            }
        } else {
            conv[0]->process(left.data(), tmp0.data());
            conv[1]->process(right.data(), tmp1.data());
            for (int i = 0; i < blockSize; ++i) {
                data[i * 2] = tmp0[i] * gain;
                data[i * 2 + 1] = tmp1[i] * gain;
            }
        }
    }

private:
    int channels, blockSize;
    float gain;
    std::vector<std::unique_ptr<MonoPartitionedConvolver>> conv;
    std::vector<float> left, right, tmp0, tmp1, tmp2, tmp3;
};

class SparseDelayFilter {
public:
    SparseDelayFilter(const std::vector<std::vector<float>>& filters, int channels, int blockSize, float gainDb)
        : channels(channels), blockSize(blockSize), outputGain(dbToGain(gainDb)) {
        const int count = (channels == 1) ? 1 : std::max(2, static_cast<int>(filters.size()));
        delays.resize(count);
        gains.resize(count);
        rings.resize(count);
        positions.assign(count, 0);
        for (int i = 0; i < count; ++i) {
            const auto& f = filters[std::min(i, static_cast<int>(filters.size()) - 1)];
            int d = 0; float g = 0.0f;
            if (!findSingleTap(f, d, g)) { valid = false; return; }
            delays[i] = d;
            gains[i] = g;
            rings[i].assign(static_cast<size_t>(std::max(1, d)), 0.0f);
        }
        valid = true;
    }

    bool isValid() const { return valid; }
    void reset() {
        for (auto& r : rings) std::fill(r.begin(), r.end(), 0.0f);
        std::fill(positions.begin(), positions.end(), 0);
    }

    void process(float* data) {
        if (!valid) return;
        if (channels == 1) {
            for (int f = 0; f < blockSize; ++f) data[f] = delaySample(0, data[f]) * outputGain;
            return;
        }
        for (int f = 0; f < blockSize; ++f) {
            const float l = data[f * 2];
            const float r = data[f * 2 + 1];
            if (rings.size() >= 4) {
                const float ll = delaySample(0, l);
                const float lr = delaySample(1, l);
                const float rl = delaySample(2, r);
                const float rr = delaySample(3, r);
                data[f * 2] = (ll + rl) * outputGain;
                data[f * 2 + 1] = (lr + rr) * outputGain;
            } else {
                data[f * 2] = delaySample(0, l) * outputGain;
                data[f * 2 + 1] = delaySample(1, r) * outputGain;
            }
        }
    }

private:
    float delaySample(int index, float input) {
        auto& ring = rings[index];
        int& pos = positions[index];
        const int delay = delays[index];
        if (delay == 0) return input * gains[index];
        const float out = ring[pos] * gains[index];
        ring[pos] = input;
        if (++pos >= static_cast<int>(ring.size())) pos = 0;
        return out;
    }

    int channels, blockSize;
    float outputGain;
    bool valid = false;
    std::vector<int> delays;
    std::vector<float> gains;
    std::vector<std::vector<float>> rings;
    std::vector<int> positions;
};

class Normalizer {
public:
    Normalizer(int sampleRate, int channels, bool enabled, float staticGainDb,
               bool adaptiveFallback, float preampDb)
        : channels(channels), enabled(enabled) {
        const bool hasStatic = std::isfinite(staticGainDb);
        staticMode = enabled && hasStatic;
        adaptive = enabled && !hasStatic && adaptiveFallback;
        staticGain = hasStatic ? dbToGain(staticGainDb + preampDb) : 1.0f;
        preamp = dbToGain(preampDb);
        targetRms = dbToGain(-18.0f);
        maxBoost = dbToGain(12.0f);
        maxCut = dbToGain(-18.0f);
        rmsCoeff = std::exp(-1.0f / (3.0f * static_cast<float>(sampleRate)));
        gainAttackCoeff = std::exp(-1.0f / (0.35f * static_cast<float>(sampleRate)));
        gainReleaseCoeff = std::exp(-1.0f / (5.0f * static_cast<float>(sampleRate)));
        reset();
    }

    void reset() {
        meanSquare = targetRms * targetRms;
        gain = 1.0f;
    }

    void process(float* a, int frames) {
        if (!enabled) return;
        if (staticMode) {
            const int samples = frames * channels;
            for (int i = 0; i < samples; ++i) a[i] *= staticGain;
            return;
        }
        if (!adaptive) {
            if (preamp != 1.0f) {
                const int samples = frames * channels;
                for (int i = 0; i < samples; ++i) a[i] *= preamp;
            }
            return;
        }
        for (int f = 0; f < frames; ++f) {
            float power = 0.0f;
            for (int ch = 0; ch < channels; ++ch) {
                const float v = a[f * channels + ch];
                power += v * v;
            }
            power /= static_cast<float>(channels);
            meanSquare = rmsCoeff * meanSquare + (1.0f - rmsCoeff) * power;
            const float rms = std::sqrt(std::max(meanSquare, 1e-12f));
            const float wanted = std::clamp((targetRms / rms) * preamp, maxCut, maxBoost);
            const float coeff = wanted < gain ? gainAttackCoeff : gainReleaseCoeff;
            gain = coeff * gain + (1.0f - coeff) * wanted;
            for (int ch = 0; ch < channels; ++ch) a[f * channels + ch] *= gain;
        }
    }

private:
    int channels;
    bool enabled = false, staticMode = false, adaptive = false;
    float staticGain = 1.0f, preamp = 1.0f;
    float targetRms = 0.0f, maxBoost = 1.0f, maxCut = 1.0f;
    float rmsCoeff = 0.0f, gainAttackCoeff = 0.0f, gainReleaseCoeff = 0.0f;
    float meanSquare = 0.0f, gain = 1.0f;
};

class LookaheadLimiter {
public:
    LookaheadLimiter(int sampleRate, int channels, int blockSize, bool enabled,
                     float thresholdDb, float releaseMs, float postGainDb)
        : channels(channels), blockSize(blockSize), enabled(enabled),
          threshold(dbToGain(thresholdDb)), post(dbToGain(postGainDb)),
          releaseCoeff(std::exp(-1.0f / (std::max(1.0f, releaseMs) * 0.001f * sampleRate))),
          lookahead(std::max(1, sampleRate / 200)), peaks(blockSize), windowMax(blockSize),
          maxDeque(blockSize) {}

    void reset() { gain = 1.0f; }

    void process(float* a) {
        const int samples = blockSize * channels;
        if (!enabled) {
            if (post != 1.0f) for (int i = 0; i < samples; ++i) a[i] *= post;
            sanitize(a, samples);
            return;
        }

        for (int f = 0; f < blockSize; ++f) {
            float peak = 0.0f;
            for (int ch = 0; ch < channels; ++ch) {
                float v = a[f * channels + ch];
                if (!std::isfinite(v)) v = 0.0f;
                v = std::clamp(v, -32.0f, 32.0f);
                a[f * channels + ch] = v;
                peak = std::max(peak, std::abs(v));
            }
            peaks[f] = peak;
        }

        int qHead = 0;
        int qTail = 0;
        int right = -1;
        for (int f = 0; f < blockSize; ++f) {
            const int wantedRight = std::min(blockSize - 1, f + lookahead);
            while (right < wantedRight) {
                ++right;
                while (qTail > qHead && peaks[maxDeque[qTail - 1]] <= peaks[right]) --qTail;
                maxDeque[qTail++] = right;
            }
            while (qHead < qTail && maxDeque[qHead] < f) ++qHead;
            windowMax[f] = qHead < qTail ? peaks[maxDeque[qHead]] : peaks[f];
            // Compact rarely so long blocks cannot exhaust the fixed queue after many pops.
            if (qHead > blockSize / 2) {
                const int live = qTail - qHead;
                for (int i = 0; i < live; ++i) maxDeque[i] = maxDeque[qHead + i];
                qHead = 0;
                qTail = live;
            }
        }

        for (int f = 0; f < blockSize; ++f) {
            const float p = windowMax[f];
            const float wanted = (p > threshold && p > 0.0f) ? threshold / p : 1.0f;
            gain = wanted < gain ? wanted : (1.0f - releaseCoeff) * wanted + releaseCoeff * gain;
            const float g = gain * post;
            for (int ch = 0; ch < channels; ++ch) {
                a[f * channels + ch] = std::clamp(a[f * channels + ch] * g, -1.0f, 1.0f);
            }
        }
    }

private:
    static void sanitize(float* a, int samples) {
        for (int i = 0; i < samples; ++i) {
            if (!std::isfinite(a[i])) a[i] = 0.0f;
            a[i] = std::clamp(a[i], -1.0f, 1.0f);
        }
    }

    int channels, blockSize;
    bool enabled;
    float threshold, post, releaseCoeff;
    int lookahead;
    float gain = 1.0f;
    std::vector<float> peaks, windowMax;
    std::vector<int> maxDeque;
};

class NativeChain {
public:
    NativeChain(int sampleRate, int channels, int blockSize,
                std::vector<Sos> ddc,
                std::vector<float> eqImpulse,
                std::vector<std::vector<float>> irs,
                float convolverGainDb,
                bool normalizationEnabled, float normalizationStaticGainDb,
                bool normalizationAdaptiveFallback, float normalizationPreampDb,
                bool limiterEnabled, float limiterThresholdDb,
                float limiterReleaseMs, float postGainDb)
        : sampleRate(sampleRate), channels(channels), blockSize(blockSize),
          normalizer(sampleRate, channels, normalizationEnabled, normalizationStaticGainDb,
                     normalizationAdaptiveFallback, normalizationPreampDb),
          ddc(ddc.empty() ? nullptr : std::make_unique<SosCascade>(std::move(ddc), channels)),
          limiter(sampleRate, channels, blockSize, limiterEnabled, limiterThresholdDb,
                  limiterReleaseMs, postGainDb) {

        const bool hadEq = !eqImpulse.empty();
        std::vector<std::vector<float>> filters;
        if (hadEq && !irs.empty()) {
            filters.reserve(irs.size());
            for (const auto& ir : irs) filters.emplace_back(convolveLinear(eqImpulse, ir));
        } else if (hadEq) {
            filters.emplace_back(std::move(eqImpulse));
        } else if (!irs.empty()) {
            filters = std::move(irs);
        }

        // A surprising number of headphone IRS files are just a delayed scalar impulse. Avoid an
        // FFT entirely for those. Heroin.irs from the user's reference set is exactly this shape.
        if (!filters.empty() && !hadEq) {
            auto sparseCandidate = std::make_unique<SparseDelayFilter>(filters, channels, blockSize, convolverGainDb);
            if (sparseCandidate->isValid()) {
                sparse = std::move(sparseCandidate);
            } else {
                convolver = std::make_unique<StereoConvolver>(std::move(filters), channels, blockSize, convolverGainDb);
            }
        } else if (!filters.empty()) {
            convolver = std::make_unique<StereoConvolver>(std::move(filters), channels, blockSize, convolverGainDb);
        }
    }

    void reset() {
        normalizer.reset();
        if (ddc) ddc->reset();
        if (convolver) convolver->reset();
        if (sparse) sparse->reset();
        limiter.reset();
    }

    void process(float* data, int samples) {
        const int expected = blockSize * channels;
        if (samples != expected) return;
        normalizer.process(data, blockSize);
        if (ddc) ddc->process(data, blockSize);
        if (convolver) convolver->process(data);
        if (sparse) sparse->process(data);
        limiter.process(data);
    }

private:
    int sampleRate, channels, blockSize;
    Normalizer normalizer;
    std::unique_ptr<SosCascade> ddc;
    std::unique_ptr<StereoConvolver> convolver;
    std::unique_ptr<SparseDelayFilter> sparse;
    LookaheadLimiter limiter;
};

std::vector<float> getFloatArray(JNIEnv* env, jfloatArray array) {
    if (!array) return {};
    const jsize n = env->GetArrayLength(array);
    std::vector<float> out(static_cast<size_t>(n));
    if (n > 0) env->GetFloatArrayRegion(array, 0, n, out.data());
    return out;
}

std::vector<Sos> getSos(JNIEnv* env, jdoubleArray coeffs) {
    if (!coeffs) return {};
    const jsize n = env->GetArrayLength(coeffs);
    if (n <= 0 || n % 5 != 0) return {};
    std::vector<double> flat(static_cast<size_t>(n));
    env->GetDoubleArrayRegion(coeffs, 0, n, flat.data());
    std::vector<Sos> out;
    out.reserve(static_cast<size_t>(n / 5));
    for (int i = 0; i < n; i += 5) out.push_back({flat[i], flat[i+1], flat[i+2], flat[i+3], flat[i+4]});
    return out;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_mokamusic_player_audio_dsp_NativeDspBridge_nativeCreate(
        JNIEnv* env, jobject,
        jint sampleRate, jint channels, jint blockSize,
        jdoubleArray ddcCoefficients,
        jfloatArray eqImpulse,
        jfloatArray ir0, jfloatArray ir1, jfloatArray ir2, jfloatArray ir3,
        jint irChannelCount,
        jfloat convolverGainDb,
        jboolean normalizationEnabled,
        jfloat normalizationStaticGainDb,
        jboolean normalizationAdaptiveFallback,
        jfloat normalizationPreampDb,
        jboolean limiterEnabled,
        jfloat limiterThresholdDb,
        jfloat limiterReleaseMs,
        jfloat postGainDb) {
    try {
        if (sampleRate <= 0 || channels < 1 || channels > 2 || blockSize <= 0 || (blockSize & (blockSize - 1)) != 0) {
            return 0;
        }
        auto ddc = getSos(env, ddcCoefficients);
        auto eq = getFloatArray(env, eqImpulse);
        std::vector<std::vector<float>> irs;
        if (irChannelCount > 0 && ir0) irs.push_back(getFloatArray(env, ir0));
        if (irChannelCount > 1 && ir1) irs.push_back(getFloatArray(env, ir1));
        if (irChannelCount > 2 && ir2) irs.push_back(getFloatArray(env, ir2));
        if (irChannelCount > 3 && ir3) irs.push_back(getFloatArray(env, ir3));

        auto* chain = new NativeChain(
            sampleRate, channels, blockSize,
            std::move(ddc), std::move(eq), std::move(irs), convolverGainDb,
            normalizationEnabled == JNI_TRUE, normalizationStaticGainDb,
            normalizationAdaptiveFallback == JNI_TRUE, normalizationPreampDb,
            limiterEnabled == JNI_TRUE, limiterThresholdDb, limiterReleaseMs, postGainDb
        );
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "Native DSP created: %d Hz, %d ch, block=%d, ddc=%d, eq=%s, irChannels=%d",
            sampleRate, channels, blockSize, ddcCoefficients ? env->GetArrayLength(ddcCoefficients) / 5 : 0,
            eqImpulse ? "yes" : "no", irChannelCount);
        return reinterpret_cast<jlong>(chain);
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeCreate failed: %s", e.what());
        return 0;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeCreate failed: unknown exception");
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mokamusic_player_audio_dsp_NativeDspBridge_nativeProcess(
        JNIEnv* env, jobject, jlong handle, jfloatArray samples) {
    auto* chain = reinterpret_cast<NativeChain*>(handle);
    if (!chain || !samples) return;
    const jsize n = env->GetArrayLength(samples);
    jboolean isCopy = JNI_FALSE;
    jfloat* ptr = env->GetFloatArrayElements(samples, &isCopy);
    if (!ptr) return;
    chain->process(ptr, n);
    env->ReleaseFloatArrayElements(samples, ptr, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mokamusic_player_audio_dsp_NativeDspBridge_nativeReset(
        JNIEnv*, jobject, jlong handle) {
    auto* chain = reinterpret_cast<NativeChain*>(handle);
    if (chain) chain->reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_mokamusic_player_audio_dsp_NativeDspBridge_nativeRelease(
        JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<NativeChain*>(handle);
}
