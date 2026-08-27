package com.agent.code.kanban

enum class KanbanColumn { BACKLOG, PLANNING, IN_PROGRESS, VERIFICATION, HUMAN_REVIEW, DONE }

data class TaskCard(val id: String, val title: String, val column: KanbanColumn)

class KanbanBoard {
    private val cards = mutableMapOf<String, TaskCard>()

    fun add(card: TaskCard) {
        cards[card.id] = card
    }

    fun get(id: String): TaskCard? = cards[id]

    fun move(id: String, to: KanbanColumn): TaskCard {
        val current = cards[id] ?: error("No card $id")
        require(allowed(current.column, to)) { "Illegal transition ${current.column} -> $to" }
        val updated = current.copy(column = to)
        cards[id] = updated
        return updated
    }

    fun all(): List<TaskCard> = cards.values.toList()

    private fun allowed(from: KanbanColumn, to: KanbanColumn): Boolean = when (from) {
        KanbanColumn.BACKLOG -> to == KanbanColumn.PLANNING
        KanbanColumn.PLANNING -> to == KanbanColumn.IN_PROGRESS
        KanbanColumn.IN_PROGRESS -> to == KanbanColumn.VERIFICATION
        KanbanColumn.VERIFICATION -> to == KanbanColumn.HUMAN_REVIEW || to == KanbanColumn.DONE || to == KanbanColumn.IN_PROGRESS
        KanbanColumn.HUMAN_REVIEW -> to == KanbanColumn.DONE || to == KanbanColumn.IN_PROGRESS
        KanbanColumn.DONE -> false
    }
}
