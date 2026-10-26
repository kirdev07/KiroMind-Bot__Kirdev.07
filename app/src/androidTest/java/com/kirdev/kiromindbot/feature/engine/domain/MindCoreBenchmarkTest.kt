package com.kirdev.kiromindbot.feature.engine.domain

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Spec §11 on a real device: index build time, query latency and index memory for a
 * synthetic Russian base. Results go to logcat (tag MindCoreBench) and instrumentation status.
 */
@RunWith(AndroidJUnit4::class)
class MindCoreBenchmarkTest {

    private val words = ("привет пока кот собака погода машина музыка работа город книга фильм игра робот поезд море лес " +
        "чай кофе школа друг время деньги телефон компьютер интернет новости футбол праздник подарок ужин завтрак " +
        "отпуск лето зима весна осень утро вечер ночь день неделя месяц магазин доставка заказ оплата скидка цена").split(' ')

    private fun base(size: Int, rnd: Random) = (1..size).map { i ->
        val q = (1..(2 + rnd.nextInt(4))).joinToString(" ") { words[rnd.nextInt(words.size)] } + " $i"
        AnswerEntry(i.toLong(), q, "ответ $i")
    }

    private fun typo(s: String, rnd: Random): String {
        val w = s.split(' ').toMutableList()
        val k = rnd.nextInt(w.size - 1)
        if (w[k].length > 4) w[k] = w[k].removeRange(1, 2)
        return w.joinToString(" ")
    }

    private fun usedMb(): Double {
        val rt = Runtime.getRuntime()
        repeat(3) { System.gc(); Thread.sleep(100) }
        return (rt.totalMemory() - rt.freeMemory()) / 1_048_576.0
    }

    private fun report(key: String, value: String) {
        Log.i("MindCoreBench", "$key = $value")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString(key, value) })
    }

    private fun run(size: Int) {
        val rnd = Random(size)
        val entries = base(size, rnd)
        val engine = FuzzyMindCore()

        val before = usedMb()
        val t0 = System.nanoTime()
        val index = engine.indexFor(entries)
        val buildMs = (System.nanoTime() - t0) / 1e6
        val indexMb = usedMb() - before

        val queries = (0 until 300).map { i ->
            val e = entries[rnd.nextInt(size)]
            when (i % 3) {
                0 -> e.question                      // exact
                1 -> typo(e.question, rnd)           // fuzzy
                else -> "совершенно неизвестная фраза $i"  // not found
            }
        }
        repeat(30) { engine.resolve(queries[it], entries) } // warm-up (JIT)
        val times = queries.map { q -> val s = System.nanoTime(); engine.resolve(q, entries); (System.nanoTime() - s) / 1e6 }.sorted()
        val avg = times.average()
        val p95 = times[(times.size * 0.95).toInt()]
        val max = times.last()

        report("n$size.build_ms", "%.0f".format(buildMs))
        report("n$size.index_mb", "%.1f".format(indexMb))
        report("n$size.query_avg_ms", "%.2f".format(avg))
        report("n$size.query_p95_ms", "%.2f".format(p95))
        report("n$size.query_max_ms", "%.2f".format(max))
        report("n$size.items", "${index.items.size}")

        // Generous ceilings for a mid-range 2021 phone; the real numbers are in the report.
        assertTrue("p95 $p95 ms", p95 < 50)
        assertTrue("build $buildMs ms", buildMs < 5_000)
    }

    @Test fun base15k() = run(15_000)

    @Test fun base30k() = run(30_000)
}
