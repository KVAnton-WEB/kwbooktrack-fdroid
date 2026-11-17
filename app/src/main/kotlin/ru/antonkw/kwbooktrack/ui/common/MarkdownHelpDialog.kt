package ru.antonkw.kwbooktrack.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ru.antonkw.kwbooktrack.R

/**
 * Шпаргалка по Markdown: синтаксис в обратных кавычках (Markwon покажет его
 * моноширинным), рядом — что получится.
 */
@Composable
fun MarkdownHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.markdown_help_title)) },
        text = {
            MarkdownText(
                markdown = stringResource(R.string.markdown_help_body),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        },
    )
}
