package ru.antonkw.kwbooktrack.data.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HtmlToMarkdownTest {

    @Test
    fun plainTextIsUnchanged() {
        val text = "Обычная аннотация без разметки."
        assertThat(HtmlToMarkdown.convert(text)).isEqualTo(text)
    }

    @Test
    fun alreadyMarkdownIsUnchanged() {
        val text = "**Жирный** и *курсив*, [ссылка](http://a.b)"
        assertThat(HtmlToMarkdown.convert(text)).isEqualTo(text)
    }

    @Test
    fun boldAndItalicBecomeMarkdown() {
        assertThat(HtmlToMarkdown.convert("<b>жирно</b> и <i>курсивно</i>"))
            .isEqualTo("**жирно** и *курсивно*")
        assertThat(HtmlToMarkdown.convert("<strong>a</strong><em>b</em>"))
            .isEqualTo("**a***b*")
    }

    @Test
    fun linksKeepTheirTarget() {
        assertThat(HtmlToMarkdown.convert("""См. <a href="http://example.com/x">здесь</a>."""))
            .isEqualTo("См. [здесь](http://example.com/x).")
    }

    @Test
    fun anchorsWithoutHrefAreJustStripped() {
        assertThat(HtmlToMarkdown.convert("<a name=\"top\">текст</a>")).isEqualTo("текст")
    }

    @Test
    fun blockTagsBecomeBlankLines() {
        assertThat(HtmlToMarkdown.convert("<p>первый</p><p>второй</p>"))
            .isEqualTo("первый\n\nвторой")
    }

    @Test
    fun brBecomesSingleNewline() {
        assertThat(HtmlToMarkdown.convert("раз<br/>два<br>три")).isEqualTo("раз\nдва\nтри")
    }

    @Test
    fun listItemsBecomeMarkdownList() {
        assertThat(HtmlToMarkdown.convert("<ul><li>раз</li><li>два</li></ul>"))
            .isEqualTo("- раз\n- два")
    }

    @Test
    fun entitiesAreDecoded() {
        assertThat(HtmlToMarkdown.convert("&laquo;Цитата&raquo; &mdash; &amp; &hellip;"))
            .isEqualTo("«Цитата» — & …")
    }

    @Test
    fun nonBreakingSpaceBecomesPlainSpace() {
        assertThat(HtmlToMarkdown.convert("раз&nbsp;два")).isEqualTo("раз два")
    }

    @Test
    fun escapedTagsStayLiteral() {
        // &lt;b&gt; — это текст про тег, а не жирный. Порядок проходов важен:
        // сущности раскрываются после снятия тегов.
        assertThat(HtmlToMarkdown.convert("&lt;b&gt;не жирный&lt;/b&gt;"))
            .isEqualTo("<b>не жирный</b>")
    }

    @Test
    fun numericEntitiesAreDecoded() {
        assertThat(HtmlToMarkdown.convert("&#1055;&#1088;&#1080;&#1074;&#1077;&#1090;"))
            .isEqualTo("Привет")
        assertThat(HtmlToMarkdown.convert("&#x41;")).isEqualTo("A")
    }

    @Test
    fun entitiesAreDecodedOnlyOnce() {
        // `&amp;lt;` — это текст про «&lt;», а не готовый «<»: сущности
        // раскрываются одним проходом, как у любого HTML-парсера. Раньше
        // `&amp;` раскрывался раньше `&lt;`, и текст превращался в разметку.
        assertThat(HtmlToMarkdown.convert("&amp;lt;script&amp;gt;")).isEqualTo("&lt;script&gt;")
        assertThat(HtmlToMarkdown.convert("&amp;nbsp;")).isEqualTo("&nbsp;")
    }

    @Test
    fun surrogatePairEntitiesBecomeWholeCharacters() {
        // Эмодзи — пара UTF-16: одиночный `toChar()` давал битый суррогат.
        assertThat(HtmlToMarkdown.convert("&#128512;")).isEqualTo("😀")
    }

    @Test
    fun unknownTagsAreStripped() {
        assertThat(HtmlToMarkdown.convert("""<font size="3" color="red">текст</font><span class="x">ещё</span>"""))
            .isEqualTo("текстещё")
    }

    @Test
    fun excessiveBlankLinesAreCollapsed() {
        assertThat(HtmlToMarkdown.convert("<p>a</p><br><br><br><p>b</p>")).isEqualTo("a\n\nb")
    }
}