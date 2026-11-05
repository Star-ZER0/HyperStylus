package cc.star0.xposed.hyperstylus.ui.state

/** The public framework name identifies a connection; newer bind notifications replace its wrapper. */
internal class FrameworkConnections<T : Any> {
    private data class Binding<T>(val sequence: Long, val endpoint: T)
    private val connections = linkedMapOf<String, Binding<T>>()

    fun bind(name: String, endpoint: T, sequence: Long): Boolean {
        if ((connections[name]?.sequence ?: Long.MIN_VALUE) > sequence) return false
        connections[name] = Binding(sequence, endpoint)
        return true
    }
    fun remove(endpoint: T): Boolean = connections.entries.removeAll { it.value.endpoint === endpoint }
    fun snapshot(): List<Pair<String, T>> = connections.map { it.key to it.value.endpoint }
}
