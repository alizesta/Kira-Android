package ai.kira.mobile.domain.model

/** Downloadable, on-device Persian Shenava model variants. */
enum class ShenavaModelVariant(
    val storageKey: String,
    val repositoryId: String,
    val modelFileName: String,
    val approximateSizeMb: Int,
    val minimumModelBytes: Long
) {
    INT8(
        storageKey = "int8",
        repositoryId = "SajjadMajdi/shenava-koochik-fa-int8",
        modelFileName = "model.int8.onnx",
        approximateSizeMb = 126,
        minimumModelBytes = 80L * 1024L * 1024L
    ),
    FP32(
        storageKey = "fp32",
        repositoryId = "Reza2kn/Shenava-Koochik-v1.0-sherpa-onnx",
        modelFileName = "model.onnx",
        approximateSizeMb = 459,
        minimumModelBytes = 320L * 1024L * 1024L
    );

    val displayName: String
        get() = if (this == INT8) "Shenava Koochik · INT8" else "Shenava Koochik · FP32"

    companion object {
        fun fromStorageKey(value: String?): ShenavaModelVariant =
            entries.firstOrNull { it.storageKey == value } ?: INT8
    }
}
