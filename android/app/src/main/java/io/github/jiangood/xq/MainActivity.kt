package io.github.jiangood.xq

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import io.github.jiangood.xq.ui.MainScreen
import io.github.jiangood.xq.viewmodel.AnalysisViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AnalysisViewModel by viewModels()

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            viewModel.analyze(this, uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.initOpenCV(this)
        viewModel.initEngine(this)
        viewModel.initRecognizer(this)

        setContent {
            MainScreen(
                viewModel = viewModel,
                onPickImage = {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            )
        }
    }
}
