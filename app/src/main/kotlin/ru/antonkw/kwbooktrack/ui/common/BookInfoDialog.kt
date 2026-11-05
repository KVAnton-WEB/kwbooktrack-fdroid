package ru.antonkw.kwbooktrack.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.antonkw.kwbooktrack.R
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.text.formatDecimal
import ru.antonkw.kwbooktrack.data.time.toLocalDate

/**
 * Read-only "all details" dialog for a book. Each row is a label/value pair;
 * tapping a row copies its value to the system clipboard and shows a toast.
 *
 * Why read-only:
 *  - The same fields are editable in AddBookScreen. Showing them here as a
 *    quick lookup avoids forcing a navigation when the user just wants to
 *    read (or copy) a value like an ISBN.
 *  - The dialog is reachable from BookDetailScreen — when the user wants to
 *    see what's there without leaving the book view.
 */
@Composable
fun BookInfoDialog(
    book: BookEntity,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val rows = remember(book) { buildRows(book) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_info_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rows.forEach { row ->
                    InfoRow(
                        label = stringResource(row.labelRes),
                        value = row.value,
                        onCopy = {
                            copyToClipboard(context, row.value, row.labelRes)
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.book_info_dialog_close))
            }
        },
    )
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onCopy)
            .padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Top,
    ) {
        Column(modifier = Modifier.padding(end = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            )
        }
    }
}

private data class InfoRow(
    @androidx.annotation.StringRes val labelRes: Int,
    val value: String,
)

private fun buildRows(book: BookEntity): List<InfoRow> = buildList {
    // Order matters — the dialog scrolls, so put high-signal fields first.
    addAll(
        listOfNotNull(
            stringOrNull(R.string.field_original_title, book.originalTitle),
            stringOrNull(R.string.field_original_language, book.originalLanguage),
            stringOrNull(R.string.field_translator, book.translator),
            stringOrNull(R.string.field_series, book.series),
            stringOrNull(R.string.field_series_number, book.seriesNumber),
            stringOrNull(R.string.field_binding, book.binding),
            stringOrNull(R.string.field_condition, book.condition),
            stringOrNull(R.string.label_publisher, book.publisher),
            stringOrNull(R.string.label_genre, book.genre),
            stringOrNull(R.string.field_annotation, book.annotation),
            stringOrNull(R.string.field_purchase_place, book.purchasePlace),
            // Plain values — use a helper that drops blank/null so the dialog
            // only shows fields the user actually filled.
            book.year?.toString()?.let { InfoRow(R.string.label_year, it) },
            book.printYear?.toString()?.let { InfoRow(R.string.field_print_year, it) },
            book.pageCount?.toString()?.let { InfoRow(R.string.label_page_count, it) },
            book.printPages?.let { InfoRow(R.string.field_print_pages, formatDecimal(it)) },
            book.purchaseDate?.let { InfoRow(R.string.label_purchased_on, formatDate(it)) },
            book.purchasePrice?.let { InfoRow(R.string.label_price, "%.2f".format(it)) },
            book.startedAt?.let { InfoRow(R.string.label_started_on, formatDate(it)) },
            book.finishedAt?.let { InfoRow(R.string.label_finished_on, formatDate(it)) },
            InfoRow(R.string.field_is_gift, boolLabel(book.isGift)),
            InfoRow(R.string.field_is_lent, boolLabel(book.isLent)),
            InfoRow(R.string.field_is_sold, boolLabel(book.isSold)),
        ),
    )
}

private fun stringOrNull(@androidx.annotation.StringRes label: Int, value: String?): InfoRow? =
    value?.takeIf { it.isNotBlank() }?.let { InfoRow(label, it) }

private fun boolLabel(value: Boolean): String = if (value) "✓" else "—"

private fun formatDate(epochMs: Long): String = epochMs.toLocalDate().toString()

private fun copyToClipboard(context: Context, value: String, @androidx.annotation.StringRes labelRes: Int) {
    val label = context.getString(labelRes)
    val clip = ClipData.newPlainText(label, value)
    context.getSystemService(Context.CLIPBOARD_SERVICE)?.let { service ->
        (service as ClipboardManager).setPrimaryClip(clip)
    }
    Toast.makeText(
        context,
        context.getString(R.string.book_info_copied, value),
        Toast.LENGTH_SHORT,
    ).show()
}
