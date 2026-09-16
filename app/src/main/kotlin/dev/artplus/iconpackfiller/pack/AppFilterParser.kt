package dev.artplus.iconpackfiller.pack

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/**
 * appfilter.xml 解析器。
 *
 * 支持：
 * - `<item component="ComponentInfo{pkg/activity}" drawable="..."/>`
 * - 包级条目 `ComponentInfo{pkg}`
 * - `$` 内部类名（如 `pkg/Outer$Inner`，原样保留大小写语义，仅做包名小写归一）
 * - `<iconback>` / `<iconmask>` / `<iconupon>` / `<iconScale>`（含 `scale` 别名）透传
 * - 未知节点忽略但不报错
 */
object AppFilterParser {

    private const val TAG_ITEM = "item"
    private const val TAG_ICONBACK = "iconback"
    private const val TAG_ICONMASK = "iconmask"
    private const val TAG_ICONUPON = "iconupon"
    private const val TAG_ICONSCALE = "iconscale"
    private const val ATTR_COMPONENT = "component"
    private const val ATTR_DRAWABLE = "drawable"
    private const val ATTR_SCALE = "scale"

    fun parse(input: InputStream): AppFilterDocument {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(input, null)
        return parse(parser)
    }

    /** 复用 Android 的 XmlResourceParser，支持编译后的 res/xml appfilter。 */
    fun parse(parser: XmlPullParser): AppFilterDocument {
        val items = ArrayList<AppFilterItem>()
        var iconback = emptyList<String>()
        var iconmask = emptyList<String>()
        var iconupon = emptyList<String>()
        var scale: Float? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    TAG_ITEM -> {
                        val rawComponent = parser.getAttributeValue(null, ATTR_COMPONENT)
                        val drawable = parser.getAttributeValue(null, ATTR_DRAWABLE)
                        val key = ComponentKey.parse(rawComponent)
                        if (key != null && !drawable.isNullOrBlank()) {
                            items.add(
                                AppFilterItem(
                                    component = key,
                                    drawableName = drawable.trim(),
                                    rawComponent = rawComponent!!.trim(),
                                ),
                            )
                        }
                    }
                    TAG_ICONBACK -> iconback = readImgRefs(parser)
                    TAG_ICONMASK -> iconmask = readImgRefs(parser)
                    TAG_ICONUPON -> iconupon = readImgRefs(parser)
                    TAG_ICONSCALE -> {
                        val value = parser.getAttributeValue(null, ATTR_SCALE)
                            ?: parser.getAttributeValue(null, "value")
                        scale = value?.toFloatOrNull()
                    }
                }
            }
            event = parser.next()
        }

        return AppFilterDocument(
            items = items,
            extras = AppFilterExtras(
                iconback = iconback,
                iconmask = iconmask,
                iconupon = iconupon,
                scale = scale,
            ),
        )
    }

    /** `<iconback img1="a" img2="b"/>` 支持任意数量的 `imgN` 属性。 */
    private fun readImgRefs(parser: XmlPullParser): List<String> {
        val refs = ArrayList<String>()
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i)
            if (name.startsWith("img")) {
                parser.getAttributeValue(i)?.trim()?.takeIf { it.isNotEmpty() }?.let(refs::add)
            }
        }
        return refs
    }
}
