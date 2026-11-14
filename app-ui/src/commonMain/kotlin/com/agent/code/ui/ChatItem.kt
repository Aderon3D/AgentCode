package com.agent.code.ui

/** One row of the conversation; built from the raw event log by [chatItems]. */
internal sealed interface ChatItem {
    data class User(val text: String) : ChatItem
    data class Agent(val text: String) : ChatItem
    data class Reasoning(val text: String) : ChatItem
    data class Tool(val name: String, val args: String, val ok: Boolean?, val output: String) : ChatItem
    data class Note(val text: String, val error: Boolean = false) : ChatItem
}

/**
 * Collapses the event log into transcript rows: protocol markers (step/iteration
 * counters) are dropped, a tool's start+finish merge into one row so the call
 * can be expanded instead of printed twice. Pure — unit-tested.
 */
internal fun chatItems(events: List<UiEvent>): List<ChatItem> {
    val out = mutableListOf<ChatItem>()
    for (e in events) {
        when (e) {
            is UiEvent.UserMessage -> out += ChatItem.User(e.text)
            is UiEvent.Message -> out += ChatItem.Agent(e.text)
            is UiEvent.Reasoning -> {
                val last = out.lastOrNull()
                if (last is ChatItem.Reasoning) out[out.lastIndex] = ChatItem.Reasoning(last.text + "\n" + e.text)
                else out += ChatItem.Reasoning(e.text)
            }
            is UiEvent.ToolStarted -> out += ChatItem.Tool(e.name, e.args, null, "")
            is UiEvent.ToolFinished -> {
                val last = out.lastOrNull()
                if (last is ChatItem.Tool && last.ok == null) {
                    out[out.lastIndex] = last.copy(
                        ok = e.success,
                        output = if (e.output.isNotBlank()) e.output else last.output,
                    )
                } else {
                    out += ChatItem.Tool(e.name, "", e.success, e.output)
                }
            }
            is UiEvent.Complete -> out += ChatItem.Note("done")
            is UiEvent.Failed -> out += ChatItem.Note(e.reason, error = true)
            is UiEvent.Thinking, is UiEvent.Iteration -> Unit
        }
    }
    return out
}
