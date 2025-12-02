package com.hippo.ehviewer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ui.LocalNavController

@Composable
fun AiSettingsScreen() {
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.settings_ai)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(
                    top = paddingValues.calculateTopPadding(),
                    start = 16.dp,
                    end = 16.dp,
                    bottom = paddingValues.calculateBottomPadding(),
                )
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState()),
        ) {
            var geminiBaseUrl by remember { mutableStateOf(Settings.aiGeminiBaseUrl.orEmpty()) }
            var geminiApiKey by remember { mutableStateOf(Settings.aiGeminiApiKey.orEmpty()) }
            var openAiBaseUrl by remember { mutableStateOf(Settings.aiOpenAiBaseUrl.orEmpty()) }
            var openAiApiKey by remember { mutableStateOf(Settings.aiOpenAiApiKey.orEmpty()) }
            var defaultModel by remember { mutableStateOf(Settings.aiDefaultModel.orEmpty()) }

            AiTextField(
                label = stringResource(id = R.string.settings_ai_gemini_base_url),
                placeholder = stringResource(id = R.string.settings_ai_gemini_base_url_placeholder),
                value = geminiBaseUrl,
                onValueChange = {
                    geminiBaseUrl = it
                    Settings.aiGeminiBaseUrl = it.ifBlank { null }
                },
                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Uri),
            )

            Spacer(modifier = Modifier.height(12.dp))

            AiTextField(
                label = stringResource(id = R.string.settings_ai_gemini_api_key),
                value = geminiApiKey,
                onValueChange = {
                    geminiApiKey = it
                    Settings.aiGeminiApiKey = it.ifBlank { null }
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Password),
            )

            Spacer(modifier = Modifier.height(12.dp))

            AiTextField(
                label = stringResource(id = R.string.settings_ai_openai_base_url),
                placeholder = stringResource(id = R.string.settings_ai_openai_base_url_placeholder),
                value = openAiBaseUrl,
                onValueChange = {
                    openAiBaseUrl = it
                    Settings.aiOpenAiBaseUrl = it.ifBlank { null }
                },
                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Uri),
            )

            Spacer(modifier = Modifier.height(12.dp))

            AiTextField(
                label = stringResource(id = R.string.settings_ai_openai_api_key),
                value = openAiApiKey,
                onValueChange = {
                    openAiApiKey = it
                    Settings.aiOpenAiApiKey = it.ifBlank { null }
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Password),
            )

            Spacer(modifier = Modifier.height(12.dp))

            SimpleMenuPreference(
                title = stringResource(id = R.string.settings_ai_format),
                entry = R.array.ai_api_format_entries,
                entryValueRes = R.array.ai_api_format_entry_values,
                value = Settings::aiApiFormat,
            )

            Spacer(modifier = Modifier.height(12.dp))

            AiTextField(
                label = stringResource(id = R.string.settings_ai_default_model),
                placeholder = stringResource(id = R.string.settings_ai_default_model_placeholder),
                value = defaultModel,
                onValueChange = {
                    defaultModel = it
                    Settings.aiDefaultModel = it.ifBlank { null }
                },
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AiTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label) },
        placeholder = placeholder?.let { { Text(text = it) } },
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        singleLine = true,
    )
}
