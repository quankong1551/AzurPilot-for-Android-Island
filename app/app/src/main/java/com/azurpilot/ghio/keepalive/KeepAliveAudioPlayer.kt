package com.azurpilot.ghio.keepalive

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 后台 24 小时无音量音频播放器
 *
 * 通过在底层 AudioFlinger 中保持一条活跃的无声音频流（AudioTrack MODE_STATIC 循环），
 * 让 Android 系统将本应用进程识别为正在进行媒体播放的前台活跃实体，从而防止进程被系统的
 * Low Memory Killer (LMK) 查杀，实现强效后台保活。数据全 0 且音量为 0，用户无感知。
 *
 * 24/7 background silent audio player.
 *
 * Keeps an active silent audio stream (an AudioTrack looping in MODE_STATIC) alive in
 * the underlying AudioFlinger, so Android recognizes the app process as an active media
 * playback entity. That prevents termination by the Low Memory Killer (LMK) and delivers
 * robust background persistence. The data is all zeros and the volume is 0, so the user
 * hears nothing.
 */
class KeepAliveAudioPlayer {

    private val lock = Any()
    private var audioTrack: AudioTrack? = null

    private val _isPlaying = MutableStateFlow(false)

    /** 当前是否处于静音播放状态，供 UI 与自检读取 / Whether silent playback is active; read by the UI and the self-check. */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /**
     * 启动静音循环播放；幂等，已在播时直接返回
     *
     * 任意线程可调，内部以 [lock] 串行化；初始化失败时回滚到已停止状态
     *
     * Starts the silent looping playback; idempotent, returns immediately when
     * already playing.
     *
     * Callable from any thread, serialized on [lock]; on initialization failure it
     * rolls back to a fully stopped state.
     */
    fun start() {
        synchronized(lock) {
            if (audioTrack != null && _isPlaying.value) {
                return
            }
            stopInternal()
            try {
                val sampleRate = 44100
                val channelConfig = AudioFormat.CHANNEL_OUT_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                // 1 秒静音缓冲区 (44100 samples * 2 bytes = 88200 bytes)
                val bufferSize = maxOf(minBufferSize, sampleRate * 2)
                val silentData = ByteArray(bufferSize) // 全 0 静音字节
                val frameCount = bufferSize / 2 // 16-bit 单声道，每帧 2 字节

                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

                val format = AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()

                val track = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(silentData, 0, silentData.size)
                // 静音：音量置为 0，且数据全为 0
                track.setVolume(0.0f)
                // 无限循环播放 (-1 表示 loop forever)
                track.setLoopPoints(0, frameCount, -1)
                track.play()

                audioTrack = track
                _isPlaying.value = true
                Timber.d("KeepAliveAudioPlayer: Silent audio playback started successfully (looping)")
            } catch (e: Exception) {
                Timber.e(e, "KeepAliveAudioPlayer: Failed to start silent audio playback")
                stopInternal()
            }
        }
    }

    /**
     * 停止并释放 AudioTrack；任意线程可调
     *
     * Stops and releases the AudioTrack; callable from any thread.
     */
    fun stop() {
        synchronized(lock) {
            stopInternal()
        }
    }

    /**
     * 自检播放状态：track 丢失或已停止播放时重启，由保活心跳周期性调用
     *
     * Self-checks the playback state and restarts when the track is gone or no
     * longer playing; invoked periodically by the keep-alive heartbeat.
     */
    fun ensurePlaying() {
        synchronized(lock) {
            val track = audioTrack
            if (track == null || track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                Timber.w("KeepAliveAudioPlayer: Audio playback interrupted or track lost, restarting...")
                start()
            }
        }
    }

    /** 停止并释放 track、复位状态；调用方必须已持有 [lock] / Stops and releases the track and resets state; callers must hold [lock]. */
    private fun stopInternal() {
        val track = audioTrack
        audioTrack = null
        _isPlaying.value = false
        if (track != null) {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
                Timber.d("KeepAliveAudioPlayer: Silent audio playback stopped and released")
            } catch (e: Exception) {
                Timber.w(e, "KeepAliveAudioPlayer: Error while stopping AudioTrack")
            }
        }
    }
}
