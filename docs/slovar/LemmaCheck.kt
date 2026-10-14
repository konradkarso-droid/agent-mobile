import com.uroboros.memory.nav.LemmaDictionary
import java.io.File

// Сверка словаря на Котлине с эталоном pymorphy3: все формы — те же разборы;
// испорченные формы, которых нет в эталоне, — пустой ответ. Плюс память и время.
fun main(args: Array<String>) {
    val rt = Runtime.getRuntime()
    fun used(): Long { repeat(3) { System.gc(); Thread.sleep(100) }; return rt.totalMemory() - rt.freeMemory() }
    val before = used()
    val t0 = System.nanoTime()
    val d = File(args[0]).bufferedReader(Charsets.UTF_8).useLines { LemmaDictionary.read(it) }
    val loadMs = (System.nanoTime() - t0) / 1_000_000
    val after = used()
    println("загрузка $loadMs мс, лексем ${d.lexemeCount}, образцов ${d.patternCount}, массивы ${d.arrayBytes} байт, куча +${after - before} байт")

    val known = HashSet<String>()
    var n = 0; var bad = 0
    val t1 = System.nanoTime()
    File(args[1]).bufferedReader(Charsets.UTF_8).useLines { lines ->
        for (line in lines) {
            val tab = line.indexOf('\t')
            val w = line.substring(0, tab)
            val want = line.substring(tab + 1).split(';').toSortedSet()
            val got = d.analyze(w).map { it.lemma + "|" + it.tags.sorted().joinToString(",") }.toSortedSet()
            if (got != want) { bad++; if (bad <= 10) println("РАСХОЖДЕНИЕ $w: ждали $want, вышло $got") }
            n++
            known += w
        }
    }
    val perWordUs = (System.nanoTime() - t1) / 1000.0 / n
    println("форм $n, расхождений $bad, на форму ${"%.2f".format(perWordUs)} мкс (с чтением файла)")

    // Испорченные слова: лишняя буква в конце, выпавшая буква в середине.
    var tried = 0; var guessed = 0
    val letters = "абвгдежзийклмнопрстуфхцчшщъыьэюя"
    var i = 0
    for (w in known) {
        i++
        if (i % 3 != 0) continue
        val a = w + letters[i % letters.length]
        val b = if (w.length > 3) w.removeRange(w.length / 2, w.length / 2 + 1) else null
        for (x in listOfNotNull(a, b)) {
            if (x in known) continue
            tried++
            if (d.analyze(x).isNotEmpty()) { guessed++; if (guessed <= 5) println("ДОГАДКА: $x -> ${d.analyze(x)}") }
        }
    }
    println("не слов проверено $tried, разобрано (догадок) $guessed")
}
