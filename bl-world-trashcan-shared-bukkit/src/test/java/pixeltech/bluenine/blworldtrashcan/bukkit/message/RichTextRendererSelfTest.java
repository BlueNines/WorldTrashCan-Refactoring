package pixeltech.bluenine.blworldtrashcan.bukkit.message;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;

import java.lang.reflect.Method;
import java.util.List;

/** 不依赖 JUnit 的富文本渲染自测。 */
public final class RichTextRendererSelfTest {
    /** 执行富文本渲染自测。 */
    public static void main(String[] args) {
        assertClickEvent("run command RGB text",
                RichTextRenderer.clickable(null, "&#5AC8FAAI_CLICK_NOTIFY_0 &#FFD166点我", "/wtc stats"),
                ClickEvent.Action.RUN_COMMAND,
                "/wtc stats");
        assertNoLegacyMarkers("run command RGB text",
                RichTextRenderer.clickable(null, "&#5AC8FAAI_CLICK_NOTIFY_0 &#FFD166点我", "/wtc stats"));
        assertClickEvent("suggest command legacy text",
                RichTextRenderer.suggest(null, "&a/wtc clear false", "/wtc clear false"),
                ClickEvent.Action.SUGGEST_COMMAND,
                "/wtc clear false");
        assertRenderedSuggestKeepsLiteralAmpersand();
        assertLegacyFallback();
        System.out.println("RichTextRendererSelfTest passed");
    }

    /** 断言已经渲染的查询文本不会把可复制的 &6 再解释成颜色。 */
    private static void assertRenderedSuggestKeepsLiteralAmpersand() {
        String configName = RichTextRenderer.escapeLiteralAmpersands("&6世界 Boss");
        BaseComponent[] components = RichTextRenderer.suggest(
                null, "&a配置格式: &f" + configName, "&6世界 Boss");
        assertClickEvent("rendered suggest literal ampersand", components,
                ClickEvent.Action.SUGGEST_COMMAND, "&6世界 Boss");
        StringBuilder visible = new StringBuilder();
        for (BaseComponent component : components) {
            if (component != null) {
                visible.append(component.toLegacyText());
            }
        }
        if (!visible.toString().contains("&6世界 Boss")) {
            throw new IllegalStateException("rendered suggest lost literal &6: " + visible);
        }
    }

    /** 断言 PrismaticAPI 兜底路径会降级 RGB 且继续兼容传统 & 颜色。 */
    private static void assertLegacyFallback() {
        try {
            Method method = RichTextRenderer.class.getDeclaredMethod("legacyFallback", String.class);
            method.setAccessible(true);
            String rendered = (String) method.invoke(null, "&#FFD166权限 &a通过");
            if (rendered.contains("&#")) {
                throw new IllegalStateException("legacy fallback leaked raw RGB marker: " + rendered);
            }
            if (!rendered.contains("\u00A7a通过")) {
                throw new IllegalStateException("legacy fallback did not keep &a color: " + rendered);
            }
            if (!rendered.contains("\u00A7")) {
                throw new IllegalStateException("legacy fallback did not render legacy colors: " + rendered);
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("legacy fallback reflection failed", error);
        }
    }

    /** 断言组件树上的所有节点都有预期点击事件。 */
    private static void assertClickEvent(String name, BaseComponent[] components,
                                         ClickEvent.Action action, String value) {
        if (components == null || components.length == 0) {
            throw new IllegalStateException(name + " expected non-empty components");
        }
        int checked = 0;
        for (BaseComponent component : components) {
            checked += assertComponentClickEvent(name, component, action, value);
        }
        if (checked <= 0) {
            throw new IllegalStateException(name + " checked no component");
        }
    }

    /** 递归断言单个组件及子组件的点击事件。 */
    private static int assertComponentClickEvent(String name, BaseComponent component,
                                                ClickEvent.Action action, String value) {
        if (component == null) {
            return 0;
        }
        ClickEvent event = component.getClickEvent();
        if (event == null) {
            throw new IllegalStateException(name + " expected click event on component");
        }
        if (event.getAction() != action) {
            throw new IllegalStateException(name + " expected action " + action + " but got " + event.getAction());
        }
        if (!value.equals(event.getValue())) {
            throw new IllegalStateException(name + " expected value " + value + " but got " + event.getValue());
        }
        int checked = 1;
        List<BaseComponent> extra = component.getExtra();
        if (extra == null || extra.isEmpty()) {
            return checked;
        }
        for (BaseComponent child : extra) {
            checked += assertComponentClickEvent(name, child, action, value);
        }
        return checked;
    }

    /** 断言组件文本字段不携带现代 Paper 禁止发送的 §。 */
    private static void assertNoLegacyMarkers(String name, BaseComponent[] components) {
        if (components == null) {
            return;
        }
        for (BaseComponent component : components) {
            assertComponentHasNoLegacyMarker(name, component);
        }
    }

    /** 递归断言单个组件及其子组件没有 §。 */
    private static void assertComponentHasNoLegacyMarker(String name, BaseComponent component) {
        if (component == null) {
            return;
        }
        if (component instanceof net.md_5.bungee.api.chat.TextComponent
                && ((net.md_5.bungee.api.chat.TextComponent) component).getText().indexOf('\u00A7') >= 0) {
            throw new IllegalStateException(name + " leaked legacy marker: " + component.toLegacyText());
        }
        List<BaseComponent> extra = component.getExtra();
        if (extra == null || extra.isEmpty()) {
            return;
        }
        for (BaseComponent child : extra) {
            assertComponentHasNoLegacyMarker(name, child);
        }
    }

    /** 阻止实例化测试类。 */
    private RichTextRendererSelfTest() {
    }
}
