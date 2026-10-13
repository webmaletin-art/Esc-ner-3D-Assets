package app.universal3d.inspector.work

/**
 * Comprobación previa de memoria. El límite que importa es el heap de Java de la app (no el espacio en disco ni la RAM total):
 * un modelo necesita aproximadamente 2,5 veces el tamaño del archivo durante la importación.
 */
object MemoryGuard {
    private const val FACTOR = 2.5

    fun availableBytes(): Long {
        val rt = Runtime.getRuntime()
        return rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
    }

    fun check(fileBytes: Long) {
        if (fileBytes <= 0) return
        System.gc()
        val need = (fileBytes * FACTOR).toLong()
        val avail = availableBytes()
        if (need > avail) {
            throw IllegalStateException(
                "Memoria insuficiente para este modelo: el archivo pesa ${fileBytes / 1048576} MB y necesita unos ${need / 1048576} MB, " +
                    "pero la app dispone de ${avail / 1048576} MB (límite de memoria de Android por app, no el espacio de almacenamiento). " +
                    "Cerrá otras apps, activá el modo poca memoria en Ajustes o usá una versión más liviana del modelo.",
            )
        }
    }
}
