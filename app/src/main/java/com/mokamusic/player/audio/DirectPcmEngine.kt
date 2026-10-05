package com.mokamusic.player.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.MediaItem
import com.mokamusic.player.audio.dsp.DspChain
import com.mokamusic.player.audio.dsp.DspFileLoader
import com.mokamusic.player.audio.dsp.DspSettingsStore
import com.mokamusic.player.audio.dsp.DspStreamAdapter
import com.mokamusic.player.audio.dsp.NativeDspChain
import com.mokamusic.player.audio.dsp.PcmFloatCodec
import com.mokamusic.player.CrashLogStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Dedicated lossless PCM engine used by Moka's hybrid Media3 player.
 *
 * WAV:
 *  - parses RIFF/WAVE directly and writes the original integer/float PCM bytes to AudioTrack.
 *
 * FLAC:
 *  - uses the platform FLAC decoder but explicitly requests the source integer PCM encoding.
 *  - if the decoder cannot return the requested precision, this engine reports a fallback rather
 *    than silently reducing precision. The hybrid player then resumes with Media3's float path.
 *
 * This class intentionally supports local lossless audio only. Media3 remains the fallback for
 * Bluetooth-friendly playback, lossy formats and devices/codecs that cannot expose the requested
 * integer path.
 */
class DirectPcmEngine(
    context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun onStateChanged(state: State)
        fun onReady(durationMs: Long)
        fun onEnded()
        fun onFallbackRequired(positionMs: Long, reason: String)
    }

    enum class State { IDLE, PREPARING, READY, PLAYING, PAUSED, ENDED, ERROR }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "MokaMusicPlayer:DirectPcm"
    ).apply { setReferenceCounted(false) }

    @Volatile var state: State = State.IDLE
        private set
    @Volatile var durationMs: Long = 0L
        private set
    @Volatile var currentPositionMs: Long = 0L
        private set
    @Volatile var isPlaying: Boolean = false
        private set

    @Volatile private var playRequested = false
    @Volatile private var stopRequested = false
    @Volatile private var pauseRequested = false
    private val seekRequestMs = AtomicLong(NO_SEEK)
    private val generation = AtomicLong(0L)

    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var activeUsbDevice: AudioDeviceInfo? = null
    @Volatile private var playbackThread: Thread? = null
    @Volatile private var activeDspWriter: DspAudioWriter? = null
    @Volatile private var activeDspAdapter: DspStreamAdapter? = null
    @Volatile private var lastUnderrunCount = 0
    @Volatile private var lastUnderrunLogMs = 0L

    private var focusRequest: AudioFocusRequest? = null

    fun canAttempt(item: MediaItem): Boolean {
        val uri = item.localConfiguration?.uri ?: return false
        if (uri.scheme != "content" && uri.scheme != "file") return false
        val name = uri.lastPathSegment.orEmpty().lowercase()
        val formatLabel = item.mediaMetadata.extras?.getString(AudioPathMonitor.EXTRA_FORMAT_LABEL).orEmpty()
        val localLossless = formatLabel.equals("FLAC", true) || formatLabel.equals("WAV", true) ||
            name.endsWith(".flac") || name.endsWith(".wav") || name.endsWith(".wave")
        val dspRequested = runCatching { DspSettingsStore(appContext).load().anyProcessingEnabled }.getOrDefault(false)
        // With DSP enabled, route every local audio format through the decoder-backed PCM bridge
        // so MP3/AAC/Opus/etc. receive the same DDC/EQ/convolver chain as FLAC/WAV.
        return localLossless || dspRequested
    }

    fun prepare(item: MediaItem, startPositionMs: Long, playWhenReady: Boolean) {
        stopInternal(clearMonitor = false)
        val token = generation.incrementAndGet()
        stopRequested = false
        pauseRequested = !playWhenReady
        playRequested = playWhenReady
        seekRequestMs.set(NO_SEEK)
        currentPositionMs = startPositionMs.coerceAtLeast(0L)
        setState(State.PREPARING)
        AudioPathMonitor.beginDirectPath()

        playbackThread = Thread({
            // Java Thread.priority alone does not put the worker in Android's audio-priority
            // scheduling class. This substantially reduces AudioTrack underruns under DSP load.
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            try {
                val uri = item.localConfiguration?.uri
                    ?: throw DirectUnsupported("Missing local URI")
                val dspRequested = DspSettingsStore(appContext).load().anyProcessingEnabled
                val formatLabel = item.mediaMetadata.extras
                    ?.getString(AudioPathMonitor.EXTRA_FORMAT_LABEL)
                    .orEmpty()
                val lowerName = uri.lastPathSegment.orEmpty().lowercase()

                val isWav = formatLabel.equals("WAV", true) || lowerName.endsWith(".wav") || lowerName.endsWith(".wave")
                val isFlac = formatLabel.equals("FLAC", true) || lowerName.endsWith(".flac")
                // getAudioDevicesForAttributes() is not reliable on every USB-C dongle/headset.
                // Lossless local files are safe to attempt directly regardless of that prediction;
                // the created AudioTrack and actual mixer state remain the source of truth.
                if (!isWav && !isFlac && !dspRequested) {
                    throw DirectUnsupported("DSP is off; use Media3 fallback for this format")
                }
                when {
                    isWav -> playWav(uri, item, startPositionMs, token)
                    isFlac || dspRequested -> playDecodedAudio(
                        uri = uri,
                        item = item,
                        startPositionMs = startPositionMs,
                        token = token,
                        requireExactSource = isFlac && !dspRequested
                    )
                    else -> throw DirectUnsupported("DSP is off; use Media3 fallback for this format")
                }
            } catch (t: Throwable) {
                if (!stopRequested && generation.get() == token) {
                    // DirectUnsupported is an expected routing/codec fallback, not a crash.
                    // Do not publish an intermediate ERROR/IDLE state while ExoPlayer is still
                    // buffering because Media3 validates the whole State atomically.
                    if (t !is DirectUnsupported) {
                        CrashLogStore.nonFatal(appContext, "Direct PCM/DSP playback", t)
                    }
                    callback.onFallbackRequired(currentPositionMs, t.message ?: t.javaClass.simpleName)
                }
            } finally {
                if (generation.get() == token) {
                    // If decode/DSP failed before the normal drain path, the writer may still be
                    // blocked in AudioTrack.write(). Stop it before releasing the shared track.
                    // Releasing AudioTrack underneath the writer was another possible race during
                    // direct -> Media3 fallback.
                    activeDspWriter?.let { writer ->
                        writer.stop()
                        if (activeDspWriter === writer) activeDspWriter = null
                    }
                    releaseAudioTrack()
                    abandonAudioFocus()
                    releaseWakeLock()
                }
            }
        }, "MokaDirectPcm").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    fun play() {
        playRequested = true
        pauseRequested = false
        if (state == State.READY || state == State.PAUSED) {
            val writer = activeDspWriter
            if (writer != null && !writer.started) {
                // DSP audio is still prebuffering. Let the writer start AudioTrack only after
                // enough processed PCM is queued instead of starting an empty track.
                setState(State.PREPARING)
                return
            }
            if (requestAudioFocus()) {
                audioTrack?.play()
                acquireWakeLock()
                isPlaying = true
                setState(State.PLAYING)
            }
        }
    }

    fun pause() {
        playRequested = false
        pauseRequested = true
        runCatching { audioTrack?.pause() }
        isPlaying = false
        abandonAudioFocus()
        releaseWakeLock()
        if (state != State.IDLE && state != State.ENDED && state != State.ERROR) {
            setState(State.PAUSED)
        }
    }

    fun seekTo(positionMs: Long) {
        seekRequestMs.set(positionMs.coerceAtLeast(0L))
    }

    fun stop() {
        generation.incrementAndGet()
        stopInternal(clearMonitor = true)
    }

    fun release() = stop()

    private fun playWav(uri: Uri, item: MediaItem, startPositionMs: Long, token: Long) {
        val pfd = appContext.contentResolver.openFileDescriptor(uri, "r")
            ?: throw DirectUnsupported("Unable to open WAV")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                val channel = input.channel
                val baseOffset = 0L
                val info = parseWav(channel, baseOffset)
                val encoding = info.encoding
                val sourceBits = encoding.pcmBitDepthOrNull() ?: info.bitsPerSample

                AudioPathMonitor.setSource(info.sampleRate, sourceBits, info.channels, encoding)
                durationMs = if (info.byteRate > 0) info.dataSize * 1000L / info.byteRate else 0L

                val dsp = createDspAdapter(info.sampleRate, info.channels, item)
                activeDspAdapter?.takeIf { it !== dsp }?.close()
                activeDspAdapter = dsp
                val dspActive = dsp != null
                val outputEncoding = if (dspActive) AudioFormat.ENCODING_PCM_FLOAT else encoding
                val track = buildAudioTrack(info.sampleRate, info.channels, outputEncoding)
                audioTrack = track
                val dspWriter = if (dspActive) {
                    DspAudioWriter(track, info.sampleRate, info.channels, token).also {
                        activeDspWriter = it
                    }
                } else null
                currentPositionMs = startPositionMs.coerceIn(0L, durationMs.coerceAtLeast(startPositionMs))
                val frameSize = max(1, info.blockAlign)
                val startByte = ((currentPositionMs * info.byteRate) / 1000L / frameSize) * frameSize
                channel.position(info.dataOffset + startByte.coerceAtMost(info.dataSize))

                publishDirectOutput(track, info.sampleRate, info.channels, outputEncoding, if (dspActive) "Moka DSP · 32-bit float" else "Moka Direct WAV")
                callback.onReady(durationMs)
                // DSP used to start AudioTrack empty, then read/process almost a full second of
                // audio at once. The track could drain before the next batch was ready. Feed much
                // smaller chunks and pre-roll one chunk before starting playback.
                if (!dspActive) startTrackIfRequested(track)

                val ioFrames = if (dspActive) DSP_IO_FRAMES else max(4096, info.sampleRate / 2)
                val ioBytes = max(frameSize, ioFrames * frameSize)
                val buffer = ByteBuffer.allocateDirect(ioBytes).order(ByteOrder.LITTLE_ENDIAN)
                var bytesConsumed = startByte

                while (!stopRequested && generation.get() == token) {
                    val requestedSeek = seekRequestMs.getAndSet(NO_SEEK)
                    if (requestedSeek != NO_SEEK) {
                        val target = requestedSeek.coerceIn(0L, durationMs.coerceAtLeast(requestedSeek))
                        val byteTarget = ((target * info.byteRate) / 1000L / frameSize) * frameSize
                        runCatching { track.pause(); track.flush() }
                        channel.position(info.dataOffset + byteTarget.coerceAtMost(info.dataSize))
                        bytesConsumed = byteTarget
                        currentPositionMs = target
                        dsp?.reset()
                        dspWriter?.resetForSeek()
                        if (!dspActive) startTrackIfRequested(track)
                    }

                    if (pauseRequested || !playRequested) {
                        Thread.sleep(20)
                        continue
                    }

                    val remaining = info.dataSize - bytesConsumed
                    if (remaining <= 0L) break
                    buffer.clear()
                    val requestedBytes = minOf(buffer.capacity().toLong(), remaining).toInt()
                    val alignedBytes = (requestedBytes / frameSize) * frameSize
                    if (alignedBytes <= 0) break
                    buffer.limit(alignedBytes)
                    val read = channel.read(buffer)
                    if (read <= 0) break
                    buffer.flip()
                    if (dspActive) {
                        val samples = read / bytesPerSample(encoding)
                        val sourceFloat = PcmFloatCodec.decode(buffer.slice().order(ByteOrder.LITTLE_ENDIAN), encoding, samples)
                        val processed = dsp!!.process(sourceFloat)
                        if (processed.isNotEmpty()) {
                            dspWriter!!.enqueue(processed)
                        }
                    } else {
                        var writtenTotal = 0
                        while (writtenTotal < read && !stopRequested && generation.get() == token) {
                            val written = track.write(buffer, read - writtenTotal, AudioTrack.WRITE_BLOCKING)
                            if (written < 0) throw DirectUnsupported("AudioTrack write failed: $written")
                            writtenTotal += written
                        }
                    }
                    bytesConsumed += read
                    currentPositionMs = if (info.byteRate > 0) bytesConsumed * 1000L / info.byteRate else 0L
                }

                if (!stopRequested && generation.get() == token) {
                    if (dspActive) {
                        val tail = dsp!!.flush()
                        if (tail.isNotEmpty()) dspWriter!!.enqueue(tail)
                        dspWriter!!.finishAndDrain()
                        if (activeDspWriter === dspWriter) activeDspWriter = null
                    }
                    isPlaying = false
                    setState(State.ENDED)
                    callback.onEnded()
                }
        }
    }

    private fun playDecodedAudio(
        uri: Uri,
        item: MediaItem,
        startPositionMs: Long,
        token: Long,
        requireExactSource: Boolean
    ) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(appContext, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw DirectUnsupported("No decodable audio track")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw DirectUnsupported("Audio MIME missing")

            val extras = item.mediaMetadata.extras ?: android.os.Bundle()
            val sourceBits = extras.getInt(AudioPathMonitor.EXTRA_BIT_DEPTH, 0).takeIf { it > 0 }
            val requestedEncoding = sourceBits?.let(::bitDepthToEncoding)
            val sourceRate = extras.getInt(AudioPathMonitor.EXTRA_SAMPLE_RATE_HZ, 0).takeIf { it > 0 }
                ?: inputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)
                ?: throw DirectUnsupported("Unknown source sample rate")
            val sourceChannels = extras.getInt(AudioPathMonitor.EXTRA_CHANNEL_COUNT, 0).takeIf { it > 0 }
                ?: inputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)
                ?: throw DirectUnsupported("Unknown source channel count")
            if (sourceChannels !in 1..2) throw DirectUnsupported("DSP engine currently supports mono/stereo")
            if (requireExactSource && requestedEncoding == null) {
                throw DirectUnsupported("Unknown lossless source bit depth; rescan library")
            }

            AudioPathMonitor.setSource(sourceRate, sourceBits, sourceChannels, requestedEncoding)
            durationMs = inputFormat.longOrNull(MediaFormat.KEY_DURATION)?.div(1000L) ?: 0L
            currentPositionMs = startPositionMs.coerceAtLeast(0L)
            extractor.seekTo(currentPositionMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            // FLAC can optionally honor a requested integer PCM precision. When DSP is active we
            // accept the decoder's actual PCM format and process it rather than falling back and
            // silently bypassing DSP.
            if (requestedEncoding != null && mime.contains("flac", true)) {
                runCatching { inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, requestedEncoding) }
            }

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
                decodeAudioLoop(
                    codec = codec,
                    extractor = extractor,
                    requestedEncoding = requestedEncoding,
                    sourceRate = sourceRate,
                    sourceChannels = sourceChannels,
                    token = token,
                    requireExactSource = requireExactSource,
                    sourceMime = mime,
                    item = item
                )
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun decodeAudioLoop(
        codec: MediaCodec,
        extractor: MediaExtractor,
        requestedEncoding: Int?,
        sourceRate: Int,
        sourceChannels: Int,
        token: Long,
        requireExactSource: Boolean,
        sourceMime: String,
        item: MediaItem
    ) {
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var track: AudioTrack? = null
        var actualEncoding = AudioFormat.ENCODING_INVALID
        var actualRate = sourceRate
        var actualChannels = sourceChannels
        var dsp: DspStreamAdapter? = null
        var dspActive = false
        var dspWriter: DspAudioWriter? = null

        while (!outputEnded && !stopRequested && generation.get() == token) {
            val requestedSeek = seekRequestMs.getAndSet(NO_SEEK)
            if (requestedSeek != NO_SEEK) {
                val target = requestedSeek.coerceAtLeast(0L)
                runCatching { track?.pause(); track?.flush() }
                extractor.seekTo(target * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                codec.flush()
                inputEnded = false
                outputEnded = false
                currentPositionMs = target
                dsp?.reset()
                dspWriter?.resetForSeek()
                if (!dspActive) track?.let(::startTrackIfRequested)
            }

            if (!inputEnded) {
                val inputIndex = codec.dequeueInputBuffer(2_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                        ?: throw DirectUnsupported("Decoder input buffer missing")
                    val size = extractor.readSampleData(inputBuffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = codec.dequeueOutputBuffer(info, 2_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = codec.outputFormat
                    actualEncoding = outputFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING)
                        ?: AudioFormat.ENCODING_PCM_16BIT
                    actualRate = outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sourceRate
                    actualChannels = outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: sourceChannels

                    if (requireExactSource && (requestedEncoding == null || actualEncoding != requestedEncoding || actualRate != sourceRate || actualChannels != sourceChannels)) {
                        throw DirectUnsupported(
                            "Decoder returned ${actualEncoding.pcmEncodingLabel()} ${actualRate}Hz/${actualChannels}ch; " +
                                "source requires ${requestedEncoding?.pcmEncodingLabel() ?: "unknown PCM"} ${sourceRate}Hz/${sourceChannels}ch"
                        )
                    }

                    dsp?.close()
                    dsp = createDspAdapter(actualRate, actualChannels, item)
                    activeDspAdapter = dsp
                    dspActive = dsp != null
                    if (!requireExactSource && !dspActive) {
                        throw DirectUnsupported("DSP was disabled while preparing; returning to Media3")
                    }
                    val outputEncoding = if (dspActive) AudioFormat.ENCODING_PCM_FLOAT else actualEncoding
                    dspWriter?.stop()
                    val newTrack = buildAudioTrack(actualRate, actualChannels, outputEncoding)
                    track = newTrack
                    audioTrack = newTrack
                    dspWriter = if (dspActive) {
                        DspAudioWriter(newTrack, actualRate, actualChannels, token).also {
                            activeDspWriter = it
                        }
                    } else null
                    val codecName = sourceMime.substringAfter('/').uppercase()
                    publishDirectOutput(
                        newTrack,
                        actualRate,
                        actualChannels,
                        outputEncoding,
                        if (dspActive) "Moka DSP · $codecName · 32-bit float" else "Moka Direct $codecName"
                    )
                    callback.onReady(durationMs)
                    if (!dspActive) startTrackIfRequested(newTrack)
                }
                else -> if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (info.size > 0 && outputBuffer != null) {
                        val activeTrack = track ?: throw DirectUnsupported("Decoder output arrived before format")
                        while (pauseRequested || !playRequested) {
                            if (stopRequested || generation.get() != token) break
                            Thread.sleep(20)
                        }
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        if (dspActive) {
                            val bytesPer = bytesPerSample(actualEncoding)
                            val samples = info.size / bytesPer
                            val sourceFloat = PcmFloatCodec.decode(
                                outputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN),
                                actualEncoding,
                                samples
                            )
                            val processed = dsp!!.process(sourceFloat)
                            if (processed.isNotEmpty()) {
                                dspWriter!!.enqueue(processed)
                            }
                        } else {
                            while (outputBuffer.hasRemaining() && !stopRequested && generation.get() == token) {
                                val written = activeTrack.write(outputBuffer, outputBuffer.remaining(), AudioTrack.WRITE_BLOCKING)
                                if (written < 0) throw DirectUnsupported("AudioTrack write failed: $written")
                            }
                        }
                        currentPositionMs = info.presentationTimeUs.coerceAtLeast(0L) / 1000L
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }

        if (outputEnded && !stopRequested && generation.get() == token) {
            if (dspActive) {
                val tail = dsp!!.flush()
                if (tail.isNotEmpty()) dspWriter?.enqueue(tail)
                dspWriter?.finishAndDrain()
                if (activeDspWriter === dspWriter) activeDspWriter = null
            }
            isPlaying = false
            setState(State.ENDED)
            callback.onEnded()
        }
    }

    private fun logUnderrunsIfChanged(track: AudioTrack, queueDepth: Int? = null) {
        val count = runCatching { track.underrunCount }.getOrDefault(lastUnderrunCount)
        if (count > lastUnderrunCount) {
            val previous = lastUnderrunCount
            lastUnderrunCount = count
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastUnderrunLogMs >= 1000L) {
                Log.w(
                    AUDIO_LOG_TAG,
                    "AudioTrack underruns increased: $previous -> $count " +
                        "(buffer=${runCatching { track.bufferSizeInFrames }.getOrDefault(-1)} frames" +
                        (queueDepth?.let { ", queue=$it/$DSP_QUEUE_PACKETS" } ?: "") + ")"
                )
                lastUnderrunLogMs = now
            }
        }
    }

    private fun createDspAdapter(sampleRate: Int, channels: Int, item: MediaItem): DspStreamAdapter? {
        val settings = DspSettingsStore(appContext).load()
        if (!settings.anyProcessingEnabled) return null
        val loader = DspFileLoader(appContext)
        val ddc = if (settings.ddcEnabled) runCatching { loader.loadVdc(settings.ddcUri) }.getOrNull() else null
        val irs = if (settings.convolverEnabled) runCatching { loader.loadIrs(settings.convolverUri) }.getOrNull() else null
        val extras = item.mediaMetadata.extras
        val normalizationGainDb = extras
            ?.takeIf { it.containsKey(AudioPathMonitor.EXTRA_NORMALIZATION_GAIN_DB) }
            ?.getFloat(AudioPathMonitor.EXTRA_NORMALIZATION_GAIN_DB)

        // Native C++ is the primary path for FIR/DDC/convolution. The previous Kotlin FFT path
        // benchmarked below realtime on the reference phone even with a two-second AudioTrack.
        // Keep the Kotlin chain as a compatibility fallback if the NDK library cannot be loaded
        // or when a still-Kotlin-only multimodal IIR mode is selected.
        val nativeBlockFrames = when {
            sampleRate >= 176_400 -> 16_384
            sampleRate >= 88_200 -> 8_192
            else -> 4_096
        }
        val nativeChain = runCatching {
            NativeDspChain.createOrNull(
                sampleRate = sampleRate,
                channels = channels,
                blockSize = nativeBlockFrames,
                settings = settings,
                ddc = ddc,
                irs = irs,
                normalizationGainDb = normalizationGainDb
            )
        }.onFailure {
            Log.w(AUDIO_LOG_TAG, "Native DSP creation failed; using Kotlin fallback", it)
        }.getOrNull()

        val chain = nativeChain ?: DspChain(
            sampleRate = sampleRate,
            channels = channels,
            settings = settings,
            ddc = ddc,
            irs = irs,
            normalizationGainDb = normalizationGainDb,
            blockSize = when {
                sampleRate >= 176_400 -> 16_384
                else -> 8_192
            }
        )

        Log.i(AUDIO_LOG_TAG, "DSP engine=${chain.implementationLabel}, block=${chain.blockSize} frames")
        AudioPathMonitor.beginDirectPath("Moka DSP · ${chain.implementationLabel} · 32-bit float")
        return DspStreamAdapter(chain, channels, sampleRate)
    }

    private fun startTrackIfRequested(track: AudioTrack): Boolean {
        return if (playRequested && !pauseRequested && requestAudioFocus()) {
            track.play()
            acquireWakeLock()
            isPlaying = true
            setState(State.PLAYING)
            true
        } else {
            isPlaying = false
            setState(State.READY)
            false
        }
    }

    private fun buildAudioTrack(sampleRate: Int, channels: Int, encoding: Int): AudioTrack {
        val channelMask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> throw DirectUnsupported("Direct engine currently supports mono/stereo")
        }
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(encoding)
            .setChannelMask(channelMask)
            .build()

        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuffer <= 0) throw DirectUnsupported("Device does not accept ${encoding.pcmEncodingLabel()} at ${sampleRate}Hz")
        val frameBytes = bytesPerSample(encoding) * channels
        // Device logs from v3.4 showed DSP was slightly slower than real time even with a
        // one-second sink buffer. Music playback can afford more latency, so keep roughly two
        // seconds in AudioTrack and prime most of it before starting.
        val targetFrames = max(16_384, sampleRate * 2)
        val bufferBytes = max(minBuffer * 8, frameBytes * targetFrames)

        val mixer = if (Build.VERSION.SDK_INT >= 34) selectExactUsbMixer(format) else null
        val builder = AudioTrack.Builder()
            .setAudioAttributes(mediaAttributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)

        val track = builder.build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            clearPreferredUsbMixer()
            throw DirectUnsupported("AudioTrack failed to initialize")
        }
        activeUsbDevice?.let { device -> runCatching { track.setPreferredDevice(device) } }
        lastUnderrunCount = runCatching { track.underrunCount }.getOrDefault(0)
        lastUnderrunLogMs = 0L
        Log.i(
            AUDIO_LOG_TAG,
            "AudioTrack created: rate=$sampleRate channels=$channels encoding=${encoding.pcmEncodingLabel()} " +
                "buffer=${runCatching { track.bufferSizeInFrames }.getOrDefault(-1)} frames " +
                "capacity=${runCatching { track.bufferCapacityInFrames }.getOrDefault(-1)} frames"
        )

        val actual = track.format
        val exactTrack = actual.sampleRate == sampleRate && actual.encoding == encoding && actual.channelCount == channels
        if (!exactTrack) {
            track.release()
            clearPreferredUsbMixer()
            throw DirectUnsupported("AudioTrack changed the requested PCM format")
        }

        val usbVerified = mixer != null && mixer.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
            mixer.format.sampleRate == actual.sampleRate && mixer.format.encoding == actual.encoding &&
            mixer.format.channelCount == actual.channelCount

        AudioPathMonitor.recordDirectOutput(
            sampleRateHz = actual.sampleRate,
            encoding = actual.encoding,
            channels = actual.channelCount,
            usbMixerRequested = mixer != null,
            usbMixerBitPerfect = mixer?.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
            usbTransportVerified = usbVerified,
            label = "Moka Direct PCM"
        )
        return track
    }

    private fun publishDirectOutput(
        track: AudioTrack,
        sampleRate: Int,
        channels: Int,
        encoding: Int,
        label: String
    ) {
        val usb = activeUsbDevice
        val preferred = if (Build.VERSION.SDK_INT >= 34 && usb != null) {
            runCatching { audioManager.getPreferredMixerAttributes(mediaAttributes, usb) }.getOrNull()
        } else null
        val verified = preferred != null &&
            preferred.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
            preferred.format.sampleRate == track.format.sampleRate &&
            preferred.format.encoding == track.format.encoding &&
            preferred.format.channelCount == track.format.channelCount
        AudioPathMonitor.recordDirectOutput(
            sampleRateHz = sampleRate,
            encoding = encoding,
            channels = channels,
            usbMixerRequested = preferred != null,
            usbMixerBitPerfect = preferred?.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
            usbTransportVerified = verified,
            label = label
        )
    }

    @RequiresApi(34)
    private fun selectExactUsbMixer(format: AudioFormat): AudioMixerAttributes? {
        val usbDevice = runCatching {
            audioManager.getAudioDevicesForAttributes(mediaAttributes)
                .firstOrNull { it.type.isUsbAudio() }
        }.getOrNull() ?: run {
            clearPreferredUsbMixer()
            return null
        }

        val exact = runCatching { audioManager.getSupportedMixerAttributes(usbDevice) }
            .getOrDefault(emptyList())
            .firstOrNull { mixer ->
                mixer.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    mixer.format.sampleRate == format.sampleRate &&
                    mixer.format.encoding == format.encoding &&
                    mixer.format.channelCount == format.channelCount
            } ?: run {
                clearPreferredUsbMixer()
                return null
            }

        clearPreferredUsbMixer()
        val selected = runCatching {
            audioManager.setPreferredMixerAttributes(mediaAttributes, usbDevice, exact)
        }.getOrDefault(false)
        activeUsbDevice = usbDevice.takeIf { selected }
        return exact.takeIf { selected }
    }

    private fun clearPreferredUsbMixer() {
        if (Build.VERSION.SDK_INT >= 34) {
            activeUsbDevice?.let { device ->
                runCatching { audioManager.clearPreferredMixerAttributes(mediaAttributes, device) }
            }
        }
        activeUsbDevice = null
    }


    private fun routeAllowsDirectPcm(): Boolean {
        val devices = if (Build.VERSION.SDK_INT >= 33) {
            runCatching { audioManager.getAudioDevicesForAttributes(mediaAttributes) }.getOrDefault(emptyList())
        } else {
            runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
        }
        val routed = devices.firstOrNull { it.type.isUsbAudio() }
            ?: devices.firstOrNull { it.type.isWiredAudio() }
            ?: devices.firstOrNull { it.type.isBluetoothAudio() }
            ?: devices.firstOrNull()
        return routed?.type.isUsbAudio() || routed?.type.isWiredAudio()
    }

    private fun requestAudioFocus(): Boolean {
        if (focusRequest == null) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(mediaAttributes)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                    }
                }
                .build()
        }
        return audioManager.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
    }

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) runCatching { wakeLock.acquire(10 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) runCatching { wakeLock.release() }
    }

    private fun setState(newState: State) {
        state = newState
        callback.onStateChanged(newState)
    }

    private fun stopInternal(clearMonitor: Boolean) {
        stopRequested = true
        playRequested = false
        pauseRequested = true
        activeDspWriter?.stop()
        activeDspWriter = null
        activeDspAdapter?.close()
        activeDspAdapter = null
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        releaseAudioTrack()
        abandonAudioFocus()
        releaseWakeLock()
        clearPreferredUsbMixer()
        isPlaying = false
        durationMs = 0L
        currentPositionMs = 0L
        state = State.IDLE
        if (clearMonitor) {
            AudioPathMonitor.endDirectPath()
            AudioPathMonitor.clearOutput()
        }
    }

    private fun releaseAudioTrack() {
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun parseWav(channel: java.nio.channels.FileChannel, baseOffset: Long): WavInfo {
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(baseOffset)
        readFully(channel, header)
        header.flip()
        val riff = ByteArray(4).also { header.get(it) }.toString(Charsets.US_ASCII)
        header.int // RIFF length
        val wave = ByteArray(4).also { header.get(it) }.toString(Charsets.US_ASCII)
        if (riff != "RIFF" || wave != "WAVE") throw DirectUnsupported("Unsupported WAV container")

        var sampleRate = 0
        var channels = 0
        var bits = 0
        var formatCode = 0
        var blockAlign = 0
        var byteRate = 0L
        var extensibleSubFormat = 0
        var dataOffset = -1L
        var dataSize = -1L

        val chunkHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        while (channel.position() + 8 <= channel.size()) {
            chunkHeader.clear()
            readFully(channel, chunkHeader)
            chunkHeader.flip()
            val id = ByteArray(4).also { chunkHeader.get(it) }.toString(Charsets.US_ASCII)
            val size = chunkHeader.int.toLong() and 0xffffffffL
            val chunkStart = channel.position()
            when (id) {
                "fmt " -> {
                    val fmt = ByteBuffer.allocate(minOf(size, 64L).toInt()).order(ByteOrder.LITTLE_ENDIAN)
                    readFully(channel, fmt)
                    fmt.flip()
                    if (fmt.remaining() < 16) throw DirectUnsupported("Invalid WAV fmt chunk")
                    formatCode = fmt.short.toInt() and 0xffff
                    channels = fmt.short.toInt() and 0xffff
                    sampleRate = fmt.int
                    byteRate = fmt.int.toLong() and 0xffffffffL
                    blockAlign = fmt.short.toInt() and 0xffff
                    bits = fmt.short.toInt() and 0xffff
                    if (formatCode == WAVE_FORMAT_EXTENSIBLE && fmt.remaining() >= 24) {
                        val cbSize = fmt.short.toInt() and 0xffff
                        if (cbSize >= 22 && fmt.remaining() >= 22) {
                            fmt.short // valid bits
                            fmt.int // channel mask
                            extensibleSubFormat = fmt.short.toInt() and 0xffff
                        }
                    }
                }
                "data" -> {
                    dataOffset = chunkStart
                    dataSize = size
                }
            }
            val next = chunkStart + size + (size and 1L)
            channel.position(next.coerceAtMost(channel.size()))
            if (dataOffset >= 0 && sampleRate > 0) break
        }

        if (dataOffset < 0 || dataSize < 0 || sampleRate <= 0 || channels <= 0 || bits <= 0) {
            throw DirectUnsupported("Incomplete WAV header")
        }
        val effectiveFormat = if (formatCode == WAVE_FORMAT_EXTENSIBLE) extensibleSubFormat else formatCode
        val encoding = when (effectiveFormat) {
            WAVE_FORMAT_PCM -> bitDepthToEncoding(bits)
            WAVE_FORMAT_IEEE_FLOAT -> if (bits == 32) AudioFormat.ENCODING_PCM_FLOAT else null
            else -> null
        } ?: throw DirectUnsupported("Unsupported WAV PCM format code=$effectiveFormat bits=$bits")

        return WavInfo(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bits,
            encoding = encoding,
            byteRate = byteRate,
            blockAlign = blockAlign,
            dataOffset = dataOffset,
            dataSize = dataSize
        )
    }

    private fun readFully(channel: java.nio.channels.FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw DirectUnsupported("Unexpected end of file")
        }
    }

    private data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val encoding: Int,
        val byteRate: Long,
        val blockAlign: Int,
        val dataOffset: Long,
        val dataSize: Long
    )

    private data class PcmPacket(
        val epoch: Int,
        val samples: FloatArray? = null,
        val endOfStream: Boolean = false
    )

    /**
     * Decouples decode/DSP work from AudioTrack writes.
     *
     * The producer thread can stay ahead by several processed blocks while this dedicated
     * urgent-audio writer keeps the USB/wired sink fed. This is specifically to avoid the
     * recurring AudioFlinger BUFFER TIMEOUT underruns seen when DSP was enabled.
     */
    private inner class DspAudioWriter(
        private val track: AudioTrack,
        private val sampleRate: Int,
        private val channels: Int,
        private val token: Long
    ) {
        private val queue = ArrayBlockingQueue<PcmPacket>(DSP_QUEUE_PACKETS)
        private val epoch = AtomicInteger(0)
        private val finished = CountDownLatch(1)
        private val worker = Thread(::writerLoop, "MokaAudioWriter")
        @Volatile private var stopping = false
        @Volatile private var failure: Throwable? = null
        @Volatile var started = false
            private set
        private val primedSamples = AtomicInteger(0)

        init {
            worker.priority = Thread.MAX_PRIORITY
            worker.start()
        }

        fun enqueue(samples: FloatArray) {
            failure?.let { throw it }
            if (samples.isEmpty() || stopping) return
            val packet = PcmPacket(epoch.get(), samples = samples)
            while (!stopping && !stopRequested && generation.get() == token) {
                try {
                    failure?.let { throw it }
                    if (queue.offer(packet, 100, TimeUnit.MILLISECONDS)) return
                } catch (_: InterruptedException) {
                    if (stopping || stopRequested) return
                }
            }
        }

        fun resetForSeek() {
            epoch.incrementAndGet()
            queue.clear()
            primedSamples.set(0)
            started = false
            runCatching { track.pause() }
            runCatching { track.flush() }
        }

        fun finishAndDrain() {
            failure?.let { throw it }
            if (stopping) return
            val endPacket = PcmPacket(epoch.get(), endOfStream = true)
            while (!stopping && generation.get() == token) {
                try {
                    if (queue.offer(endPacket, 100, TimeUnit.MILLISECONDS)) break
                } catch (_: InterruptedException) {
                    if (stopping) return
                }
            }
            val drained = try {
                finished.await(DSP_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                false
            }
            if (!drained) {
                stop()
                throw DirectUnsupported("DSP AudioTrack writer timed out while draining")
            }
            failure?.let { throw it }
        }

        fun stop() {
            if (stopping) return
            stopping = true
            epoch.incrementAndGet()
            queue.clear()
            runCatching { track.pause() }
            runCatching { track.flush() }
            worker.interrupt()
            runCatching { finished.await(1, TimeUnit.SECONDS) }
        }

        private fun writerLoop() {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            try {
                while (!stopping && !stopRequested && generation.get() == token) {
                    val packet = try {
                        queue.take()
                    } catch (_: InterruptedException) {
                        if (stopping || stopRequested) break
                        continue
                    }
                    if (packet.epoch != epoch.get()) continue

                    if (packet.endOfStream) {
                        if (!started && primedSamples.get() > 0 && playRequested && !pauseRequested) {
                            started = startTrackIfRequested(track)
                        }
                        if (started) {
                            // MODE_STREAM stop drains all already-written data before ending.
                            runCatching { track.stop() }
                        }
                        break
                    }

                    val samples = packet.samples ?: continue
                    writePacket(samples, packet.epoch)
                }
            } catch (t: Throwable) {
                failure = t
                stopping = true
            } finally {
                finished.countDown()
            }
        }

        private fun writePacket(samples: FloatArray, packetEpoch: Int) {
            var offset = 0
            val capacityFrames = runCatching { track.bufferCapacityInFrames }.getOrDefault(sampleRate * 2)
            val primeTargetFrames = minOf(
                max(DSP_PRIME_MIN_FRAMES, sampleRate * 3 / 2),
                max(DSP_PRIME_MIN_FRAMES, capacityFrames * 3 / 4)
            )
            val primeTargetSamples = primeTargetFrames * channels

            while (
                offset < samples.size &&
                !stopping &&
                !stopRequested &&
                generation.get() == token &&
                packetEpoch == epoch.get()
            ) {
                if (pauseRequested || !playRequested) {
                    try {
                        Thread.sleep(5)
                    } catch (_: InterruptedException) {
                        if (stopping || stopRequested) return
                    }
                    continue
                }

                val mode = if (started) AudioTrack.WRITE_BLOCKING else AudioTrack.WRITE_NON_BLOCKING
                val written = track.write(samples, offset, samples.size - offset, mode)
                if (written < 0) {
                    throw DirectUnsupported("AudioTrack float write failed: $written")
                }

                if (written == 0) {
                    if (!started) {
                        // Effective device buffer is full before the nominal prime target.
                        started = startTrackIfRequested(track)
                    } else {
                        Thread.yield()
                    }
                    continue
                }

                offset += written
                if (!started) {
                    val primed = primedSamples.addAndGet(written)
                    if (primed >= primeTargetSamples) {
                        started = startTrackIfRequested(track)
                        if (started) {
                            Log.i(
                                AUDIO_LOG_TAG,
                                "DSP writer started after ${primed / channels} frames; " +
                                    "queue=${queue.size}/$DSP_QUEUE_PACKETS"
                            )
                        }
                    }
                }
                logUnderrunsIfChanged(track, queue.size)
            }
        }
    }

    private class DirectUnsupported(message: String) : Exception(message)

    companion object {
        private const val AUDIO_LOG_TAG = "MokaAudio"
        private const val DSP_IO_FRAMES = 4096
        private const val DSP_PRIME_MIN_FRAMES = 16_384
        private const val DSP_QUEUE_PACKETS = 64
        private const val DSP_DRAIN_TIMEOUT_SECONDS = 8L
        private const val NO_SEEK = -1L
        private const val WAVE_FORMAT_PCM = 0x0001
        private const val WAVE_FORMAT_IEEE_FLOAT = 0x0003
        private const val WAVE_FORMAT_EXTENSIBLE = 0xfffe

        private fun bitDepthToEncoding(bitDepth: Int): Int? = when (bitDepth) {
            8 -> AudioFormat.ENCODING_PCM_8BIT
            16 -> AudioFormat.ENCODING_PCM_16BIT
            24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            32 -> AudioFormat.ENCODING_PCM_32BIT
            else -> null
        }

        private fun bytesPerSample(encoding: Int): Int = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> throw DirectUnsupported("Unknown PCM encoding")
        }
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

private fun MediaFormat.longOrNull(key: String): Long? =
    if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

private fun Int?.isUsbAudio(): Boolean = when (this) {
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> true
    else -> false
}


private fun Int?.isWiredAudio(): Boolean = when (this) {
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> true
    else -> false
}

private fun Int?.isBluetoothAudio(): Boolean = when (this) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST -> true
    else -> false
}
