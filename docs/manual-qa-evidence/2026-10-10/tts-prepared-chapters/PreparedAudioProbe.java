package com.retro99.parrot.preparedprobe;

import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.nio.ByteBuffer;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Temporary instrumentation, no library or app configuration changes. Logs numbers only. */
public final class PreparedAudioProbe extends Instrumentation {
    private static final String TAG = "PreparedAudioProbe";
    private ClassLoader loader;
    private Object koin;
    private File root;
    private boolean ownsRoot;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = 0;
        try {
            waitForIdleSync(); // Application.onCreate must finish wiring Koin before this worker.
            loader = getTargetContext().getClassLoader();
            Object global = cls("org.koin.core.context.GlobalContext").getField("INSTANCE").get(null);
            koin = call(global, "get");
            root = new File(getTargetContext().getCacheDir(), "prepared-step1-probe");
            if (root.exists()) throw new IllegalStateException("Probe folder already exists; preserve it");
            if (!root.mkdir()) throw new IllegalStateException("Cannot create probe folder");
            ownsRoot = true;
            Object synth = get("com.retro99.reader.ui.tts.TtsSynthesizer");
            Object systemSynth = get("com.retro99.reader.ui.tts.AndroidSystemTtsSynthesizer");
            Log.i(TAG, "STAGE system_ready");
            if (!Boolean.TRUE.equals(suspend(systemSynth, "awaitReady", 3000L)))
                throw new IllegalStateException("System synthesizer not ready");
            Object defaultVoice = call(systemSynth, "defaultVoice");
            Log.i(TAG, "STAGE system_voice");
            String system = (String) call(defaultVoice, "getId");
            if (system.startsWith("kokoro:") || system.startsWith("supertonic:"))
                throw new IllegalStateException("No default system voice");
            if (!Boolean.TRUE.equals(suspend(synth, "warmUp", "kokoro:af_heart")))
                throw new IllegalStateException("Downloaded Kokoro Heart unavailable");
            Log.i(TAG, "STAGE kokoro_ready");
            Object encoder = cls("com.retro99.reader.ui.tts.AndroidTtsPreparedAacEncoder").getConstructor().newInstance();
            String[] texts = {"Down, down, down.", "Would the fall never come to an end?",
                "I wonder how many miles I've fallen by this time?"};
            for (int kind = 0; kind < 2; kind++) {
                String voice = kind == 0 ? "kokoro:af_heart" : system;
                File[] wavs = new File[3], encoded = new File[3];
                long[] wavDurations = new long[3], encodedDurations = new long[3];
                for (int i = 0; i < 3; i++) {
                    int index = kind * 3 + i;
                    wavs[i] = new File(root, index + ".wav");
                    encoded[i] = new File(root, index + ".m4a");
                    Object generated = suspend(synth, "synthesize", texts[i], voice, 1f, 1f, wavs[i]);
                    if (!"SUCCESS".equals(call(generated, "getStatus").toString()))
                        throw new IllegalStateException("Synthesis failed at " + index);
                    Object companion = cls("com.retro99.reader.ui.tts.PreparedPcmWav").getField("Companion").get(null);
                    Object pcm = call(companion, "read", wavs[i]);
                    wavDurations[i] = ((Number) call(pcm, "getDurationMs")).longValue();
                    Object compressed = suspend(encoder, "encode", wavs[i], encoded[i]);
                    if (!compressed.getClass().getSimpleName().equals("Success"))
                        throw new IllegalStateException("Encode failed at " + index);
                    encodedDurations[i] = ((Number) call(compressed, "getDurationMs")).longValue();
                    Log.i(TAG, "MEASURE index=" + index + " kind=" + (kind == 0 ? "kokoro" : "system")
                        + " wav_bytes=" + wavs[i].length() + " m4a_bytes=" + encoded[i].length()
                        + " wav_ms=" + wavDurations[i] + " m4a_ms=" + encodedDurations[i]
                        + " delta_ms=" + (encodedDurations[i] - wavDurations[i]));
                    tryPaddingMetadata(encoded[i], wavDurations[i], index);
                }
                play(synth, voice, texts, wavs, wavDurations, "wav", kind);
                play(synth, voice, texts, encoded, encodedDurations, "m4a", kind);
            }
            result.putString("result", "six measured; engine played WAV/M4A; acoustic judgment requires a human");
        } catch (Throwable error) {
            code = 1;
            // Do not retain exception messages from app dependencies (may include paths/content).
            Log.e(TAG, "BLOCKED " + error.getClass().getSimpleName());
            Throwable cause = error;
            while (cause != null) {
                Log.e(TAG, "CAUSE " + cause.getClass().getSimpleName());
                for (StackTraceElement frame : cause.getStackTrace())
                    Log.e(TAG, "FRAME " + frame.getClassName() + "." + frame.getMethodName());
                cause = cause.getCause();
            }
            result.putString("result", "blocked: " + error.getClass().getSimpleName());
        } finally {
            // Only this probe's numbered files; no cache/prepared-store deletion.
            if (ownsRoot) {
                for (int i = 0; i < 6; i++) {
                    new File(root, i + ".wav").delete();
                    new File(root, i + ".m4a").delete();
                    new File(root, i + ".trim.m4a").delete();
                }
                root.delete();
            }
        }
        finish(code, result);
    }

    /** Lossless remux requesting decoder priming/padding trim; never removes spoken AAC packets. */
    private void tryPaddingMetadata(File source, long wavMs, int index) throws Exception {
        File trimmed = new File(root, index + ".trim.m4a");
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            MediaFormat format = extractor.getTrackFormat(0);
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            long durationUs = format.getLong(MediaFormat.KEY_DURATION);
            int delay = 1024;
            int padding = (int) Math.max(0L, durationUs * rate / 1000000 - wavMs * rate / 1000 - delay);
            format.setInteger(MediaFormat.KEY_ENCODER_DELAY, delay);
            format.setInteger(MediaFormat.KEY_ENCODER_PADDING, padding);
            muxer = new MediaMuxer(trimmed.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = muxer.addTrack(format);
            muxer.start(); started = true;
            extractor.selectTrack(0);
            ByteBuffer buffer = ByteBuffer.allocate(65536);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                buffer.clear();
                int count = extractor.readSampleData(buffer, 0);
                if (count < 0) break;
                info.set(0, count, extractor.getSampleTime(), extractor.getSampleFlags());
                muxer.writeSampleData(track, buffer, info);
                extractor.advance();
            }
            muxer.stop(); started = false;
        } finally {
            extractor.release();
            if (muxer != null) { if (started) muxer.stop(); muxer.release(); }
        }
        MediaExtractor check = new MediaExtractor();
        try {
            check.setDataSource(trimmed.getAbsolutePath());
            MediaFormat format = check.getTrackFormat(0);
            long actual = format.getLong(MediaFormat.KEY_DURATION) / 1000;
            Log.i(TAG, "TRIM index=" + index + " duration_ms=" + actual + " delta_ms=" + (actual - wavMs)
                + " delay_metadata=" + format.containsKey(MediaFormat.KEY_ENCODER_DELAY)
                + " padding_metadata=" + format.containsKey(MediaFormat.KEY_ENCODER_PADDING));
        } finally { check.release(); }
    }

    private void play(Object synth, String voice, String[] texts, File[] files, long[] durations,
                      String format, int kind) throws Exception {
        Object success = Enum.valueOf((Class) cls("com.retro99.reader.ui.tts.TtsSynthesisStatus"), "SUCCESS");
        Class<?> resultType = cls("com.retro99.reader.ui.tts.TtsSynthesisResult");
        Object source = Proxy.newProxyInstance(loader,
            new Class[]{cls("com.retro99.reader.ui.tts.TtsSentenceAudioSource")}, (proxy, method, args) -> {
                int index = Arrays.asList(texts).indexOf(args[0]);
                if (index < 0) throw new IllegalStateException("Unknown probe sentence");
                return resultType.getConstructor(cls("com.retro99.reader.ui.tts.TtsSynthesisStatus"),
                    File.class, String.class, Long.class).newInstance(success, files[index], null, durations[index]);
            });
        Object main = cls("kotlinx.coroutines.Dispatchers").getMethod("getMain").invoke(null);
        Object immediate = call(main, "getImmediate");
        Object engine = cls("com.retro99.reader.ui.tts.TtsReadAloudEngine").getConstructor(
            cls("com.retro99.reader.ui.tts.TtsSynthesizer"), cls("com.retro99.reader.ui.tts.TtsSentenceAudioSource"),
            cls("com.retro99.reader.ui.playback.MediaPlaybackController"),
            cls("com.retro99.reader.ui.tts.TtsEnginePlayerProvider"), cls("kotlin.coroutines.CoroutineContext")
        ).newInstance(synth, source, get("com.retro99.reader.ui.playback.MediaPlaybackController"),
            get("com.retro99.reader.ui.tts.TtsEnginePlayerProvider"), immediate);
        try {
            List<Object> sentences = new ArrayList<>();
            Constructor<?> constructor = cls("com.retro99.reader.ui.tts.TtsSentence").getConstructor(
                int.class, String.class, String.class, Integer.class, String.class);
            for (int i = 0; i < 3; i++) sentences.add(constructor.newInstance(i, "probe-" + i, texts[i], null, null));
            runOnMainSync(() -> { try { call(engine, "setSentences", sentences); } catch (Exception e) { throw new RuntimeException(e); } });
            suspendInvoke(engine, "playFrom", true, 0, voice, 1f, 1f, false, false);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            Set<Integer> seen = new LinkedHashSet<>();
            while (System.nanoTime() < deadline) {
                Object sentence = call(call(engine, "getCurrentSentence"), "getValue");
                if (sentence != null) seen.add(((Number) call(sentence, "getIndex")).intValue());
                if (seen.contains(2) && !Boolean.TRUE.equals(call(call(engine, "isSessionRunning"), "getValue"))) break;
                Thread.sleep(50);
            }
            Log.i(TAG, "PLAY kind=" + kind + " format=" + format + " indices=" + seen);
            if (!seen.containsAll(Arrays.asList(0, 1, 2))) throw new IllegalStateException("Incomplete engine playback");
        } finally {
            runOnMainSync(() -> { try { call(engine, "close"); } catch (Exception e) { throw new RuntimeException(e); } });
        }
    }

    private Class<?> cls(String name) throws Exception { return Class.forName(name, true, loader); }
    private Object get(String name) throws Exception {
        Object klass = cls("kotlin.jvm.JvmClassMappingKt").getMethod("getKotlinClass", Class.class).invoke(null, cls(name));
        return call(koin, "get", klass, null, null);
    }
    private Object call(Object target, String name, Object... args) throws Exception {
        Method chosen = null;
        for (Method method : target.getClass().getMethods())
            if (method.getName().equals(name) && method.getParameterCount() == args.length) { chosen = method; break; }
        if (chosen == null) throw new NoSuchMethodException(name);
        chosen.setAccessible(true);
        return chosen.invoke(target, args);
    }
    private Object suspend(Object target, String name, Object... args) throws Exception {
        return suspendInvoke(target, name, false, args);
    }
    private Object suspendInvoke(Object target, String name, boolean onMain, Object... args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Object[] answer = new Object[1];
        Object continuation = Proxy.newProxyInstance(loader, new Class[]{cls("kotlin.coroutines.Continuation")},
            (proxy, method, values) -> {
                if (method.getName().equals("getContext")) return cls("kotlin.coroutines.EmptyCoroutineContext").getField("INSTANCE").get(null);
                if (method.getName().equals("resumeWith")) { answer[0] = values[0]; done.countDown(); }
                return null;
            });
        Object[] full = Arrays.copyOf(args, args.length + 1);
        full[args.length] = continuation;
        Object[] direct = new Object[1];
        Exception[] invocationError = new Exception[1];
        if (onMain) runOnMainSync(() -> {
            try { direct[0] = call(target, name, full); }
            catch (Exception e) { invocationError[0] = e; }
        });
        else direct[0] = call(target, name, full);
        if (invocationError[0] != null) throw invocationError[0];
        Object value = direct[0];
        Object suspended = cls("kotlin.coroutines.intrinsics.IntrinsicsKt").getMethod("getCOROUTINE_SUSPENDED").invoke(null);
        if (value == suspended) {
            if (!done.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("Probe suspend timeout");
            value = answer[0];
        }
        cls("kotlin.ResultKt").getMethod("throwOnFailure", Object.class).invoke(null, value);
        return value;
    }
}
