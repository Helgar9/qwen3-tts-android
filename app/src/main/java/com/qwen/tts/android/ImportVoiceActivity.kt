package com.qwen.tts.android

import android.media.MediaMetadataRetriever
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
    private var selectedLabel by mutableStateOf("No WAV selected")
    private var status by mutableStateOf("Choose a saved WAV file from your phone.")
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
                            selectedLabel = uri.lastPathSegment ?: "Selected WAV"
                            status = "Ready to create a voice profile."
                            runCatching {
                                contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
                        Text("Select an IrodoriTTS WAV stored on this Galaxy. It will be copied into the app and converted into a reusable Qwen speaker embedding.")

                        OutlinedTextField(
                            value = voiceName,
                            onValueChange = { voiceName = it },
                            label = { Text("Voice name") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Button(
                            onClick = { picker.launch(arrayOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Choose saved WAV")
                        }

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
        status = "Preparing WAV..."

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
                    val reference = File(targetDir, "reference.wav")

                    contentResolver.openInputStream(uri)?.use { input ->
                        reference.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Could not read the selected file")

                    require(isPcmWav(reference)) {
                        "The selected file is not a supported PCM WAV. Export the IrodoriTTS reference as WAV and try again."
                    }

                    status = "Loading Qwen model on the fastest available backend..."
                    val engine = QwenEngine()
                    try {
                        engine.setBackendPreference(QwenEngine.BACKEND_GPU)
                        engine.setCpuThreads(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
                        if (!engine.loadModels(modelDir.absolutePath, talker.name)) {
                            error(engine.getLastError() ?: "Model load failed")
                        }

                        status = "Extracting speaker embedding..."
                        val embedding = File(targetDir, "speaker.json")
                        if (!engine.extractSpeakerEmbedding(reference.absolutePath, embedding.absolutePath)) {
                            error(engine.getLastError() ?: "Speaker embedding extraction failed")
                        }

                        val durationMillis = readDurationMillis(reference)
                        val dao = QwenDatabase.getDatabase(applicationContext).qwenDao()
                        dao.insertVoice(
                            VoiceProfileEntity(
                                voiceId = voiceId,
                                name = requestedName,
                                referenceWavPath = reference.absolutePath,
                                speakerEmbeddingPath = embedding.absolutePath,
                                durationMillis = durationMillis,
                            ),
                        )
                    } finally {
                        engine.close()
                    }

                    requestedName
                }
            }

            result.fold(
                onSuccess = { name ->
                    busy = false
                    status = "Voice '$name' created. Open the main app and select it from Voices."
                    selectedUri = null
                    selectedLabel = "No WAV selected"
                },
                onFailure = { error ->
                    busy = false
                    status = error.message ?: "Voice import failed"
                },
            )
        }
    }

    private fun isPcmWav(file: File): Boolean {
        if (!file.isFile || file.length() < 44L) return false
        val header = ByteArray(12)
        file.inputStream().use { input ->
            if (input.read(header) != header.size) return false
        }
        val riff = String(header, 0, 4, Charsets.US_ASCII)
        val wave = String(header, 8, 4, Charsets.US_ASCII)
        return riff == "RIFF" && wave == "WAVE"
    }

    private fun readDurationMillis(file: File): Long {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
    }
}