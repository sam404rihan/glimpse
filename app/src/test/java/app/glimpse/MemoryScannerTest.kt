package app.glimpse

class MemoryScannerTest : ScannerContract() {
    override fun newStore() = MemoryIndexStore()
}
