package com.sainadh.livenotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sainadh.livenotes.data.NoteCategory
import com.sainadh.livenotes.data.NoteOrganization
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.util.UUID

/** Searching does not mutate either the saved transcript or the user's summary. */
internal fun matchesNoteFilters(
    title: String,
    text: String,
    organization: NoteOrganization?,
    categoryName: String?,
    query: String,
    bookmarkedOnly: Boolean,
    categoryFilter: String?
): Boolean {
    if (bookmarkedOnly && organization?.isBookmarked != true) return false
    if (categoryFilter != null && (organization?.categoryId ?: "") != categoryFilter) return false
    val fields = listOf(title, text, organization?.userSummary.orEmpty(), categoryName.orEmpty())
    return query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        .all { term -> fields.any { it.contains(term, ignoreCase = true) } }
}

internal fun organizedNoteText(title: String, categoryName: String?, userSummary: String, originalHeading: String, originalText: String): String =
    buildList {
        add(title)
        if (!categoryName.isNullOrBlank()) add("Category: $categoryName")
        if (userSummary.isNotBlank()) add("My summary\n$userSummary")
        if (originalText.isNotBlank()) add("$originalHeading\n$originalText")
    }.joinToString("\n\n")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotesFilters(
    query: String, onQuery: (String) -> Unit,
    bookmarkedOnly: Boolean, onBookmarkedOnly: (Boolean) -> Unit,
    categoryFilter: String?, onCategoryFilter: (String?) -> Unit,
    categories: List<NoteCategory>, onManageCategories: () -> Unit
) {
    var categoryMenu by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, onQuery, modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); keyboard?.hide() }),
            label = { Text("Search notes") }, placeholder = { Text("Name, transcript, summary or category") })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = bookmarkedOnly, onClick = { onBookmarkedOnly(!bookmarkedOnly) },
                label = { Text("Bookmarked") }, modifier = Modifier.heightIn(min = 48.dp))
            Box {
                OutlinedButton(onClick = { categoryMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(when (categoryFilter) {
                        null -> "All categories"
                        "" -> "Uncategorized"
                        else -> categories.firstOrNull { it.id == categoryFilter }?.name ?: "All categories"
                    }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                    DropdownMenuItem(text = { Text("All categories") }, onClick = { onCategoryFilter(null); categoryMenu = false })
                    DropdownMenuItem(text = { Text("Uncategorized") }, onClick = { onCategoryFilter(""); categoryMenu = false })
                    categories.forEach { category ->
                        DropdownMenuItem(text = { Text(category.name) }, onClick = { onCategoryFilter(category.id); categoryMenu = false })
                    }
                }
            }
            TextButton(onClick = onManageCategories, modifier = Modifier.heightIn(min = 48.dp)) { Text("Manage categories") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteOrganizationActions(isBookmarked: Boolean, onBookmark: () -> Unit, onEdit: () -> Unit, enabled: Boolean = true) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBookmark, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).semantics {
            contentDescription = if (isBookmarked) "Remove bookmark" else "Bookmark note"
            stateDescription = if (isBookmarked) "Bookmarked" else "Not bookmarked"
        }) { Text(if (isBookmarked) "Bookmarked ✓" else "Bookmark") }
        TextButton(onClick = onEdit, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit details") }
    }
}

@Composable
fun NoteCategoryBadge(name: String?) {
    if (!name.isNullOrBlank()) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp)) {
            Text(name, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun UserSummary(text: String, expanded: Boolean = true) {
    if (text.isNotBlank()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("My summary", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

internal data class NoteEditDraft(
    val original: NoteOrganization,
    val defaultTitle: String,
    val title: String = original.title,
    val categoryId: String? = original.categoryId,
    val userSummary: String = original.userSummary
) {
    val changed: Boolean get() = title != original.title || categoryId != original.categoryId || userSummary != original.userSummary
}

/** Retains potentially long pasted text across Activity recreation without putting it in a Bundle. */
class NoteEditorState : ViewModel() {
    internal var draft by mutableStateOf<NoteEditDraft?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var confirmDiscard by mutableStateOf(false)
        private set

    fun open(noteKey: String, defaultTitle: String, organization: NoteOrganization?) {
        // A second open cannot overwrite an in-progress edit or a save.
        if (draft != null) return
        draft = NoteEditDraft(organization ?: NoteOrganization(noteKey), defaultTitle)
        error = null
    }

    internal fun update(value: NoteEditDraft) { if (!saving) { draft = value; error = null } }
    fun requestClose() { if (!saving) { if (draft?.changed == true) confirmDiscard = true else close() } }
    fun keepEditing() { confirmDiscard = false }
    fun close() { if (!saving) { draft = null; error = null; confirmDiscard = false } }

    fun save(action: suspend (String, String, String?, String) -> Result<Unit>) {
        val current = draft ?: return
        if (saving) return
        saving = true
        error = null
        viewModelScope.launch {
            try {
                action(current.original.noteKey, current.title, current.categoryId, current.userSummary)
                    .onSuccess { draft = null; confirmDiscard = false }
                    .onFailure { error = it.message ?: "Could not save this note. Please try again." }
            } finally {
                saving = false
            }
        }
    }
}

@Composable
fun NoteEditDialog(
    state: NoteEditorState, categories: List<NoteCategory>,
    onSave: suspend (String, String, String?, String) -> Result<Unit>,
    onCreateCategory: suspend (String) -> Result<NoteCategory>
) {
    val draft = state.draft ?: return
    var categoryMenu by remember { mutableStateOf(false) }
    var creatingCategory by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = state::requestClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        Surface(Modifier.fillMaxWidth(.94f).widthIn(max = 640.dp).imePadding(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Edit note details", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(draft.title, { state.update(draft.copy(title = it)) }, enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth(), label = { Text("Note name") }, singleLine = true,
                        placeholder = { Text(draft.defaultTitle) }, supportingText = { Text("Up to 120 characters. Leave blank for the original name.") })
                    Box {
                        OutlinedButton(onClick = { categoryMenu = true }, enabled = !state.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Category: " + (categories.firstOrNull { it.id == draft.categoryId }?.name
                                ?: if (draft.categoryId == null) "Uncategorized" else "Unavailable — choose again"))
                        }
                        DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                            DropdownMenuItem(text = { Text("Uncategorized") }, onClick = { state.update(draft.copy(categoryId = null)); categoryMenu = false })
                            categories.forEach { category ->
                                DropdownMenuItem(text = { Text(category.name) }, onClick = { state.update(draft.copy(categoryId = category.id)); categoryMenu = false })
                            }
                            DropdownMenuItem(text = { Text("+ New category") }, onClick = { creatingCategory = true; categoryMenu = false })
                        }
                    }
                    OutlinedTextField(draft.userSummary, { state.update(draft.copy(userSummary = it)) }, enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth(), label = { Text("My summary") }, minLines = 6, maxLines = 12,
                        placeholder = { Text("Write your notes or paste a summary from Gemini or another app.") },
                        supportingText = { Text("Saved separately from the transcript and automatic summary. Up to 100,000 characters.") })
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = state::requestClose, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                    Button(onClick = { focus.clearFocus(); keyboard?.hide(); state.save(onSave) }, enabled = !state.saving,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.saving) "Saving…" else "Save") }
                }
            }
        }
    }
    if (state.confirmDiscard) {
        AlertDialog(onDismissRequest = state::keepEditing, title = { Text("Discard changes?") },
            text = { Text("Your changes have not been saved.") },
            confirmButton = { TextButton(onClick = state::close) { Text("Discard") } },
            dismissButton = { TextButton(onClick = state::keepEditing) { Text("Keep editing") } })
    }
    if (creatingCategory) {
        CategoryNameDialog("New category", "", onDismiss = { creatingCategory = false }, onSave = { name ->
            onCreateCategory(name).map { category ->
                state.draft?.let { state.update(it.copy(categoryId = category.id)) }
            }
        })
    }
}

internal data class CategoryOperation(val id: String, val running: Boolean = false, val succeeded: Boolean = false, val error: String? = null)

/** A single Activity-scoped owner keeps category writes alive across dialog/Activity recreation. */
class CategoryOperationState : ViewModel() {
    internal var operation by mutableStateOf<CategoryOperation?>(null)
        private set

    fun run(id: String, action: suspend () -> Result<Unit>) {
        if (operation?.running == true || (operation?.id == id && operation?.succeeded == true)) return
        operation = CategoryOperation(id, running = true)
        viewModelScope.launch {
            val result = try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { Result.failure(error) }
            operation = result.fold(
                onSuccess = { CategoryOperation(id, succeeded = true) },
                onFailure = { CategoryOperation(id, error = it.message ?: "Could not update category. Please try again.") }
            )
        }
    }

    fun clear(id: String) {
        if (operation?.id == id && operation?.running != true) operation = null
    }
}

@Composable
private fun CategoryNameDialog(title: String, initialName: String, onDismiss: () -> Unit, onSave: suspend (String) -> Result<Unit>) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val operationId = rememberSaveable { UUID.randomUUID().toString() }
    val operations = androidx.lifecycle.viewmodel.compose.viewModel<CategoryOperationState>()
    val operation = operations.operation?.takeIf { it.id == operationId }
    val saving = operation?.running == true
    val latestDismiss by rememberUpdatedState(onDismiss)
    // Deliver completion to the current composition, never to a pre-rotation state closure.
    LaunchedEffect(operationId, operation?.succeeded) {
        if (operation?.succeeded == true) { latestDismiss(); operations.clear(operationId) }
    }
    fun dismiss() { operations.clear(operationId); onDismiss() }
    fun close() { if (!saving) { if (name != initialName) discard = true else dismiss() } }
    AlertDialog(onDismissRequest = ::close, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it; operations.clear(operationId) }, modifier = Modifier.fillMaxWidth(), enabled = !saving,
                label = { Text("Category name") }, singleLine = true, supportingText = { Text("Up to 60 characters.") })
            operation?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = !saving && name.isNotBlank(), onClick = {
            val savedName = name
            operations.run(operationId) { onSave(savedName) }
        }) { Text(if (saving) "Saving…" else "Save category") }
    }, dismissButton = { TextButton(onClick = ::close, enabled = !saving) { Text("Cancel") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard category changes?") },
        confirmButton = { TextButton(onClick = ::dismiss) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
}

@Composable
fun ManageCategoriesDialog(
    categories: List<NoteCategory>, onDismiss: () -> Unit,
    onCreate: suspend (String) -> Result<NoteCategory>,
    onRename: suspend (String, String) -> Result<Unit>,
    onDelete: suspend (String) -> Result<Unit>
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val operationId = rememberSaveable(deletingId) { UUID.randomUUID().toString() }
    val operations = androidx.lifecycle.viewmodel.compose.viewModel<CategoryOperationState>()
    val operation = operations.operation?.takeIf { it.id == operationId }
    val deleting = operation?.running == true
    LaunchedEffect(operationId, operation?.succeeded) {
        if (operation?.succeeded == true) { deletingId = null; operations.clear(operationId) }
    }
    AlertDialog(onDismissRequest = { if (!deleting) onDismiss() }, title = { Text("Categories") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Give your notes a home. Removing a category keeps all its notes.")
            if (categories.isEmpty()) Text("No categories yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            categories.forEach { category ->
                Column {
                    Text(category.name, style = MaterialTheme.typography.titleMedium)
                    Row {
                        TextButton(onClick = { renamingId = category.id }, enabled = !deleting, modifier = Modifier.heightIn(min = 48.dp)
                            .semantics { contentDescription = "Rename ${category.name}" }) { Text("Rename") }
                        TextButton(onClick = { deletingId = category.id }, enabled = !deleting, modifier = Modifier.heightIn(min = 48.dp)
                            .semantics { contentDescription = "Delete ${category.name}" }) { Text("Delete") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { creating = true }, enabled = !deleting) { Text("New category") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !deleting) { Text("Done") } })
    if (creating) CategoryNameDialog("New category", "", { creating = false }) { name -> onCreate(name).map { } }
    categories.firstOrNull { it.id == renamingId }?.let { category ->
        CategoryNameDialog("Rename category", category.name, { renamingId = null }) { name -> onRename(category.id, name) }
    }
    categories.firstOrNull { it.id == deletingId }?.let { category ->
        AlertDialog(onDismissRequest = { if (!deleting) deletingId = null }, title = { Text("Delete ${category.name}?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Its notes will become uncategorized. Your recordings and summaries are kept.")
                operation?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    operations.run(operationId) { onDelete(category.id) }
                }) { Text(if (deleting) "Deleting…" else "Delete category") }
            }, dismissButton = { TextButton(onClick = { deletingId = null }, enabled = !deleting) { Text("Cancel") } })
    }
}
