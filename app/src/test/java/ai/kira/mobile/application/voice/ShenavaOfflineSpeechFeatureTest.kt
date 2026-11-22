package ai.kira.mobile.application.voice

import ai.kira.mobile.domain.model.ShenavaModelVariant
import ai.kira.mobile.domain.repository.ShenavaOfflineSpeechRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShenavaOfflineSpeechFeatureTest {
    @Test
    fun exposesBothModelVariantsWithExpectedRepositoriesAndSizes() {
        assertEquals("SajjadMajdi/shenava-koochik-fa-int8", ShenavaModelVariant.INT8.repositoryId)
        assertEquals("model.int8.onnx", ShenavaModelVariant.INT8.modelFileName)
        assertEquals(126, ShenavaModelVariant.INT8.approximateSizeMb)

        assertEquals("Reza2kn/Shenava-Koochik-v1.0-sherpa-onnx", ShenavaModelVariant.FP32.repositoryId)
        assertEquals("model.onnx", ShenavaModelVariant.FP32.modelFileName)
        assertEquals(459, ShenavaModelVariant.FP32.approximateSizeMb)
        assertEquals(ShenavaModelVariant.INT8, ShenavaModelVariant.fromStorageKey("unknown"))
    }

    @Test
    fun recordingIsBlockedUntilTheSelectedModelHasDownloaded() {
        val repository = FakeShenavaRepository()
        val interactor = ShenavaOfflineSpeechInteractor(repository)

        try {
            interactor.startRecording(ShenavaModelVariant.INT8)
            throw AssertionError("Recording should require a downloaded model.")
        } catch (expected: IllegalStateException) {
            assertEquals("ابتدا مدل شنوا را دانلود کن.", expected.message)
        }
        assertFalse(repository.recording)
    }

    @Test
    fun passesDownloadProgressAndStartsTheSelectedOfflineModel() {
        val repository = FakeShenavaRepository()
        val interactor = ShenavaOfflineSpeechInteractor(repository)
        val progress = mutableListOf<Int>()

        interactor.downloadModel(ShenavaModelVariant.FP32) { progress += it }
        interactor.startRecording(ShenavaModelVariant.FP32)

        assertEquals(listOf(0, 40, 100), progress)
        assertEquals(ShenavaModelVariant.FP32, repository.downloadedVariant)
        assertTrue(repository.recording)
    }

    @Test
    fun stopTranscribesAndTrimsResultAndCancelIsForwarded() {
        val repository = FakeShenavaRepository()
        val interactor = ShenavaOfflineSpeechInteractor(repository)
        interactor.downloadModel(ShenavaModelVariant.INT8) {}
        interactor.startRecording(ShenavaModelVariant.INT8)

        assertEquals("سلام کیرا", interactor.stopRecordingAndTranscribe(ShenavaModelVariant.INT8))
        assertFalse(repository.recording)
        assertEquals(1, repository.transcriptionCalls)

        interactor.startRecording(ShenavaModelVariant.INT8)
        interactor.cancelRecording()
        assertFalse(repository.recording)
        assertEquals(1, repository.cancelCalls)
    }

    private class FakeShenavaRepository : ShenavaOfflineSpeechRepository {
        var downloadedVariant: ShenavaModelVariant? = null
        var recording = false
        var transcriptionCalls = 0
        var cancelCalls = 0

        override fun isModelDownloaded(variant: ShenavaModelVariant): Boolean = downloadedVariant == variant

        override fun downloadModel(
            variant: ShenavaModelVariant,
            onProgress: (percent: Int) -> Unit
        ) {
            downloadedVariant = variant
            onProgress(0)
            onProgress(40)
            onProgress(100)

        }

        override fun startRecording() {
            recording = true
        }

        override fun stopRecordingAndTranscribe(variant: ShenavaModelVariant): String {
            transcriptionCalls++
            recording = false
            return "  سلام کیرا  "
        }

        override fun cancelRecording() {
            cancelCalls++
            recording = false
        }
    }
}
