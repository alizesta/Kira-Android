package ai.kira.mobile.domain.repository

import ai.kira.mobile.domain.model.ShenavaModelVariant

/** Device-local model storage, microphone capture and offline speech recognition. */
interface ShenavaOfflineSpeechRepository {
    fun isModelDownloaded(variant: ShenavaModelVariant): Boolean

    /** Download the selected model into app-private storage. Progress is 0..100. */
    fun downloadModel(
        variant: ShenavaModelVariant,
        onProgress: (percent: Int) -> Unit
    )

    fun startRecording()

    fun stopRecordingAndTranscribe(variant: ShenavaModelVariant): String

    /** Stop and discard an unfinished recording without running inference. */
    fun cancelRecording()
}
