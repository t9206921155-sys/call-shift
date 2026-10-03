package fi.callshift.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Каждый View в layout обязан иметь layout_width и layout_height (атрибутом или через свой style).
 * Иначе экран падает при открытии (так падал экран добавления правила в 0.2.x).
 */
class LayoutSanityTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun resDir(): File =
        listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }

    private fun factory() = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }

    private fun styleItems(res: File): Map<String, Set<String>> {
        val out = mutableMapOf<String, Set<String>>()
        res.listFiles { f -> f.name.startsWith("values") }.orEmpty()
            .flatMap { it.listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
            .forEach { f ->
                val styles = factory().newDocumentBuilder().parse(f).getElementsByTagName("style")
                for (i in 0 until styles.length) {
                    val s = styles.item(i) as Element
                    val items = s.getElementsByTagName("item")
                    out[s.getAttribute("name")] =
                        (0 until items.length).map { (items.item(it) as Element).getAttribute("name") }.toSet()
                }
            }
        return out
    }

    @Test
    fun everyViewHasWidthAndHeight() {
        val res = resDir()
        val styles = styleItems(res)
        val problems = mutableListOf<String>()
        res.listFiles { f -> f.name.startsWith("layout") }.orEmpty()
            .flatMap { it.listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
            .forEach { f ->
                val all = factory().newDocumentBuilder().parse(f).getElementsByTagName("*")
                for (i in 0 until all.length) {
                    val el = all.item(i) as Element
                    if (el.tagName in setOf("include", "merge", "requestFocus", "tag")) continue
                    val style = styles[el.getAttribute("style").removePrefix("@style/")].orEmpty()
                    for (attr in listOf("layout_width", "layout_height")) {
                        if (!el.hasAttributeNS(android, attr) && "android:$attr" !in style) {
                            problems += "${f.name}: <${el.tagName}> ${el.getAttributeNS(android, "id")} без $attr"
                        }
                    }
                }
            }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
