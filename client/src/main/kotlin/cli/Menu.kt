package logfeline.client.cli

import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.rendering.TextStyles.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*


data class SingleChoiceMenuState<T>(
    val choices: List<T>,
    val header: (suspend Terminal.() -> Unit)? = null,
)

suspend fun <T> Terminal.singleChoiceMenu(
    choices: Flow<SingleChoiceMenuState<T>>,
    key: (T) -> String = { it.hashCode().toString() },
    hide: (T) -> Boolean = { false },
    label: LabelScope.(T) -> String,
    searchText: (T) -> String,
    maxSearchResults: Int = 10,
): T = coroutineScope outerScope@{
    val cursorStyle = Colors.pink + bold
    
    val selectedKeyFlow = MutableStateFlow<String?>(null)
    var allChoices = emptyList<T>()
    var currentChoices = emptyList<T>()
    val queryFlow = MutableStateFlow<String?>(null)
    val searchResults = MutableStateFlow(emptyList<String>())

    var headerJob: Job? = null
    
    val renderJob = combine(
        choices,
        selectedKeyFlow,
        queryFlow,
        searchResults,
    ) { (choices, header), selectedKey, query, searchResults ->
        headerJob?.cancelAndJoin()

        val permanentChoices = choices.filter { !hide(it) }
        val searchChoices = searchResults.mapNotNull { key -> choices.find { choice -> key(choice) == key }?.takeIf(hide) }
        val actualChoices = permanentChoices + searchChoices
        allChoices = choices
        currentChoices = actualChoices

        // Move back to start and clear the screen, but leave space for the header
        cursor.move {
            startOfLine()
            clearScreenAfterCursor()
        }
        println()

        // Render the choices
        val labelScope = LabelScope(query)
        actualChoices.forEach { choice ->
            val isSelected = key(choice) == selectedKey
            print(if (isSelected) cursorStyle(">>> ") else "    ")
            println(labelScope.label(choice))
        }

        // Render the query
        if (query != null) {
            print("Search: " + underline(("$query▏").padEnd(50)))
            cursor.move { startOfLine() }
        }

        // Go back to the header line and let the header job do it's thing
        cursor.move { up(actualChoices.size + (if (header != null) 1 else 0)) }
        headerJob = header?.let { this@outerScope.launch { it() } }
    }.flowOn(Dispatchers.IO).launchIn(this)

    fun updateSearch() {
        val query = queryFlow.value ?: return
        searchResults.value = allChoices
            .map { choice -> choice to computeSearchScore(searchText(choice), query.lowercase().split(' ').filter { it.isNotEmpty() }) }
            .filter { (_, score) -> score != 0 }
            .sortedByDescending { (_, score) -> score }
            .take(maxSearchResults)
            .map { (choice) -> key(choice) }
        selectedKeyFlow.value = searchResults.value.firstOrNull()
    }
    
    withContext(Dispatchers.IO) {
        while (true) {
            ensureActive()
            when (val char = Char(System.`in`.read())) {
                ESCAPE -> {
                    val next = Char(System.`in`.read())
                    if (next != '[') continue
                    when (Char(System.`in`.read())) {
                        'A' -> currentChoices.let { choices ->
                            val selectedKey = selectedKeyFlow.value
                            val currentIndex = choices.indexOfFirst { key(it) == selectedKey }
                            val newIndex = when {
                                currentIndex < 0 -> choices.lastIndex
                                currentIndex == 0 -> currentIndex
                                else -> currentIndex - 1
                            }
                            selectedKeyFlow.value = key(choices[newIndex])
                        }
                        'B' -> currentChoices.let { choices ->
                            val selectedKey = selectedKeyFlow.value
                            val currentIndex = choices.indexOfFirst { key(it) == selectedKey }
                            val newIndex = when {
                                currentIndex < 0 -> 0
                                currentIndex == choices.lastIndex -> currentIndex
                                else -> currentIndex + 1
                            }
                            selectedKeyFlow.value = key(choices[newIndex])
                        }
                        else -> continue
                    }
                }
                '\n' -> {
                    currentChoices.singleOrNull()?.let { return@withContext it }
                    val selectedKey = selectedKeyFlow.value
                    return@withContext currentChoices.firstOrNull { key(it) == selectedKey } ?: continue
                }
                '\u007f' -> { queryFlow.value = queryFlow.value?.dropLast(1); updateSearch() }
                else -> { queryFlow.value = queryFlow.value.orEmpty() + char; updateSearch() }
            }
        }
        awaitCancellation()
    }.also {
        renderJob.cancelAndJoin()
        headerJob?.cancel()
        cursor.move { startOfLine(); clearScreenAfterCursor() }
    }
}


data class LabelScope(val query: String?) {
    fun String.searchable(style: TextStyle = TextStyle()): String {
        val words = query?.lowercase()?.split(' ')?.filter { it.isNotEmpty() } ?: return style(this)
        val ranges = mutableListOf<IntRange>()
        var currentRange: IntRange? = null

        for (i in indices) {
            if (currentRange != null && i !in currentRange) currentRange = null
            val suffix = substring(i).lowercase()
            words.forEach { word ->
                if (!suffix.startsWith(word)) return@forEach
                if (currentRange == null) {
                    currentRange = i ..< (i + word.length)
                    ranges.add(currentRange!!)
                } else {
                    currentRange = currentRange!!.first.. maxOf(currentRange!!.last, i + word.length - 1)
                    ranges[ranges.lastIndex] = currentRange!!
                }
            }
        }

        return buildString {
            var count = 0
            ranges.forEach { range ->
                if (count != range.first) {
                    append(style(this@searchable.substring(count, range.first)))
                    count = range.first
                }
                append((style + Colors.red)(this@searchable.substring(range)))
                count = range.last + 1
            }
            if (count != this@searchable.length) append(style(this@searchable.substring(count, this@searchable.length)))
        }
    }
}

//TODO: Maybe use a more sophisticated algorithm here? This seems to work fine tho idk
private fun computeSearchScore(searchText: String, query: List<String>): Int {
    val ranges = mutableListOf<IntRange>()
    var currentRange: IntRange? = null

    for (i in searchText.indices) {
        if (currentRange != null && i !in currentRange) currentRange = null
        val suffix = searchText.substring(i).lowercase()
        query.forEach { word ->
            if (!suffix.startsWith(word)) return@forEach
            if (currentRange == null) {
                currentRange = i ..< (i + word.length)
                ranges.add(currentRange!!)
            } else {
                currentRange = currentRange!!.first.. maxOf(currentRange!!.last, i + word.length - 1)
                ranges[ranges.lastIndex] = currentRange!!
            }
        }
    }

    return ranges.sumOf { it.last - it.first + 1 }
}


private const val ESCAPE = '\u001b'
