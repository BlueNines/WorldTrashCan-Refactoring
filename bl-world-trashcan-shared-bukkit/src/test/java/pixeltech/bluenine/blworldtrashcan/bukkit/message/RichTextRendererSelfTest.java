package pixeltech.bluenine.blworldtrashcan.bukkit.message;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;

import java.util.List;

/** 不依赖 JUnit 的富文本渲染自测。 */
public final class RichTextRendererSelfTest {
    /** 执行富文本渲染自测。 */
    public static void main(String[] args) {
        assertClickEvent("run command RGB text",
                RichTextRenderer.clickable(null, "&#5AC8FAAI_CLICK_NOTIFY_0 &#FFD166点我", "/blwtc stats"),
                ClickEvent.Action.RUN_COMMAND,
                "/blwtc stats");
        assertClickEvent("suggest command legacy text",
                RichTextRenderer.suggest(null, "&a/blwtc clear false", "/blwtc clear false"),
                ClickEvent.Action.SUGGEST_COMMAND,
                "/blwtc clear false");
        System.out.println("RichTextRendererSelfTest passed");
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

    /** 阻止实例化测试类。 */
    private RichTextRendererSelfTest() {
    }
}
