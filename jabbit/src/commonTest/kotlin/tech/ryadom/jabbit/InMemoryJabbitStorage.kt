package tech.ryadom.jabbit

internal class InMemoryJabbitStorage : JabbitStorage {

    private var document: String? = null

    override suspend fun read(): String? = document

    override suspend fun write(value: String) {
        document = value
    }
}
