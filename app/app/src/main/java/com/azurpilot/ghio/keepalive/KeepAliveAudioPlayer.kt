package com.azurpilot.ghio.keepalive

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 后台 24 小时无音量音频播放器 / 24/7 background silent audio player
 *
 * 通过在底层 AudioFlinger 中保持一条活跃的无声音频流（AudioTrack MODE_STATIC 循环），
 * 让 Android 系统将本应用进程识别为正在进行媒体播放的前台活跃实体，从而防止进程被系统的
 * Low Memory Killer (LMK) 查杀，实现强效后台保活。
 *
 * Maintains an active silent audio stream in Android's AudioFlinger using AudioTrack in MODE_STATIC loop.
 * The system recognizes the app process as an active media playback entity, preventing termination by
 * the Low Memory Killer (LMK) and achieving robust background persistence without audible output or battery drain.
 */
class KeepAliveAudioPlayer {

    private val lock = Any()
    private var audioTrack: AudioTrack? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

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

    fun stop() {
        synchronized(lock) {
            stopInternal()
        }
    }

    /** 检查并在需要时恢复播放 / Check and revive playback if interrupted */
    fun ensurePlaying() {
        synchronized(lock) {
            val track = audioTrack
            if (track == null || track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                Timber.w("KeepAliveAudioPlayer: Audio playback interrupted or track lost, restarting...")
                start()
            }
        }
    }

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
