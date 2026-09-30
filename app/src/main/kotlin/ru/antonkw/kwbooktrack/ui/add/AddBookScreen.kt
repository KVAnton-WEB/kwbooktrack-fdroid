package ru.antonkw.kwbooktrack.ui.add

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.core.content.ContextCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import ru.antonkw.kwbooktrack.R
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBookScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddBookViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val authorSuggestions by viewModel.authorSuggestions.collectAsStateWithLifecycle()
    val genreSuggestions by viewModel.genreSuggestions.collectAsStateWithLifecycle()
    val publisherSuggestions by viewModel.publisherSuggestions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity

    var showDiscardDialog by remember { mutableStateOf(false) }
    var pendingBack by remember { mutableStateOf(false) }

    // A new book with even a single keystroke in title/author/etc. is worth
    // prompting for — silently dropping user input on back was the old behaviour.
    val hasUserInput = remember(state.title, state.author, state.isbn, state.pageCount,
        state.year, state.publisher, state.genre, state.purchaseDate, state.purchasePrice) {
        state.title.isNotBlank() || state.author.isNotBlank() || state.isbn.isNotBlank() ||
            state.pageCount.isNotBlank() || state.year.isNotBlank() ||
            state.publisher.isNotBlank() || state.genre.isNotBlank() ||
            state.purchaseDate.isNotBlank() || state.purchasePrice.isNotBlank() ||
            state.coverPath != null || state.rating != null
    }
    val handleBack = {
        if (viewModel.isDirty && (viewModel.isEditing || hasUserInput)) {
            showDiscardDialog = true
        } else {
            onBack()
        }
    }
    BackHandler(enabled = true) { handleBack() }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val isbn = result?.contents?.filter { it.isDigit() }
        if (!isbn.isNullOrBlank()) {
            viewModel.setIsbn(isbn.take(13))
        }
    }

    val scanPrompt = stringResource(R.string.scan_prompt)
    val coverPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) viewModel.attachCover(uri) }

    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = cameraTempUri
        if (success && uri != null) viewModel.attachCover(uri)
        cameraTempUri = null
    }
    val pickCover: () -> Unit = {
        coverPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val uri = viewModel.newCameraTempUri()
            cameraTempUri = uri
            cameraLauncher.launch(uri)
        } else {
            Toast.makeText(context, context.getString(R.string.error_no_camera), Toast.LENGTH_SHORT).show()
        }
    }
    val takePhoto: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val uri = viewModel.newCameraTempUri()
            cameraTempUri = uri
            cameraLauncher.launch(uri)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.dialog_unsaved_title)) },
            text = { Text(stringResource(R.string.dialog_unsaved_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    viewModel.save()
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDiscardDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = {
                        showDiscardDialog = false
                        onBack()
                    }) { Text(stringResource(R.string.action_discard)) }
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (viewModel.isEditing) R.string.edit_book_title else R.string.add_book_title)) },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CoverPreview(
                coverPath = state.coverPath,
                onPick = pickCover,
                onCamera = takePhoto,
                onClear = { viewModel.clearCover() },
            )

            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::setTitle,
                label = { Text(stringResource(R.string.label_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SuggestionTextField(
                value = state.author,
                onValueChange = viewModel::setAuthor,
                suggestions = authorSuggestions,
                label = stringResource(R.string.label_author),
            )

            OutlinedTextField(
                value = state.isbn,
                onValueChange = viewModel::setIsbn,
                label = { Text("ISBN-13") },
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            activity?.let {
                                val options = ScanOptions().apply {
                                    setDesiredBarcodeFormats(ScanOptions.PRODUCT_CODE_TYPES)
                                    setPrompt(scanPrompt)
                                    setBeepEnabled(false)
                                }
                                scanLauncher.launch(options)
                            }
                        },
                    ) {
                        Icon(
                            Icons.Filled.QrCodeScanner,
                            contentDescription = stringResource(R.string.cd_scan_isbn),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.pageCount,
                    onValueChange = viewModel::setPageCount,
                    label = { Text(stringResource(R.string.label_page_count)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.year,
                    onValueChange = viewModel::setYear,
                    label = { Text(stringResource(R.string.label_year)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            SuggestionTextField(
                value = state.publisher,
                onValueChange = viewModel::setPublisher,
                suggestions = publisherSuggestions,
                label = stringResource(R.string.label_publisher),
            )
            SuggestionTextField(
                value = state.genre,
                onValueChange = viewModel::setGenre,
                suggestions = genreSuggestions,
                label = stringResource(R.string.label_genre),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.purchaseDate,
                    onValueChange = viewModel::setPurchaseDate,
                    label = { Text(stringResource(R.string.label_purchase_date)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.purchasePrice,
                    onValueChange = viewModel::setPurchasePrice,
                    label = { Text(stringResource(R.string.label_purchase_price)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.label_meta_rating) + ":", style = MaterialTheme.typography.bodyMedium)
                (1..5).forEach { star ->
                    IconButton(onClick = {
                        viewModel.setRating(if (state.rating == star) null else star)
                    }) {
                        if ((state.rating ?: 0) >= star) {
                            Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        } else {
                            Icon(Icons.Outlined.Star, contentDescription = null)
                        }
                    }
                }
            }

            // v5: extra fields in a single Card so the form doesn't grow taller
            // than the screen. Most users won't fill these; power users can.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = state.subtitle,
                        onValueChange = viewModel::setSubtitle,
                        label = { Text(stringResource(R.string.field_subtitle)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.originalTitle,
                        onValueChange = viewModel::setOriginalTitle,
                        label = { Text(stringResource(R.string.field_original_title)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = state.series,
                            onValueChange = viewModel::setSeries,
                            label = { Text(stringResource(R.string.field_series)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = state.seriesNumber,
                            onValueChange = viewModel::setSeriesNumber,
                            label = { Text(stringResource(R.string.field_series_number)) },
                            singleLine = true,
                            modifier = Modifier.weight(0.5f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = state.binding,
                            onValueChange = viewModel::setBinding,
                            label = { Text(stringResource(R.string.field_binding)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = state.condition,
                            onValueChange = viewModel::setCondition,
                            label = { Text(stringResource(R.string.field_condition)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = state.translator,
                            onValueChange = viewModel::setTranslator,
                            label = { Text(stringResource(R.string.field_translator)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = state.originalLanguage,
                            onValueChange = viewModel::setOriginalLanguage,
                            label = { Text(stringResource(R.string.field_original_language)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = state.printYear,
                            onValueChange = viewModel::setPrintYear,
                            label = { Text(stringResource(R.string.field_print_year)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = state.printPages,
                            onValueChange = viewModel::setPrintPages,
                            label = { Text(stringResource(R.string.field_print_pages)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = state.purchasePlace,
                        onValueChange = viewModel::setPurchasePlace,
                        label = { Text(stringResource(R.string.field_purchase_place)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.annotation,
                        onValueChange = viewModel::setAnnotation,
                        label = { Text(stringResource(R.string.field_annotation)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        CheckboxWithLabel(
                            checked = state.isGift,
                            onCheckedChange = viewModel::setIsGift,
                            label = stringResource(R.string.field_is_gift),
                        )
                        CheckboxWithLabel(
                            checked = state.isLent,
                            onCheckedChange = viewModel::setIsLent,
                            label = stringResource(R.string.field_is_lent),
                        )
                        CheckboxWithLabel(
                            checked = state.isSold,
                            onCheckedChange = viewModel::setIsSold,
                            label = stringResource(R.string.field_is_sold),
                        )
                    }
                }
            }

            Button(
                onClick = { viewModel.save() },
                enabled = state.title.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (viewModel.isEditing) R.string.action_save_changes else R.string.action_save))
            }
        }
    }
}

@Composable
private fun CheckboxWithLabel(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuggestionTextField(
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
    label: String,
) {
    val trimmed = value.trim()
    var isFocused by remember { mutableStateOf(false) }
    val filtered = remember(isFocused, value, suggestions) {
        if (!isFocused || trimmed.isBlank()) emptyList()
        else suggestions.filter { it.lowercase().contains(trimmed.lowercase()) }.take(6)
    }
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused },
        )
        if (filtered.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Column {
                    filtered.forEach { suggestion ->
                        Text(
                            text = suggestion,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onValueChange(suggestion) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverPreview(
    coverPath: String?,
    onPick: () -> Unit,
    onCamera: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 144.dp)
                .clickable(onClick = onPick),
            contentAlignment = Alignment.Center,
        ) {
            if (!coverPath.isNullOrBlank()) {
                AsyncImage(
                    model = File(coverPath),
                    contentDescription = stringResource(R.string.label_cover),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.AddPhotoAlternate,
                            contentDescription = stringResource(R.string.action_pick_cover),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = onPick) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
                Text("  " + stringResource(R.string.action_pick_cover))
            }
            OutlinedButton(onClick = onCamera) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                Text("  " + stringResource(R.string.action_take_photo))
            }
            if (!coverPath.isNullOrBlank()) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.action_clear_cover)) }
            }
        }
    }
}
