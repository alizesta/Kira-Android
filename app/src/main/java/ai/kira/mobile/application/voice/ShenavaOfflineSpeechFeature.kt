package ai.kira.mobile.application.voice

import ai.kira.mobile.domain.model.ShenavaModelVariant
import ai.kira.mobile.domain.repository.ShenavaOfflineSpeechRepository

/** Application boundary for downloading and using Shenava without a network ASR service. */
class ShenavaOfflineSpeechInteractor(
    private val repository: ShenavaOfflineSpeechRepository
) {
    fun isModelDownloaded(variant: ShenavaModelVariant): Boolean =
        repository.isModelDownloaded(variant)

    fun downloadModel(
        variant: ShenavaModelVariant,
        onProgress: (percent: Int) -> Unit
    ) {
        repository.downloadModel(variant, onProgress)
    }

    fun startRecording(variant: ShenavaModelVariant) {
        check(repository.isModelDownloaded(variant)) {
            "ابتدا مدل شنوا را دانلود کن."
        }
        repository.startRecording()
    }

    fun stopRecordingAndTranscribe(variant: ShenavaModelVariant): String =
        repository.stopRecordingAndTranscribe(variant).trim()

    fun cancelRecording() = repository.cancelRecording()
}
