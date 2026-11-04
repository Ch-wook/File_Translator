package kr.documentstudio.core

/** Opening is a view operation unless a caller explicitly requests editing. */
enum class OpenIntent {
    VIEW, EDIT;
    companion object {
        fun parse(value: String?): OpenIntent = if(value == EDIT.name) EDIT else VIEW
    }
}

/** UI presentation is separate from a document's dirty/saved state. */
enum class PresentationState {
    LOADING, READING, EDITING, READ_ONLY, ERROR;
    val allowsMutation: Boolean get() = this == EDITING
}
