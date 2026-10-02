package com.qwen.tts.android

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.qwen.tts.android.data.AudioImportConverter
import com.qwen.tts.android.data.db.QwenDatabase
import com.qwen.tts.android.data.db.VoiceProfileEntity
import com.qwen.tts.android.ui.theme.QwenTtsTheme
import com.qwen.tts.studio.engine.QwenEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImportVoiceActivity : ComponentActivity() {
    private var voiceName by mutableStateOf("Estelle")
    private var selectedUri by mutableStateOf<Uri?>(null)
    private var selectedLabel by mutableStateOf("No audio selected")
    private var status by mutableStateOf(
        "Choose WAV, FLAC, MP3, M4A/AAC, OGG/Vorbis, Opus or WebM audio from your phone.",
    )
    private var busy by mutableStateOf(false)

    private val modelDir by lazy { File(filesDir, "qwen3-tts-models") }
    private val voiceDir by lazy { File(filesDir, "voices") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QwenTtsTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri != null) {
                            selectedUri = uri
                            selectedLabel = uri.lastPathSegment ?: "Selected audio"
                            status = "Ready. The file will be converted to 24 kHz mono WAV for Qwen."
                            runCatching {
                                contentResolver.takePersistableUriPermission(
                                    uri,
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Spacer(Modifier.height(20.dp))
                        Text("Import saved voice", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Select a saved reference voice. Common formats are decoded on-device, " +
                                "downmixed to mono and resampled to Qwen's 24 kHz reference format.",
                        )

                        OutlinedTextField(
                            value = voiceName,
                            onValueChange = { voiceName = it },
                            label = { Text("Voice name") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Button(
                            onClick = { picker.launch(arrayOf("audio/*")) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Choose saved audio")
                        }

                        Text(
                            "WAV / FLAC / MP3 / M4A-AAC / OGG-Vorbis / Opus / WebM",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(selectedLabel, style = MaterialTheme.typography.bodySmall)

                        Button(
                            onClick = { importSelectedVoice() },
                            enabled = selectedUri != null && !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Create voice profile")
                        }

                        if (busy) CircularProgressIndicator()
                        Text(status)
                    }
                }
            }
        }
    }

    private fun importSelectedVoice() {
        val uri = selectedUri ?: return
        val requestedName = voiceName.trim().ifBlank { "Imported Voice" }
        busy = true
        status = "Reading selected audio..."

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val talker = File(modelDir, "qwen-talker-0.6b-base-Q4_K_M.gguf")
                    val tokenizer = File(modelDir, "qwen-tokenizer-12hz-Q4_K_M.gguf")
                    require(talker.isFile && tokenizer.isFile) {
                        "Download the Q4_K_M model in the main app first."
                    }

                    val voiceId = "voice-${System.currentTimeMillis()}"
                    val targetDir = File(voiceDir, voiceId).apply { mkdirs() }
                    try {
                        val source = File(targetDir, "source-audio")
                        val reference = File(targetDir, "reference.wav")

                        contentResolver.openInputStream(uri)?.use { input ->
                            source.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Could not read the selected file")

                        status = "Decoding and converting audio to 24 kHz mono WAV..."
                        val conversion = AudioImportConverter.convertToReferenceWav(source, reference)
                        source.delete()

                        status = "Loading Qwen model on Vulkan/accelerated backend..."
                        val engine = QwenEngine()
                        try {
                            // AUTO probes Android accelerated backends first. In this S23 build
                            // Vulkan is compiled in, while CUDA is intentionally never requested.
                            engine.setBackendPreference(QwenEngine.BACKEND_AUTO)
                            engine.setCpuThreads(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
                            if (!engine.loadModels(modelDir.absolutePath, talker.name)) {
                                error(engine.getLastError() ?: "Model load failed")
                            }

                            status = "Extracting speaker embedding..."
                            val embedding = File(targetDir, "speaker.json")
                            if (!engine.extractSpeakerEmbedding(reference.absolutePath, embedding.absolutePath)) {
                                error(engine.getLastError() ?: "Speaker embedding extraction failed")
                            }

                            val dao = QwenDatabase.getDatabase(applicationContext).qwenDao()
                            dao.insertVoice(
                                VoiceProfileEntity(
                                    voiceId = voiceId,
                                    name = requestedName,
                                    referenceWavPath = reference.absolutePath,
                                    speakerEmbeddingPath = embedding.absolutePath,
                                    durationMillis = conversion.durationMillis,
                                ),
                            )

                            "$requestedName (${conversion.sourceMime}, ${conversion.sourceSampleRate} Hz → 24000 Hz mono)"
                        } finally {
                            engine.close()
                        }
                    } catch (error: Throwable) {
                        targetDir.deleteRecursively()
                        throw error
                    }
                }
            }

            result.fold(
                onSuccess = { details ->
                    busy = false
                    status = "Voice created: $details. Open the main app and select it from Voices."
                    selectedUri = null
                    selectedLabel = "No audio selected"
                },
                onFailure = { error ->
                    busy = false
                    status = (error.message ?: "Voice import failed") +
                        "\nSupported on the S23: WAV, FLAC, MP3, AAC/M4A, OGG/Vorbis, Opus and WebM audio."
                },
            )
        }
    }
}
