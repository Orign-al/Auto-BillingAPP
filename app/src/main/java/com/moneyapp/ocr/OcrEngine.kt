package com.moneyapp.ocr

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OcrEngine {
    private val chineseRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )
    private val latinRecognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    fun recognizeText(context: Context, uri: Uri, onResult: (OcrResult) -> Unit) {
        val image = InputImage.fromFilePath(context, uri)
        chineseRecognizer
            .process(image)
            .addOnSuccessListener { result ->
                val text = result.text.trim()
                if (text.isNotEmpty()) {
                    onResult(OcrResult(text, result))
                } else {
                    runLatin(context, image, onResult)
                }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Chinese OCR failed", error)
                runLatin(context, image, onResult)
            }
    }

    private fun runLatin(context: Context, image: InputImage, onResult: (OcrResult) -> Unit) {
        latinRecognizer
            .process(image)
            .addOnSuccessListener { result ->
                onResult(OcrResult(result.text.trim(), result))
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Latin OCR failed", error)
                onResult(OcrResult("", null))
            }
    }

    data class OcrResult(val text: String, val raw: Text?)

    companion object {
        private const val TAG = "OcrEngine"
    }
}
