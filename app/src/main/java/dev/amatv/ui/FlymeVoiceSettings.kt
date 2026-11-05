package dev.amatv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.integration.voice.VoiceEngineSettings
import dev.amatv.integration.voice.VoiceModelCatalog
import dev.amatv.integration.voice.VoiceService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Which recognizer the mic uses: a model downloaded to this device, or the user's own cloud API
 * (Tencent Cloud sentence recognition, or any OpenAI-compatible transcription endpoint).
 */
@Composable
internal fun FlymeVoiceEngineSettings() {
    val context = LocalContext.current
    val voice = remember(context) { VoiceService.get(context.applicationContext) }
    val settings by voice.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val progress = remember { mutableStateMapOf<String, Float>() }
    val jobs = remember { mutableMapOf<String, Job>() }
    var installedNonce by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf<String?>(null) }

    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.voice_engine_title), color = FlymeInk, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.voice_engine_summary), color = FlymeInk.copy(alpha = 0.6f), fontSize = 14.sp)
    Spacer(Modifier.height(6.dp))
    installedNonce.let { _ ->
        VoiceModelCatalog.all.forEach { model ->
            val id = "model:${model.id}"
            val installed = voice.models.isInstalled(model)
            val downloading = progress[model.id]
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 3.dp)) {
                CacheButton((if (settings.engine == id) "✓ " else "") + model.name,
                    onClick = { voice.update { it.copy(engine = id) } }, modifier = Modifier.width(330.dp))
                Text(model.summary, color = FlymeInk.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.width(330.dp))
                when {
                    downloading != null -> CacheButton(stringResource(R.string.voice_model_downloading, (downloading * 100).toInt()),
                        onClick = {
                            jobs.remove(model.id)?.cancel()
                            progress.remove(model.id)
                        }, modifier = Modifier.width(170.dp))
                    installed -> CacheButton(stringResource(R.string.voice_model_delete), onClick = {
                        voice.models.delete(model)
                        installedNonce++
                    }, modifier = Modifier.width(170.dp))
                    else -> CacheButton(stringResource(R.string.voice_model_download, model.sizeMb), onClick = {
                        failed = null
                        progress[model.id] = 0f
                        jobs[model.id] = scope.launch {
                            val result = voice.models.download(model) { value -> progress[model.id] = value }
                            progress.remove(model.id)
                            jobs.remove(model.id)
                            if (result.isFailure) failed = model.name
                            installedNonce++
                        }
                    }, modifier = Modifier.width(170.dp))
                }
            }
        }
    }
    failed?.let {
        Text(stringResource(R.string.voice_model_download_failed, it), color = FlymeInk.copy(alpha = 0.7f), fontSize = 13.sp)
    }
    Spacer(Modifier.height(10.dp))
    CacheButton((if (settings.engine == "tencent") "✓ " else "") + stringResource(R.string.voice_engine_tencent),
        onClick = { voice.update { it.copy(engine = "tencent") } }, modifier = Modifier.width(330.dp))
    if (settings.engine == "tencent") {
        VoiceField(stringResource(R.string.voice_field_secret_id), settings.tencentSecretId, secret = false) { v ->
            voice.update { it.copy(tencentSecretId = v) }
        }
        VoiceField(stringResource(R.string.voice_field_secret_key), settings.tencentSecretKey, secret = true) { v ->
            voice.update { it.copy(tencentSecretKey = v) }
        }
    }
    Spacer(Modifier.height(8.dp))
    CacheButton((if (settings.engine == "openai") "✓ " else "") + stringResource(R.string.voice_engine_openai),
        onClick = { voice.update { it.copy(engine = "openai") } }, modifier = Modifier.width(330.dp))
    if (settings.engine == "openai") {
        Text(stringResource(R.string.voice_engine_openai_hint), color = FlymeInk.copy(alpha = 0.6f), fontSize = 13.sp)
        VoiceField(stringResource(R.string.voice_field_base_url), settings.openAiBaseUrl, secret = false) { v ->
            voice.update { it.copy(openAiBaseUrl = v.ifBlank { VoiceEngineSettings.DEFAULT_OPENAI_BASE }) }
        }
        VoiceField(stringResource(R.string.voice_field_api_key), settings.openAiKey, secret = true) { v ->
            voice.update { it.copy(openAiKey = v) }
        }
        VoiceField(stringResource(R.string.voice_field_model), settings.openAiModel, secret = false) { v ->
            voice.update { it.copy(openAiModel = v.ifBlank { VoiceEngineSettings.DEFAULT_OPENAI_MODEL }) }
        }
    }
    Text(stringResource(R.string.voice_cloud_privacy), color = FlymeInk.copy(alpha = 0.5f), fontSize = 12.sp,
        modifier = Modifier.padding(top = 6.dp))
}

/** A labelled single-line field; keys are masked. Saved as the user types. */
@Composable
private fun VoiceField(label: String, value: String, secret: Boolean, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 6.dp)) {
        Text(label, color = FlymeInk, fontSize = 15.sp, modifier = Modifier.width(150.dp))
        Box(Modifier.width(520.dp).clip(RoundedCornerShape(12.dp)).background(FlymeInk.copy(alpha = 0.1f))
            .padding(horizontal = 14.dp, vertical = 10.dp)) {
            BasicTextField(
                value = value,
                onValueChange = { onChange(it.trim()) },
                singleLine = true,
                textStyle = TextStyle(color = FlymeInk, fontSize = 15.sp),
                cursorBrush = SolidColor(FlymeInk),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            )
        }
    }
}
