package com.example.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.AppDatabase
import com.example.data.ScanHistory
import com.example.data.ScanRepository
import com.example.network.Content
import com.example.network.GenerateContentRequest
import com.example.network.GenerationConfig
import com.example.network.InlineData
import com.example.network.Part
import com.example.network.RetrofitClient
import com.example.network.ScannedFormulaResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

sealed interface UiState {
    object Idle : UiState
    object Loading : UiState
    data class Success(val result: ScannedFormulaResult) : UiState
    data class Error(val message: String) : UiState
}

class MathViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val repository = ScanRepository(database.scanDao())

    val scanHistory: StateFlow<List<ScanHistory>> = repository.allScans
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap: StateFlow<Bitmap?> = _originalBitmap.asStateFlow()

    private val _croppedBitmap = MutableStateFlow<Bitmap?>(null)
    val croppedBitmap: StateFlow<Bitmap?> = _croppedBitmap.asStateFlow()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Crop parameters
    private var lastCropRect: Rect? = null
    private var lastCanvasSize: Size? = null

    // State for the loaded image uri (for showing in UI)
    private val _selectedImageUri = MutableStateFlow<Uri?>(null)
    val selectedImageUri: StateFlow<Uri?> = _selectedImageUri.asStateFlow()

    // States for editable values
    val editedLatex = MutableStateFlow("")
    val editedPlainText = MutableStateFlow("")
    val editedWordMath = MutableStateFlow("")

    fun setImageUri(uri: Uri) {
        _selectedImageUri.value = uri
        _uiState.value = UiState.Idle
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = loadScaledBitmapFromUri(getApplication(), uri)
            _originalBitmap.value = bitmap
            _croppedBitmap.value = null // reset cropped until they crop or we set default
        }
    }

    fun updateCropBounds(rect: Rect, canvasSize: Size) {
        lastCropRect = rect
        lastCanvasSize = canvasSize
    }

    fun performCrop() {
        val original = _originalBitmap.value ?: return
        val rect = lastCropRect ?: return
        val size = lastCanvasSize ?: return

        viewModelScope.launch(Dispatchers.Default) {
            val cropped = cropBitmap(original, rect, size)
            _croppedBitmap.value = cropped
        }
    }

    fun analyzeImage() {
        // If they didn't explicitly crop, perform crop now with last known parameters
        performCrop()

        viewModelScope.launch {
            val targetBitmap = _croppedBitmap.value ?: _originalBitmap.value
            if (targetBitmap == null) {
                _uiState.value = UiState.Error("कृपया पहले एक इमेज लोड करें।")
                return@launch
            }

            _uiState.value = UiState.Loading

            try {
                val base64Image = withContext(Dispatchers.Default) {
                    val outputStream = ByteArrayOutputStream()
                    targetBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                    Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                }

                val prompt = """
                    You are a mathematical formula and general text OCR extraction expert.
                    Extract all mathematical formulas, equations, and text from this cropped image.
                    Since the user wants to copy-paste the output directly into Microsoft Word and edit it, provide your response strictly as a JSON object with three keys:
                    - 'latex': The math formulas in standard LaTeX notation (for example, \\sum_{i=1}^{n} i^2). Microsoft Word supports pasting LaTeX formulas natively in its Equation editor. Use double backslashes in JSON (\\) so it parses correctly.
                    - 'plainText': Any general explanations, description text, or OCR transcription of the text surrounding the formulas, formatted clearly in Hindi/English as written in the image.
                    - 'wordMath': A version of the mathematical formula optimized for pasting as standard UnicodeMath or simplified inline math (e.g. (a+b)^2 = a^2 + 2ab + b^2) which can be quickly pasted and edited in Microsoft Word.

                    Do not include any markdown formatting like ```json or ```. Return the raw JSON object only so it can be parsed directly.
                    If there are no math formulas, set 'latex' and 'wordMath' to empty strings and put all extracted text in 'plainText'.
                """.trimIndent()

                val request = GenerateContentRequest(
                    contents = listOf(
                        Content(
                            parts = listOf(
                                Part(text = prompt),
                                Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Image))
                            )
                        )
                    ),
                    generationConfig = GenerationConfig(
                        responseMimeType = "application/json",
                        temperature = 0.2f
                    )
                )

                val apiKey = BuildConfig.GEMINI_API_KEY
                if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                    _uiState.value = UiState.Error("Gemini API key is not configured! Please set GEMINI_API_KEY in the Secrets panel in AI Studio.")
                    return@launch
                }

                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.service.generateContent(apiKey, request)
                }

                val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (responseText == null) {
                    _uiState.value = UiState.Error("मॉडल से कोई प्रतिक्रिया प्राप्त नहीं हुई।")
                    return@launch
                }

                Log.d("MathViewModel", "Gemini response: $responseText")

                val result = withContext(Dispatchers.Default) {
                    RetrofitClient.responseAdapter.fromJson(responseText)
                }

                if (result != null) {
                    editedLatex.value = result.latex
                    editedPlainText.value = result.plainText
                    editedWordMath.value = result.wordMath

                    _uiState.value = UiState.Success(result)

                    // Save to history along with the image
                    val imagePath = saveBitmapToInternalStorage(targetBitmap)
                    repository.insert(
                        ScanHistory(
                            latexResult = result.latex,
                            textResult = result.plainText,
                            wordMathResult = result.wordMath,
                            imagePath = imagePath
                        )
                    )
                } else {
                    _uiState.value = UiState.Error("प्रतिक्रिया को JSON प्रारूप में पार्स करने में विफल।")
                }

            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = UiState.Error("त्रुटि: ${e.localizedMessage ?: "अज्ञात त्रुटि हुई"}")
            }
        }
    }

    fun loadFromHistory(scan: ScanHistory) {
        editedLatex.value = scan.latexResult
        editedPlainText.value = scan.textResult
        editedWordMath.value = scan.wordMathResult

        _uiState.value = UiState.Success(
            ScannedFormulaResult(
                latex = scan.latexResult,
                plainText = scan.textResult,
                wordMath = scan.wordMathResult
            )
        )

        // Load the saved crop image if exists
        viewModelScope.launch(Dispatchers.IO) {
            if (scan.imagePath != null) {
                val file = File(scan.imagePath)
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    _croppedBitmap.value = bitmap
                    _originalBitmap.value = bitmap
                    _selectedImageUri.value = null // clear URI since it's local file
                }
            }
        }
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteById(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearAll()
        }
    }

    private suspend fun saveBitmapToInternalStorage(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        try {
            val context = getApplication<Application>()
            val filename = "scan_${System.currentTimeMillis()}.jpg"
            val file = File(context.filesDir, filename)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun loadScaledBitmapFromUri(context: Context, uri: Uri, maxDimension: Int = 1200): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            var srcWidth = options.outWidth
            var srcHeight = options.outHeight
            
            var sampleSize = 1
            while (srcWidth / 2 >= maxDimension || srcHeight / 2 >= maxDimension) {
                srcWidth /= 2
                srcHeight /= 2
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
